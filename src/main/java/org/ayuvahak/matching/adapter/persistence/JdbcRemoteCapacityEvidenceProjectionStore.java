package org.ayuvahak.matching.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.matching.application.RemoteCapacityEvidenceProjectionStore;
import org.ayuvahak.matching.domain.RemoteCapacityEvidence;
import org.ayuvahak.matching.domain.RemoteCapacityOutcome;
import org.ayuvahak.matching.domain.RemoteEvidenceFreshness;
import org.ayuvahak.matching.domain.RemoteFacilityReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persists one current source version per remote Facility and CapacityDimension. */
@Repository
public class JdbcRemoteCapacityEvidenceProjectionStore
    implements RemoteCapacityEvidenceProjectionStore {

  private static final String KEY_PREDICATE =
      "remote_deployment_id = ? AND remote_context = ? AND remote_facility_id = ? "
          + "AND dimension_code = ?";

  private static final RowMapper<RemoteCapacityEvidence> ROW_MAPPER =
      JdbcRemoteCapacityEvidenceProjectionStore::mapEvidence;

  private final JdbcTemplate jdbc;

  public JdbcRemoteCapacityEvidenceProjectionStore(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc template");
  }

  @Override
  @Transactional
  public RecordResult record(RemoteCapacityEvidence evidence) {
    java.util.Objects.requireNonNull(evidence, "remote Capacity evidence");
    String fingerprint = RemoteCapacityEvidenceFingerprint.calculate(evidence);
    List<Long> inserted =
        jdbc.query(
            "INSERT INTO matching.remote_capacity_evidence_projection ("
                + "remote_deployment_id, remote_context, remote_facility_id, dimension_code, "
                + "source_sequence, dimension_version, policy_version, source_profile_version, "
                + "evidence_id, readiness_outcome, evidence_freshness, source_complete, quantity, "
                + "state_value, unit_value, observed_at, received_at, valid_until, "
                + "evidence_fingerprint, source_conflict, recorded_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?) "
                + "ON CONFLICT (remote_deployment_id, remote_context, remote_facility_id, dimension_code) "
                + "DO NOTHING RETURNING source_sequence",
            (resultSet, rowNumber) -> resultSet.getLong(1),
            evidence.facility().deploymentId().value(),
            evidence.facility().context(),
            evidence.facility().resourceId().value(),
            evidence.dimensionCode(),
            evidence.sourceSequence(),
            evidence.dimensionVersion(),
            evidence.policyVersion(),
            evidence.sourceProfileVersion(),
            evidence.evidenceId(),
            evidence.outcome().name(),
            evidence.freshness().name(),
            evidence.complete(),
            evidence.quantity().orElse(null),
            evidence.state().orElse(null),
            evidence.unit().orElse(null),
            Timestamp.from(evidence.observedAt()),
            Timestamp.from(evidence.receivedAt()),
            Timestamp.from(evidence.validUntil()),
            fingerprint,
            Timestamp.from(evidence.receivedAt()));
    if (!inserted.isEmpty()) {
      return RecordResult.APPLIED;
    }

    ExistingEvidence existing = lockCurrent(evidence);
    if (evidence.sourceSequence() > existing.sourceSequence()) {
      updateCurrent(evidence, fingerprint);
      return RecordResult.APPLIED;
    }
    if (evidence.sourceSequence() < existing.sourceSequence()) {
      return RecordResult.STALE_IGNORED;
    }
    if (existing.fingerprint().equals(fingerprint)) {
      return RecordResult.DUPLICATE;
    }
    jdbc.update(
        "UPDATE matching.remote_capacity_evidence_projection SET source_conflict = true, "
            + "recorded_at = ? WHERE "
            + KEY_PREDICATE
            + " AND source_sequence = ? AND evidence_fingerprint <> ?",
        flatten(
            Timestamp.from(evidence.receivedAt()),
            key(evidence),
            evidence.sourceSequence(),
            fingerprint));
    return RecordResult.CONFLICT;
  }

  @Override
  public Optional<RemoteCapacityEvidence> find(
      RemoteFacilityReference facility, String dimensionCode) {
    java.util.Objects.requireNonNull(facility, "remote Facility reference");
    requireDimension(dimensionCode);
    return jdbc
        .query(
            "SELECT * FROM matching.remote_capacity_evidence_projection WHERE " + KEY_PREDICATE,
            ROW_MAPPER,
            facility.deploymentId().value(),
            facility.context(),
            facility.resourceId().value(),
            dimensionCode)
        .stream()
        .findFirst();
  }

  private ExistingEvidence lockCurrent(RemoteCapacityEvidence evidence) {
    return jdbc.queryForObject(
        "SELECT source_sequence, evidence_fingerprint FROM "
            + "matching.remote_capacity_evidence_projection WHERE "
            + KEY_PREDICATE
            + " FOR UPDATE",
        (resultSet, rowNumber) ->
            new ExistingEvidence(
                resultSet.getLong("source_sequence"), resultSet.getString("evidence_fingerprint")),
        key(evidence));
  }

  private void updateCurrent(RemoteCapacityEvidence evidence, String fingerprint) {
    jdbc.update(
        "UPDATE matching.remote_capacity_evidence_projection SET "
            + "source_sequence = ?, dimension_version = ?, policy_version = ?, "
            + "source_profile_version = ?, evidence_id = ?, readiness_outcome = ?, "
            + "evidence_freshness = ?, source_complete = ?, quantity = ?, state_value = ?, "
            + "unit_value = ?, observed_at = ?, received_at = ?, valid_until = ?, "
            + "evidence_fingerprint = ?, source_conflict = false, recorded_at = ? WHERE "
            + KEY_PREDICATE,
        flatten(
            evidence.sourceSequence(),
            evidence.dimensionVersion(),
            evidence.policyVersion(),
            evidence.sourceProfileVersion(),
            evidence.evidenceId(),
            evidence.outcome().name(),
            evidence.freshness().name(),
            evidence.complete(),
            evidence.quantity().orElse(null),
            evidence.state().orElse(null),
            evidence.unit().orElse(null),
            Timestamp.from(evidence.observedAt()),
            Timestamp.from(evidence.receivedAt()),
            Timestamp.from(evidence.validUntil()),
            fingerprint,
            Timestamp.from(evidence.receivedAt()),
            key(evidence)));
  }

  private static Object[] key(RemoteCapacityEvidence evidence) {
    return new Object[] {
      evidence.facility().deploymentId().value(),
      evidence.facility().context(),
      evidence.facility().resourceId().value(),
      evidence.dimensionCode()
    };
  }

  private static Object[] flatten(Object... values) {
    List<Object> flattened = new ArrayList<>();
    for (Object value : values) {
      if (value instanceof Object[] nested) {
        flattened.addAll(List.of(nested));
      } else {
        flattened.add(value);
      }
    }
    return flattened.toArray();
  }

  private static RemoteCapacityEvidence mapEvidence(ResultSet row, int rowNumber)
      throws SQLException {
    return new RemoteCapacityEvidence(
        new RemoteFacilityReference(
            new DeploymentId(row.getString("remote_deployment_id")),
            row.getString("remote_context"),
            InternalId.parse(row.getString("remote_facility_id"))),
        row.getString("dimension_code"),
        row.getLong("source_sequence"),
        row.getLong("dimension_version"),
        row.getLong("policy_version"),
        row.getString("source_profile_version"),
        row.getString("evidence_id"),
        RemoteCapacityOutcome.valueOf(row.getString("readiness_outcome")),
        RemoteEvidenceFreshness.valueOf(row.getString("evidence_freshness")),
        row.getBoolean("source_complete"),
        Optional.ofNullable(row.getBigDecimal("quantity")),
        Optional.ofNullable(row.getString("state_value")),
        Optional.ofNullable(row.getString("unit_value")),
        row.getTimestamp("observed_at").toInstant(),
        row.getTimestamp("received_at").toInstant(),
        row.getTimestamp("valid_until").toInstant(),
        row.getBoolean("source_conflict"));
  }

  private static void requireDimension(String dimensionCode) {
    if (dimensionCode == null || !dimensionCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("dimension code must be a stable identifier");
    }
  }

  private record ExistingEvidence(long sourceSequence, String fingerprint) {}
}
