package org.ayuvahak.analytics.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.application.Dhis2ExportOperationConflictException;
import org.ayuvahak.analytics.application.Dhis2ExportOperationStore;
import org.ayuvahak.analytics.domain.Dhis2DataValueTuple;
import org.ayuvahak.analytics.domain.Dhis2ExportOperation;
import org.ayuvahak.analytics.domain.Dhis2ExportReconciliation;
import org.ayuvahak.analytics.domain.Dhis2ExportState;
import org.ayuvahak.analytics.domain.Dhis2MappingVersion;
import org.ayuvahak.analytics.domain.ReportingPeriod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists only sanitized export operation metadata and never stores the request/response body. */
@Repository
public class JdbcDhis2ExportOperationStore implements Dhis2ExportOperationStore {

  private static final String SELECT_OPERATION =
      "SELECT operation_id, operation_fingerprint, snapshot_id, revision_series_id, revision_number, "
          + "mapping_key, mapping_version, partner_key, partner_configuration_version, data_element_uid, "
          + "org_unit_uid, period_code, category_option_combo_uid, attribute_option_combo_uid, data_set_uid, "
          + "disclosure_reservation_id, operation_state, result_class, attempt_count, imported_count, "
          + "updated_count, ignored_count, deleted_count, audit_record_id, created_at, updated_at "
          + "FROM analytics.dhis2_export_operation ";

  private final JdbcTemplate jdbc;

  public JdbcDhis2ExportOperationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public CreateResult createOrReplay(Dhis2ExportOperation operation) {
    int inserted =
        jdbc.update(
            "INSERT INTO analytics.dhis2_export_operation (operation_id, operation_fingerprint, snapshot_id, "
                + "revision_series_id, revision_number, mapping_key, mapping_version, partner_key, "
                + "partner_configuration_version, data_element_uid, org_unit_uid, period_code, "
                + "category_option_combo_uid, attribute_option_combo_uid, data_set_uid, "
                + "disclosure_reservation_id, operation_state, result_class, attempt_count, audit_record_id, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (operation_id) DO NOTHING",
            operation.operationId(),
            operation.fingerprint(),
            operation.snapshotId(),
            operation.revisionSeriesId(),
            operation.revisionNumber(),
            operation.mappingKey(),
            operation.mappingVersion(),
            operation.partnerKey(),
            operation.partnerConfigurationVersion().orElse(null),
            operation.targetTuple().dataElementUid(),
            operation.targetTuple().orgUnitUid(),
            operation.targetTuple().period(),
            operation.targetTuple().categoryOptionComboUid().orElse(null),
            operation.targetTuple().attributeOptionComboUid().orElse(null),
            operation.dataSetUid().orElse(null),
            operation.disclosureReservationId().orElse(null),
            operation.state().name(),
            operation.resultClass(),
            operation.attemptCount(),
            operation.auditRecordId().orElse(null),
            Timestamp.from(operation.createdAt()),
            Timestamp.from(operation.updatedAt()));
    if (inserted == 1) {
      return new CreateResult(operation, true);
    }
    return find(operation.operationId())
        .filter(existing -> existing.fingerprint().equals(operation.fingerprint()))
        .map(existing -> new CreateResult(existing, false))
        .orElseThrow(Dhis2ExportOperationConflictException::new);
  }

  @Override
  public Optional<Dhis2ExportOperation> find(UUID operationId) {
    return jdbc
        .query(
            SELECT_OPERATION + "WHERE operation_id = ?",
            (result, row) -> fromRow(result),
            operationId)
        .stream()
        .findFirst();
  }

