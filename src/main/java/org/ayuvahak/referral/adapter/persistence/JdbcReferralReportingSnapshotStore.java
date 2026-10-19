package org.ayuvahak.referral.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.referral.application.ReferralReportingCompleteness;
import org.ayuvahak.referral.application.ReferralReportingSnapshot;
import org.ayuvahak.referral.application.ReferralReportingSnapshotException;
import org.ayuvahak.referral.application.ReferralReportingSnapshotRequest;
import org.ayuvahak.referral.application.ReferralReportingSnapshotStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL adapter for immutable Referral-owned aggregate reporting snapshots. */
@Repository
@Lazy
public class JdbcReferralReportingSnapshotStore implements ReferralReportingSnapshotStore {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final RowMapper<SnapshotHeader> HEADER_ROW_MAPPER =
      (row, index) ->
          new SnapshotHeader(
              row.getObject("snapshot_id", UUID.class),
              row.getObject("request_id", UUID.class),
              row.getString("request_fingerprint"),
              row.getObject("origin_organization_id", UUID.class),
              Optional.ofNullable(row.getObject("origin_facility_id", UUID.class)),
              row.getTimestamp("period_start").toInstant(),
              row.getTimestamp("period_end").toInstant(),
              row.getTimestamp("source_cutoff").toInstant(),
              ReferralReportingCompleteness.valueOf(row.getString("completeness")),
              row.getLong("source_version"),
              row.getTimestamp("generated_at").toInstant());

  private final JdbcTemplate jdbc;
  private final TransactionTemplate ownerTransaction;

  public JdbcReferralReportingSnapshotStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    ownerTransaction = new TransactionTemplate(transactionManager);
    ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerTransaction.setTimeout(5);
  }

  @Override
  public ReferralReportingSnapshot getOrCreate(
      UUID organizationId,
      Optional<UUID> facilityId,
      ReferralReportingSnapshotRequest request,
      String requestFingerprint,
      Instant generatedAt) {
    return ownerTransaction.execute(
        ignored -> {
          List<SnapshotHeader> existing = findHeader(request.requestId());
          if (!existing.isEmpty()) {
            return replayOrConflict(
                existing.getFirst(), organizationId, facilityId, requestFingerprint);
          }

          boolean complete = !request.sourceCutoff().isBefore(request.periodEnd());
          Map<String, OptionalLong> measureValues =
              complete
                  ? countMeasures(organizationId, facilityId, request)
                  : emptyMeasures(request);
          UUID snapshotId = UUID.randomUUID();
          int inserted =
              jdbc.update(
                  "INSERT INTO referral.reporting_snapshot (snapshot_id, request_id, "
                      + "request_fingerprint, origin_organization_id, origin_facility_id, period_start, period_end, "
                      + "source_cutoff, completeness, source_version, generated_at) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, nextval('referral.reporting_snapshot_version_seq'), ?) "
                      + "ON CONFLICT (request_id) DO NOTHING",
                  snapshotId,
                  request.requestId(),
                  requestFingerprint,
                  organizationId,
                  facilityId.orElse(null),
                  Timestamp.from(request.periodStart()),
                  Timestamp.from(request.periodEnd()),
                  Timestamp.from(request.sourceCutoff()),
                  complete
                      ? ReferralReportingCompleteness.COMPLETE.name()
                      : ReferralReportingCompleteness.PARTIAL.name(),
                  Timestamp.from(generatedAt));
          if (inserted == 0) {
            SnapshotHeader winner =
                findHeader(request.requestId()).stream().findFirst().orElseThrow();
            return replayOrConflict(winner, organizationId, facilityId, requestFingerprint);
          }

          for (Map.Entry<String, OptionalLong> measure : measureValues.entrySet()) {
            jdbc.update(
                "INSERT INTO referral.reporting_snapshot_measure "
                    + "(snapshot_id, measure_key, measure_value) VALUES (?, ?, ?)",
                snapshotId,
                measure.getKey(),
                measure.getValue().isPresent() ? measure.getValue().getAsLong() : null);
          }
          SnapshotHeader created =
              findHeader(request.requestId()).stream().findFirst().orElseThrow();
          return withMeasures(created, measureValues);
        });
  }

  private ReferralReportingSnapshot replayOrConflict(
      SnapshotHeader header,
      UUID organizationId,
      Optional<UUID> facilityId,
      String requestFingerprint) {
    if (!header.originOrganizationId().equals(organizationId)
        || !header.originFacilityId().equals(facilityId)
        || !header.requestFingerprint().equals(requestFingerprint)) {
      throw new ReferralReportingSnapshotException(
          ReferralReportingSnapshotException.Reason.REQUEST_ID_CONFLICT);
    }
    return withMeasures(header, findMeasures(header.snapshotId()));
  }

  private List<SnapshotHeader> findHeader(UUID requestId) {
    return jdbc.query(
        "SELECT snapshot_id, request_id, request_fingerprint, origin_organization_id, "
            + "origin_facility_id, period_start, period_end, source_cutoff, completeness, source_version, generated_at "
            + "FROM referral.reporting_snapshot WHERE request_id = ?",
        HEADER_ROW_MAPPER,
        requestId);
  }

  private Map<String, OptionalLong> countMeasures(
      UUID organizationId, Optional<UUID> facilityId, ReferralReportingSnapshotRequest request) {
    String facilityFilter = facilityId.isPresent() ? " AND %s.origin_facility_id = ? " : "";
    String query =
        "SELECT (SELECT count(*) FROM referral.submitted_revision revision "
            + "WHERE revision.origin_organization_id = ? AND revision.revision_number = 1 "
            + "AND revision.submitted_at >= ? AND revision.submitted_at < ? "
            + "AND revision.submitted_at <= ?"
            + (facilityId.isPresent() ? facilityFilter.formatted("revision") : "")
            + ") AS submitted_count, "
            + "(SELECT count(*) FROM referral.resolution resolution "
            + "JOIN referral.draft draft ON draft.referral_id = resolution.referral_id "
            + "WHERE draft.origin_organization_id = ? AND resolution.resolved_at >= ? "
            + "AND resolution.resolved_at < ? AND resolution.resolved_at <= ?"
            + (facilityId.isPresent() ? facilityFilter.formatted("draft") : "")
            + ") AS resolved_count";
    List<Object> parameters = new java.util.ArrayList<>();
    parameters.add(organizationId);
    parameters.add(Timestamp.from(request.periodStart()));
    parameters.add(Timestamp.from(request.periodEnd()));
    parameters.add(Timestamp.from(request.sourceCutoff()));
    facilityId.ifPresent(parameters::add);
    parameters.add(organizationId);
    parameters.add(Timestamp.from(request.periodStart()));
    parameters.add(Timestamp.from(request.periodEnd()));
    parameters.add(Timestamp.from(request.sourceCutoff()));
    facilityId.ifPresent(parameters::add);
    List<Map<String, Object>> rows = jdbc.queryForList(query, parameters.toArray());
    Map<String, Object> row = rows.getFirst();
    Map<String, OptionalLong> result = new LinkedHashMap<>();
    for (String key : request.measureKeys().stream().sorted().toList()) {
      long value =
          ((Number) row.get(key.equals("SUBMITTED_COUNT") ? "submitted_count" : "resolved_count"))
              .longValue();
      result.put(key, OptionalLong.of(value));
    }
    return Map.copyOf(result);
  }

  private static Map<String, OptionalLong> emptyMeasures(ReferralReportingSnapshotRequest request) {
    Map<String, OptionalLong> result = new LinkedHashMap<>();
    request.measureKeys().stream().sorted().forEach(key -> result.put(key, OptionalLong.empty()));
    return Map.copyOf(result);
  }

  private Map<String, OptionalLong> findMeasures(UUID snapshotId) {
    Map<String, OptionalLong> result = new LinkedHashMap<>();
    jdbc.query(
        "SELECT measure_key, measure_value FROM referral.reporting_snapshot_measure "
            + "WHERE snapshot_id = ? ORDER BY measure_key",
        row -> {
          long value = row.getLong("measure_value");
          result.put(
              row.getString("measure_key"),
              row.wasNull() ? OptionalLong.empty() : OptionalLong.of(value));
        },
        snapshotId);
    return Map.copyOf(result);
  }

  private static ReferralReportingSnapshot withMeasures(
      SnapshotHeader header, Map<String, OptionalLong> measures) {
    return new ReferralReportingSnapshot(
        new ExternalReference(
            new IssuerId(DIRECTORY_ISSUER),
            "Organization",
            header.originOrganizationId().toString()),
        header
            .originFacilityId()
            .map(
                facilityId ->
                    new ExternalReference(
                        new IssuerId(DIRECTORY_ISSUER), "Facility", facilityId.toString())),
        header.periodStart(),
        header.periodEnd(),
        header.sourceCutoff(),
        header.completeness(),
        measures,
        header.sourceVersion(),
        header.snapshotId(),
        header.generatedAt());
  }

  private record SnapshotHeader(
      UUID snapshotId,
      UUID requestId,
      String requestFingerprint,
      UUID originOrganizationId,
      Optional<UUID> originFacilityId,
      Instant periodStart,
      Instant periodEnd,
      Instant sourceCutoff,
      ReferralReportingCompleteness completeness,
      long sourceVersion,
      Instant generatedAt) {}
}
