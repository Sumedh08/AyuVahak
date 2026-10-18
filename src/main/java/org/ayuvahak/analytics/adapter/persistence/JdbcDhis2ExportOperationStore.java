package org.ayuvahak.analytics.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.application.Dhis2ExportOperationConflictException;
import org.ayuvahak.analytics.application.Dhis2ExportOperationStore;
import org.ayuvahak.analytics.domain.Dhis2DataValueTuple;
import org.ayuvahak.analytics.domain.Dhis2ExportOperation;
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
    return find(operationId).orElseThrow(Dhis2ExportOperationConflictException::new);
  }

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