  @Override
  public TargetTupleClaimResult acquireTargetTupleClaim(
      Dhis2ExportOperation operation, Instant claimedAt) {
    Objects.requireNonNull(operation, "export operation");
    Objects.requireNonNull(claimedAt, "claim time");
    var tuple = operation.targetTuple();
    lockTargetTuple(operation);

    List<OperationVersion> acknowledged =
        jdbc.query(
            "SELECT operation_id, revision_series_id, revision_number "
                + "FROM analytics.dhis2_export_operation WHERE "
                + tuplePredicate()
                + " AND operation_state = 'ACKNOWLEDGED' AND operation_id <> ? "
                + "ORDER BY updated_at DESC, operation_id",
            (result, row) ->
                new OperationVersion(
                    result.getObject("operation_id", UUID.class),
                    result.getObject("revision_series_id", UUID.class),
                    result.getInt("revision_number")),
            tupleAndTrailingParameters(operation, operation.operationId()));
    for (OperationVersion prior : acknowledged) {
      if (!prior.revisionSeriesId().equals(operation.revisionSeriesId())) {
        return new TargetTupleClaimResult(
            TargetTupleClaimOutcome.CONFLICTING_SERIES, Optional.of(prior.operationId()));
      }
      if (prior.revisionNumber() >= operation.revisionNumber()) {
        return new TargetTupleClaimResult(
            TargetTupleClaimOutcome.STALE_REVISION, Optional.of(prior.operationId()));
      }
    }

    Optional<OperationVersion> olderPrepared =
        jdbc
            .query(
                "SELECT operation_id, revision_series_id, revision_number "
                    + "FROM analytics.dhis2_export_operation WHERE "
                    + tuplePredicate()
                    + " AND operation_state = 'PREPARED' AND revision_series_id = ? "
                    + "AND revision_number < ? AND operation_id <> ? "
                    + "ORDER BY revision_number, created_at, operation_id LIMIT 1",
                (result, row) ->
                    new OperationVersion(
                        result.getObject("operation_id", UUID.class),
                        result.getObject("revision_series_id", UUID.class),
                        result.getInt("revision_number")),
                tupleAndTrailingParameters(
                    operation,
                    operation.revisionSeriesId(),
                    operation.revisionNumber(),
                    operation.operationId()))
            .stream()
            .findFirst();
    if (olderPrepared.isPresent()) {
      return new TargetTupleClaimResult(
          TargetTupleClaimOutcome.BLOCKED_BY_OLDER,
          Optional.of(olderPrepared.orElseThrow().operationId()));
    }

    int inserted =
        jdbc.update(
            "INSERT INTO analytics.dhis2_export_target_tuple_claim (partner_key, data_element_uid, "
                + "period_code, org_unit_uid, category_option_combo_uid, attribute_option_combo_uid, "
                + "operation_id, claimed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING",
            operation.partnerKey(),
            tuple.dataElementUid(),
            tuple.period(),
            tuple.orgUnitUid(),
            tuple.categoryOptionComboUid().orElse(null),
            tuple.attributeOptionComboUid().orElse(null),
            operation.operationId(),
            Timestamp.from(claimedAt));
    if (inserted == 1) {
      return new TargetTupleClaimResult(TargetTupleClaimOutcome.ACQUIRED, Optional.empty());
    }
    UUID holder =
        jdbc.queryForObject(
            "SELECT operation_id FROM analytics.dhis2_export_target_tuple_claim WHERE "
                + tuplePredicate(),
            UUID.class,
            tupleParameters(operation));
    return new TargetTupleClaimResult(
        holder.equals(operation.operationId())
            ? TargetTupleClaimOutcome.SAME_OPERATION
            : TargetTupleClaimOutcome.HELD_BY_OTHER,
        Optional.of(holder));
  }

