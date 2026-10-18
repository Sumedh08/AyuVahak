package org.ayuvahak.transfer.adapter.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.transfer.application.TransferReportingSnapshotConflictException;
import org.ayuvahak.transfer.application.TransferReportingSnapshotPort;
import org.ayuvahak.transfer.application.TransferReportingSnapshotPort.Attribution;
import org.ayuvahak.transfer.application.TransferReportingSnapshotPort.Snapshot;
import org.ayuvahak.transfer.application.TransferReportingSnapshotPort.SnapshotRequest;
import org.ayuvahak.transfer.application.TransferReportingSnapshotStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL adapter for the small, immutable Transfer reporting contract. */
@Repository
@Lazy
public class JdbcTransferReportingSnapshotStore implements TransferReportingSnapshotStore {

  private static final RowMapper<StoredSnapshot> SNAPSHOT_ROW =
      (row, ignored) ->
          new StoredSnapshot(
              row.getObject("source_snapshot_id", UUID.class),
              row.getString("request_fingerprint"),
              row.getTimestamp("source_cutoff").toInstant(),
              row.getLong("value"),
              row.getTimestamp("generated_at").toInstant());

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  public JdbcTransferReportingSnapshotStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbcTemplate");
    transaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  @Override
  public Snapshot createOrReplay(
      ExecutionScope scope, SnapshotRequest request, Instant generatedAt) {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(generatedAt, "generatedAt");
    Instant persistedGeneratedAt = postgresPrecision(generatedAt);
    UUID organizationId = directoryId(scope.organization().orElseThrow().value());
    UUID facilityId =
        scope.facility().map(reference -> directoryId(reference.value())).orElse(null);
    String fingerprint = fingerprint(scope, request);
    Snapshot stored =
        transaction.execute(
            ignored -> {
              lockRequest(request.requestId());
              var existing = find(request.requestId());
              if (existing.isPresent()) {
                StoredSnapshot prior = existing.orElseThrow();
                if (!prior.requestFingerprint().equals(fingerprint)) {
                  throw new TransferReportingSnapshotConflictException();
                }
                return snapshot(scope, request, prior);
              }

              CountAtCutoff count = count(scope, request);
              InternalId sourceSnapshotId = InternalId.generate();
              jdbc.update(
                  "INSERT INTO transfer.reporting_snapshot (source_snapshot_id, request_id, "
                      + "request_fingerprint, measure_key, attribution, scope_kind, organization_id, "
                      + "facility_id, period_start, period_end, source_cutoff, owner_contract_version, "
                      + "completeness, value, generated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'COMPLETE', ?, ?)",
                  sourceSnapshotId.value(),
                  request.requestId().value(),
                  fingerprint,
                  request.measure().key(),
                  request.attribution().name(),
                  scope.kind().name(),
                  organizationId,
                  facilityId,
                  timestamp(request.periodStart()),
                  timestamp(request.periodEnd()),
                  timestamp(count.sourceCutoff()),
                  TransferReportingSnapshotPort.OWNER_CONTRACT_VERSION,
                  count.value(),
                  timestamp(persistedGeneratedAt));
              return new Snapshot(
                  sourceSnapshotId,
                  request.measure().key(),
                  request.attribution(),
                  scope,
                  request.periodStart(),
                  request.periodEnd(),
                  count.sourceCutoff(),
                  TransferReportingSnapshotPort.OWNER_CONTRACT_VERSION,
                  TransferReportingSnapshotPort.Completeness.COMPLETE,
                  count.value(),
                  dimensions(scope, request.attribution()),
                  persistedGeneratedAt);
            });
    return Objects.requireNonNull(stored, "snapshot transaction result");
  }