  @Override
  public boolean hasTargetTupleClaim(UUID operationId) {
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM analytics.dhis2_export_target_tuple_claim "
                + "WHERE operation_id = ?)",
            Boolean.class,
            operationId);
    return Boolean.TRUE.equals(exists);
  }

  @Override
  public void recordReconciliation(Dhis2ExportReconciliation reconciliation) {
    Objects.requireNonNull(reconciliation, "reconciliation");
    jdbc.update(
        "INSERT INTO analytics.dhis2_export_reconciliation (operation_id, resolution, "
            + "evidence_reference, observed_value, audit_record_id, resolved_at) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        reconciliation.operationId(),
        reconciliation.resolution().name(),
        reconciliation.evidenceReference(),
        reconciliation.observedValue().isPresent()
            ? reconciliation.observedValue().getAsLong()
            : null,
        reconciliation.auditRecordId(),
        Timestamp.from(reconciliation.resolvedAt()));
  }

  @Override
  public Optional<Dhis2ExportReconciliation> findReconciliation(UUID operationId) {
    return jdbc
        .query(
            "SELECT operation_id, resolution, evidence_reference, observed_value, audit_record_id, "
                + "resolved_at FROM analytics.dhis2_export_reconciliation WHERE operation_id = ?",
            (result, row) -> {
              Long observedValue = (Long) result.getObject("observed_value");
              return new Dhis2ExportReconciliation(
                  result.getObject("operation_id", UUID.class),
                  Dhis2ExportReconciliation.Resolution.valueOf(result.getString("resolution")),
                  result.getObject("evidence_reference", UUID.class),
                  observedValue == null
                      ? java.util.OptionalLong.empty()
                      : java.util.OptionalLong.of(observedValue),
                  result.getObject("audit_record_id", UUID.class),
                  result.getTimestamp("resolved_at").toInstant());
            },
            operationId)
        .stream()
        .findFirst();
  }

  @Override
  public boolean hasUnrepresentableOverlappingRelease(
      Dhis2MappingVersion mapping, ReportingPeriod period, UUID currentOperationId) {
    Objects.requireNonNull(mapping, "DHIS2 mapping");
    Objects.requireNonNull(period, "reporting period");
    Objects.requireNonNull(currentOperationId, "current operation id");
    String organizationScope =
        mapping.organization().issuer().value()
            + "|"
            + mapping.organization().resourceType()
            + "|"
            + mapping.organization().value();
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        result -> {
          while (result.next()) {
            // Consuming the row keeps the transaction-scoped lock held until the caller commits.
          }
        },
        organizationScope);
    Boolean blocked =
        jdbc.queryForObject(
            "SELECT EXISTS ("
                + "SELECT 1 FROM analytics.dhis2_export_operation prior_operation "
                + "JOIN analytics.aggregate_snapshot prior_snapshot "
                + "ON prior_snapshot.snapshot_id = prior_operation.snapshot_id "
                + "JOIN analytics.dhis2_mapping_version prior_mapping "
                + "ON prior_mapping.mapping_key = prior_operation.mapping_key "
                + "AND prior_mapping.mapping_version = prior_operation.mapping_version "
                + "JOIN analytics.disclosure_reservation prior_reservation "
                + "ON prior_reservation.reservation_id = prior_operation.disclosure_reservation_id "
                + "WHERE prior_operation.operation_id <> ? "
                + "AND prior_reservation.state IN ('RESERVED', 'DISCLOSED', 'POTENTIALLY_DISCLOSED') "
                + "AND prior_snapshot.organization_issuer = ? "
                + "AND prior_snapshot.organization_resource_type = ? "
                + "AND prior_snapshot.organization_id = ? "
                + "AND (prior_snapshot.indicator_key = ? OR prior_mapping.disclosure_policy_key = ?) "
                + "AND prior_snapshot.period_start_utc < ? "
                + "AND prior_snapshot.period_end_exclusive_utc > ? "
                + "AND NOT (prior_snapshot.indicator_key = ? "
                + "AND prior_snapshot.definition_version = ? "
                + "AND prior_snapshot.period_start_utc = ? "
                + "AND prior_snapshot.period_end_exclusive_utc = ? "
                + "AND prior_mapping.disclosure_policy_key = ? "
                + "AND prior_mapping.disclosure_output_key = ?)"
                + ")",
            Boolean.class,
            currentOperationId,
            mapping.organization().issuer().value(),
            mapping.organization().resourceType(),
            UUID.fromString(mapping.organization().value()),
            mapping.indicatorKey(),
            mapping.disclosurePolicyKey(),
            Timestamp.from(period.endExclusiveUtc()),
            Timestamp.from(period.startInclusiveUtc()),
            mapping.indicatorKey(),
            mapping.indicatorVersion(),
            Timestamp.from(period.startInclusiveUtc()),
            Timestamp.from(period.endExclusiveUtc()),
            mapping.disclosurePolicyKey(),
            mapping.disclosureOutputKey());
    return Boolean.TRUE.equals(blocked);
  }

  @Override
  public Dhis2ExportOperation update(
      UUID operationId,
      Dhis2ExportState expectedState,
      Dhis2ExportState state,
      String resultClass,
      int attemptCount,
      Optional<Integer> partnerConfigurationVersion,
      Optional<Dhis2ExportOperation.Dhis2ImportCounts> importCounts,
      Optional<UUID> disclosureReservationId,
      Instant changedAt,
      UUID auditRecordId) {
    Dhis2ExportOperation.Dhis2ImportCounts counts = importCounts.orElse(null);
    if (state == Dhis2ExportState.ACKNOWLEDGED
        || state == Dhis2ExportState.REJECTED
        || state == Dhis2ExportState.WITHHELD) {
      find(operationId).ifPresent(this::lockTargetTuple);
    }
    int changed =
        jdbc.update(
            "UPDATE analytics.dhis2_export_operation SET operation_state = ?, result_class = ?, "
                + "attempt_count = ?, partner_configuration_version = COALESCE(?, partner_configuration_version), "
                + "imported_count = COALESCE(?, imported_count), updated_count = COALESCE(?, updated_count), "
                + "ignored_count = COALESCE(?, ignored_count), deleted_count = COALESCE(?, deleted_count), "
                + "disclosure_reservation_id = COALESCE(?, disclosure_reservation_id), audit_record_id = ?, updated_at = ? "
                + "WHERE operation_id = ? AND operation_state = ?",
            state.name(),
            resultClass,
            attemptCount,
            partnerConfigurationVersion.orElse(null),
            counts == null ? null : counts.imported(),
            counts == null ? null : counts.updated(),
            counts == null ? null : counts.ignored(),
            counts == null ? null : counts.deleted(),
            disclosureReservationId.orElse(null),
            auditRecordId,
            Timestamp.from(changedAt),
            operationId,
            expectedState.name());
    if (changed != 1) {
      throw new Dhis2ExportOperationConflictException();
    }
    if (state == Dhis2ExportState.ACKNOWLEDGED
        || state == Dhis2ExportState.REJECTED
        || state == Dhis2ExportState.WITHHELD) {
      jdbc.update(
          "DELETE FROM analytics.dhis2_export_target_tuple_claim WHERE operation_id = ?",
          operationId);
    }
    return find(operationId).orElseThrow(Dhis2ExportOperationConflictException::new);
  }

  private static String tuplePredicate() {
    return "partner_key = ? AND data_element_uid = ? AND period_code = ? AND org_unit_uid = ? "
        + "AND category_option_combo_uid IS NOT DISTINCT FROM ? "
        + "AND attribute_option_combo_uid IS NOT DISTINCT FROM ?";
  }

  private void lockTargetTuple(Dhis2ExportOperation operation) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        result -> {
          while (result.next()) {
            // Hold the exact-target ordering lock until the surrounding transaction commits.
          }
        },
        operation.partnerKey() + "\n" + operation.targetTuple().stableKey());
  }

  private static Object[] tupleParameters(Dhis2ExportOperation operation) {
    var tuple = operation.targetTuple();
    return new Object[] {
      operation.partnerKey(),
      tuple.dataElementUid(),
      tuple.period(),
      tuple.orgUnitUid(),
      tuple.categoryOptionComboUid().orElse(null),
      tuple.attributeOptionComboUid().orElse(null)
    };
  }

  private static Object[] tupleAndTrailingParameters(
      Dhis2ExportOperation operation, Object... trailing) {
    Object[] tuple = tupleParameters(operation);
    Object[] parameters = Arrays.copyOf(tuple, tuple.length + trailing.length);
    System.arraycopy(trailing, 0, parameters, tuple.length, trailing.length);
    return parameters;
  }

  private record OperationVersion(UUID operationId, UUID revisionSeriesId, int revisionNumber) {}

  private static Dhis2ExportOperation fromRow(ResultSet result) throws SQLException {
    return new Dhis2ExportOperation(
        result.getObject("operation_id", UUID.class),
        result.getString("operation_fingerprint"),
        result.getObject("snapshot_id", UUID.class),
        result.getObject("revision_series_id", UUID.class),
        result.getInt("revision_number"),
        result.getString("mapping_key"),
        result.getInt("mapping_version"),
        result.getString("partner_key"),
        Optional.ofNullable((Integer) result.getObject("partner_configuration_version")),
        new Dhis2DataValueTuple(
            result.getString("data_element_uid"),
            result.getString("period_code"),
            result.getString("org_unit_uid"),
            Optional.ofNullable(result.getString("category_option_combo_uid")),
            Optional.ofNullable(result.getString("attribute_option_combo_uid"))),
        Optional.ofNullable(result.getString("data_set_uid")),
        Optional.ofNullable(result.getObject("disclosure_reservation_id", UUID.class)),
        Dhis2ExportState.valueOf(result.getString("operation_state")),
        result.getString("result_class"),
        result.getInt("attempt_count"),
        importCounts(result),
        Optional.ofNullable(result.getObject("audit_record_id", UUID.class)),
        result.getTimestamp("created_at").toInstant(),
        result.getTimestamp("updated_at").toInstant());
  }

  private static Optional<Dhis2ExportOperation.Dhis2ImportCounts> importCounts(ResultSet result)
      throws SQLException {
    Integer imported = (Integer) result.getObject("imported_count");
    Integer updated = (Integer) result.getObject("updated_count");
    Integer ignored = (Integer) result.getObject("ignored_count");
    Integer deleted = (Integer) result.getObject("deleted_count");
    if (imported == null || updated == null || ignored == null || deleted == null) {
      return Optional.empty();
    }
    return Optional.of(
        new Dhis2ExportOperation.Dhis2ImportCounts(imported, updated, ignored, deleted));
  }
}