  private void lockRequest(InternalId requestId) {
    ResultSetExtractor<Void> consumeLockResult =
        result -> {
          result.next();
          return null;
        };
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        consumeLockResult,
        requestId.toString());
  }

  private java.util.Optional<StoredSnapshot> find(InternalId requestId) {
    List<StoredSnapshot> found =
        jdbc.query(
            "SELECT source_snapshot_id, request_fingerprint, source_cutoff, value, generated_at "
                + "FROM transfer.reporting_snapshot WHERE request_id = ?",
            SNAPSHOT_ROW,
            requestId.value());
    return found.stream().findFirst();
  }

  private Snapshot snapshot(ExecutionScope scope, SnapshotRequest request, StoredSnapshot stored) {
    return new Snapshot(
        InternalId.parse(stored.sourceSnapshotId().toString()),
        request.measure().key(),
        request.attribution(),
        scope,
        request.periodStart(),
        request.periodEnd(),
        stored.sourceCutoff(),
        TransferReportingSnapshotPort.OWNER_CONTRACT_VERSION,
        TransferReportingSnapshotPort.Completeness.COMPLETE,
        stored.value(),
        dimensions(scope, request.attribution()),
        stored.generatedAt());
  }

  private CountAtCutoff count(ExecutionScope scope, SnapshotRequest request) {
    SqlQuery query =
        switch (request.measure()) {
          case ACCEPTED -> acceptedQuery(scope, request);
          case COMPLETED -> completedQuery(scope, request);
          case UNFULFILLED -> unfulfilledQuery(scope, request);
        };
    return jdbc.queryForObject(
        query.sql(),
        (row, ignored) ->
            new CountAtCutoff(row.getTimestamp("source_cutoff").toInstant(), row.getLong("value")),
        query.parameters().toArray());
  }

  private SqlQuery acceptedQuery(ExecutionScope scope, SnapshotRequest request) {
    var parameters = new ArrayList<Object>();
    String sourceQuery;
    if (request.attribution() == Attribution.ORIGIN) {
      sourceQuery =
          "SELECT d.transfer_id AS reporting_fact FROM transfer.request_decision d "
              + "JOIN transfer.request_attempt a ON a.request_attempt_id = d.request_attempt_id "
              + "JOIN transfer.journey j ON j.journey_id = a.journey_id CROSS JOIN cutoff c "
              + "WHERE d.decision_kind = 'ACCEPTED' AND d.occurred_at >= ? AND d.occurred_at < ? "
              + "AND d.recorded_at <= c.source_cutoff AND "
              + scopePredicate(
                  "j.origin_organization_id", "j.origin_facility_id", scope, parameters)
              + " UNION ALL SELECT f.origin_attempt_id AS reporting_fact "
              + "FROM transfer.federated_origin_receiving_attempt f "
              + "JOIN transfer.journey j ON j.journey_id = f.journey_id CROSS JOIN cutoff c "
              + "WHERE f.remote_outcome IN ('ACCEPTED_RESERVED', 'ACCEPTED_UNRESERVED') "
              + "AND f.remote_accepted_at >= ? AND f.remote_accepted_at < ? "
              + "AND f.updated_at <= c.source_cutoff AND "
              + scopePredicate(
                  "j.origin_organization_id", "j.origin_facility_id", scope, parameters);
      parameters.add(0, timestamp(request.periodStart()));
      parameters.add(1, timestamp(request.periodEnd()));
      // The second half of the UNION has its own period placeholders after the first scope.
      parameters.add(2 + scopeParameterCount(scope), timestamp(request.periodStart()));
      parameters.add(3 + scopeParameterCount(scope), timestamp(request.periodEnd()));
    } else {
      sourceQuery =
          "SELECT d.transfer_id AS reporting_fact FROM transfer.request_decision d "
              + "CROSS JOIN cutoff c WHERE d.decision_kind = 'ACCEPTED' "
              + "AND d.occurred_at >= ? AND d.occurred_at < ? AND d.recorded_at <= c.source_cutoff AND "
              + scopePredicate(
                  "d.receiving_organization_id", "d.receiving_facility_id", scope, parameters)
              + " UNION ALL SELECT r.receiving_request_id AS reporting_fact "
              + "FROM transfer.federated_receiving_request r CROSS JOIN cutoff c "
              + "WHERE r.request_state IN ('ACCEPTED_RESERVED', 'ACCEPTED_UNRESERVED') "
              + "AND r.accepted_at >= ? AND r.accepted_at < ? AND r.updated_at <= c.source_cutoff AND "
              + scopePredicate(
                  "r.destination_organization_id", "r.destination_facility_id", scope, parameters);
      parameters.add(0, timestamp(request.periodStart()));
      parameters.add(1, timestamp(request.periodEnd()));
      parameters.add(2 + scopeParameterCount(scope), timestamp(request.periodStart()));
      parameters.add(3 + scopeParameterCount(scope), timestamp(request.periodEnd()));
    }
    return new SqlQuery(
        "WITH cutoff AS (SELECT statement_timestamp() AS source_cutoff), reporting_facts AS ("
            + sourceQuery
            + ") SELECT c.source_cutoff, COUNT(f.reporting_fact) AS value FROM cutoff c "
            + "LEFT JOIN reporting_facts f ON TRUE GROUP BY c.source_cutoff",
        List.copyOf(parameters));
  }

  private SqlQuery completedQuery(ExecutionScope scope, SnapshotRequest request) {
    var parameters = new ArrayList<Object>();
    String org;
    String facility;
    if (request.attribution() == Attribution.ORIGIN) {
      org = "j.origin_organization_id";
      facility = "j.origin_facility_id";
    } else {
      org = "p.organization_id";
      facility = "p.facility_id";
    }
    String scopeFilter = scopePredicate(org, facility, scope, parameters);
    parameters.add(0, timestamp(request.periodStart()));
    parameters.add(1, timestamp(request.periodEnd()));
    String sql =
        "WITH cutoff AS (SELECT statement_timestamp() AS source_cutoff), completed AS ("
            + "SELECT p.transfer_id FROM transfer.progress_fact p "
            + "JOIN transfer.journey j ON j.journey_id = p.journey_id CROSS JOIN cutoff c "
            + "WHERE p.fact_kind = 'COMPLETION' AND p.recorded_at <= c.source_cutoff "
            + "AND COALESCE((SELECT correction.corrected_occurred_at "
            + "FROM transfer.progress_correction correction "
            + "WHERE correction.corrects_progress_fact_id = p.progress_fact_id "
            + "AND correction.correction_kind = 'OCCURRENCE_TIME' "
            + "AND correction.recorded_at <= c.source_cutoff "
            + "ORDER BY correction.aggregate_sequence DESC LIMIT 1), p.occurred_at) >= ? "
            + "AND COALESCE((SELECT correction.corrected_occurred_at "
            + "FROM transfer.progress_correction correction "
            + "WHERE correction.corrects_progress_fact_id = p.progress_fact_id "
            + "AND correction.correction_kind = 'OCCURRENCE_TIME' "
            + "AND correction.recorded_at <= c.source_cutoff "
            + "ORDER BY correction.aggregate_sequence DESC LIMIT 1), p.occurred_at) < ? "
            + "AND "
            + scopeFilter
            + ") SELECT c.source_cutoff, COUNT(DISTINCT completed.transfer_id) AS value "
            + "FROM cutoff c LEFT JOIN completed ON TRUE GROUP BY c.source_cutoff";
    return new SqlQuery(sql, List.copyOf(parameters));
  }

  private SqlQuery unfulfilledQuery(ExecutionScope scope, SnapshotRequest request) {
    var parameters = new ArrayList<Object>();
    String scopeFilter =
        scopePredicate("closure.organization_id", "closure.facility_id", scope, parameters);
    parameters.add(0, timestamp(request.periodStart()));
    parameters.add(1, timestamp(request.periodEnd()));
    String sql =
        "WITH cutoff AS (SELECT statement_timestamp() AS source_cutoff), unfulfilled AS ("
            + "SELECT closure.journey_id FROM transfer.journey_closure closure CROSS JOIN cutoff c "
            + "WHERE closure.outcome = 'UNFULFILLED' AND closure.occurred_at >= ? "
            + "AND closure.occurred_at < ? AND closure.recorded_at <= c.source_cutoff AND "
            + scopeFilter
            + ") SELECT c.source_cutoff, COUNT(DISTINCT unfulfilled.journey_id) AS value "
            + "FROM cutoff c LEFT JOIN unfulfilled ON TRUE GROUP BY c.source_cutoff";
    return new SqlQuery(sql, List.copyOf(parameters));
  }

  private static String scopePredicate(
      String organizationColumn,
      String facilityColumn,
      ExecutionScope scope,
      List<Object> parameters) {
    parameters.add(directoryId(scope.organization().orElseThrow().value()));
    String predicate = organizationColumn + " = ?";
    if (scope.facility().isPresent()) {
      parameters.add(directoryId(scope.facility().orElseThrow().value()));
      predicate += " AND " + facilityColumn + " = ?";
    }
    return predicate;
  }

  private static int scopeParameterCount(ExecutionScope scope) {
    return scope.facility().isPresent() ? 2 : 1;
  }

  private static Map<String, String> dimensions(ExecutionScope scope, Attribution attribution) {
    var dimensions = new LinkedHashMap<String, String>();
    dimensions.put("attribution", attribution.name());
    dimensions.put("organization", scope.organization().orElseThrow().value());
    scope.facility().ifPresent(facility -> dimensions.put("facility", facility.value()));
    return Map.copyOf(dimensions);
  }

  private static String fingerprint(ExecutionScope scope, SnapshotRequest request) {
    String canonical =
        String.join(
            "\n",
            request.measure().key(),
            request.attribution().name(),
            scope.kind().name(),
            scope.organization().orElseThrow().issuer().value(),
            scope.organization().orElseThrow().resourceType(),
            scope.organization().orElseThrow().value(),
            scope.facility().map(reference -> reference.issuer().value()).orElse(""),
            scope.facility().map(reference -> reference.resourceType()).orElse(""),
            scope.facility().map(reference -> reference.value()).orElse(""),
            request.periodStart().toString(),
            request.periodEnd().toString());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
    }
  }

  private static UUID directoryId(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("validated Directory identifiers must be UUIDs", invalid);
    }
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }

  private static Instant postgresPrecision(Instant instant) {
    return Instant.ofEpochSecond(instant.getEpochSecond(), instant.getNano() / 1_000 * 1_000L);
  }

  private record CountAtCutoff(Instant sourceCutoff, long value) {}

  private record StoredSnapshot(
      UUID sourceSnapshotId,
      String requestFingerprint,
      Instant sourceCutoff,
      long value,
      Instant generatedAt) {}

  private record SqlQuery(String sql, List<Object> parameters) {}
}
