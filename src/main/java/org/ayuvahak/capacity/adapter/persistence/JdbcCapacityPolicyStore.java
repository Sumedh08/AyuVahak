package org.ayuvahak.capacity.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.ayuvahak.capacity.application.CapacityPolicyStore;
import org.ayuvahak.capacity.application.CapacityVersionConflictException;
import org.ayuvahak.capacity.domain.CapacityDimension;
import org.ayuvahak.capacity.domain.CapacityPolicy;
import org.ayuvahak.capacity.domain.CapacitySourcePriority;
import org.ayuvahak.capacity.domain.CapacityValueKind;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.platform.idempotency.configuration.CommandIdempotencyProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for the Capacity-owned, append-only policy catalog. */
@Repository
@Lazy
public class JdbcCapacityPolicyStore implements CapacityPolicyStore {

  private final JdbcTemplate jdbc;
  private final CommandIdempotencyProperties idempotencyProperties;

  public JdbcCapacityPolicyStore(
      JdbcTemplate jdbc, CommandIdempotencyProperties idempotencyProperties) {
    this.jdbc = jdbc;
    this.idempotencyProperties = idempotencyProperties;
  }

  @Override
  public Optional<CapacityDimension> currentDimension(String code) {
    return jdbc
        .query(
            "SELECT current_version FROM capacity.capacity_dimension WHERE dimension_code = ?",
            (result, rowNumber) -> result.getLong("current_version"),
            code)
        .stream()
        .findFirst()
        .flatMap(version -> dimension(code, version));
  }

  @Override
  public Optional<CapacityDimension> lockCurrentDimension(String code) {
    return jdbc
        .query(
            "SELECT current_version FROM capacity.capacity_dimension "
                + "WHERE dimension_code = ? FOR UPDATE",
            (result, rowNumber) -> result.getLong("current_version"),
            code)
        .stream()
        .findFirst()
        .flatMap(version -> dimension(code, version));
  }

  @Override
  public Optional<CapacityDimension> findDimensionVersion(InternalId revisionId) {
    return jdbc
        .query(
            "SELECT dimension_code, version FROM capacity.capacity_dimension_version "
                + "WHERE revision_id = ?",
            (result, rowNumber) ->
                new DimensionKey(result.getString("dimension_code"), result.getLong("version")),
            revisionId.value())
        .stream()
        .findFirst()
        .flatMap(key -> dimension(key.code(), key.version()));
  }

  @Override
  public void insertDimensionVersion(CapacityDimension dimension) {
    jdbc.update(
        "INSERT INTO capacity.capacity_dimension (dimension_code, current_version, created_at) "
            + "VALUES (?, ?, ?)",
        dimension.code(),
        dimension.version(),
        Timestamp.from(dimension.changedAt()));
    insertDimensionRevision(dimension);
  }

  @Override
  public void appendDimensionVersion(CapacityDimension dimension, long expectedVersion) {
    insertDimensionRevision(dimension);
    int changed =
        jdbc.update(
            "UPDATE capacity.capacity_dimension SET current_version = ? "
                + "WHERE dimension_code = ? AND current_version = ?",
            dimension.version(),
            dimension.code(),
            expectedVersion);
    if (changed != 1) {
      throw new CapacityVersionConflictException();
    }
  }

  @Override
  public Optional<CapacityPolicy> currentPolicy(String dimensionCode) {
    return jdbc
        .query(
            "SELECT current_version FROM capacity.capacity_policy WHERE dimension_code = ?",
            (result, rowNumber) -> result.getLong("current_version"),
            dimensionCode)
        .stream()
        .findFirst()
        .flatMap(version -> policy(dimensionCode, version));
  }

  @Override
  public Optional<CapacityPolicy> lockCurrentPolicy(String dimensionCode) {
    return jdbc
        .query(
            "SELECT current_version FROM capacity.capacity_policy "
                + "WHERE dimension_code = ? FOR UPDATE",
            (result, rowNumber) -> result.getLong("current_version"),
            dimensionCode)
        .stream()
        .findFirst()
        .flatMap(version -> policy(dimensionCode, version));
  }

  @Override
  public Optional<CapacityPolicy> findPolicyVersion(InternalId revisionId) {
    return jdbc
        .query(
            "SELECT dimension_code, version FROM capacity.capacity_policy_version "
                + "WHERE revision_id = ?",
            (result, rowNumber) ->
                new PolicyKey(result.getString("dimension_code"), result.getLong("version")),
            revisionId.value())
        .stream()
        .findFirst()
        .flatMap(key -> policy(key.dimensionCode(), key.version()));
  }

  @Override
  public void insertPolicyVersion(CapacityPolicy policy) {
    jdbc.update(
        "INSERT INTO capacity.capacity_policy (dimension_code, current_version, created_at) "
            + "VALUES (?, ?, ?)",
        policy.dimensionCode(),
        policy.version(),
        Timestamp.from(policy.changedAt()));
    insertPolicyRevision(policy);
  }

  @Override
  public void appendPolicyVersion(CapacityPolicy policy, long expectedVersion) {
    insertPolicyRevision(policy);
    int changed =
        jdbc.update(
            "UPDATE capacity.capacity_policy SET current_version = ? "
                + "WHERE dimension_code = ? AND current_version = ?",
            policy.version(),
            policy.dimensionCode(),
            expectedVersion);
    if (changed != 1) {
      throw new CapacityVersionConflictException();
    }
  }

  @Override
  public ReceiptClaim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt) {
    int inserted =
        jdbc.update(
            "INSERT INTO capacity.command_receipt (scope_digest, idempotency_key_digest, "
                + "request_fingerprint, receipt_state, recorded_at) "
                + "VALUES (?, ?, ?, 'IN_PROGRESS', ?) ON CONFLICT DO NOTHING",
            key.scopeDigest(),
            key.idempotencyKey().value(),
            fingerprint.value(),
            Timestamp.from(recordedAt));
    if (inserted == 1) {
      return new ReceiptClaim(
          ReceiptState.OWNER, Optional.empty(), Optional.empty(), Optional.empty());
    }
    return jdbc.queryForObject(
        "SELECT request_fingerprint, receipt_state, response_status, result_reference, result_version "
            + "FROM capacity.command_receipt WHERE scope_digest = ? AND idempotency_key_digest = ?",
        (result, rowNumber) -> {
          if (!fingerprint.value().equals(result.getString("request_fingerprint"))) {
            return new ReceiptClaim(
                ReceiptState.KEY_REUSED, Optional.empty(), Optional.empty(), Optional.empty());
          }
          if ("IN_PROGRESS".equals(result.getString("receipt_state"))) {
            return new ReceiptClaim(
                ReceiptState.IN_PROGRESS, Optional.empty(), Optional.empty(), Optional.empty());
          }
          return new ReceiptClaim(
              ReceiptState.REPLAY,
              Optional.of(result.getInt("response_status")),
              Optional.of(new InternalId(result.getObject("result_reference", UUID.class))),
              Optional.of(result.getLong("result_version")));
        },
        key.scopeDigest(),
        key.idempotencyKey().value());
  }

  @Override
  public void configureBoundedReceiptWait() {
    jdbc.execute(
        "SET LOCAL lock_timeout = '"
            + idempotencyProperties.getReceiptLockWait().toMillis()
            + "ms'");
  }

  @Override
  public void completeReceipt(
      CommandReceiptKey key,
      int responseStatus,
      InternalId resultReference,
      long resultVersion,
      Instant recordedAt) {
    int changed =
        jdbc.update(
            "UPDATE capacity.command_receipt SET receipt_state = 'COMPLETED', response_status = ?, "
                + "result_reference = ?, result_version = ?, recorded_at = ? "
                + "WHERE scope_digest = ? AND idempotency_key_digest = ? "
                + "AND receipt_state = 'IN_PROGRESS'",
            responseStatus,
            resultReference.value(),
            resultVersion,
            Timestamp.from(recordedAt),
            key.scopeDigest(),
            key.idempotencyKey().value());
    if (changed != 1) {
      throw new IllegalStateException("Capacity command receipt could not be completed");
    }
  }

  private Optional<CapacityDimension> dimension(String code, long version) {
    List<CapacityDimension> versions =
        jdbc.query(
            "SELECT revision_id, dimension_code, version, display_name, meaning, value_kind, "
                + "unit_code, minimum_value, maximum_value, used_for_readiness, reservable, "
                + "reason_code, changed_by_kind, changed_by_issuer, changed_by_identifier_type, "
                + "changed_by_identifier, changed_at FROM capacity.capacity_dimension_version "
                + "WHERE dimension_code = ? AND version = ?",
            dimensionRowMapper(),
            code,
            version);
    return versions.stream().findFirst();
  }

  private RowMapper<CapacityDimension> dimensionRowMapper() {
    return (result, rowNumber) -> {
      String code = result.getString("dimension_code");
      long version = result.getLong("version");
      Set<String> states =
          new LinkedHashSet<>(
              jdbc.query(
                  "SELECT state_code FROM capacity.capacity_dimension_state "
                      + "WHERE dimension_code = ? AND dimension_version = ? ORDER BY state_code",
                  (state, index) -> state.getString("state_code"),
                  code,
                  version));
      return new CapacityDimension(
          new InternalId(result.getObject("revision_id", UUID.class)),
          code,
          version,
          result.getString("display_name"),
          result.getString("meaning"),
          CapacityValueKind.valueOf(result.getString("value_kind")),
          result.getString("unit_code"),
          result.getBigDecimal("minimum_value"),
          result.getBigDecimal("maximum_value"),
          states,
          result.getBoolean("used_for_readiness"),
          result.getBoolean("reservable"),
          result.getString("reason_code"),
          actor(
              result.getString("changed_by_kind"),
              result.getString("changed_by_issuer"),
              result.getString("changed_by_identifier_type"),
              result.getString("changed_by_identifier")),
          result.getTimestamp("changed_at").toInstant());
    };
  }

  private Optional<CapacityPolicy> policy(String dimensionCode, long version) {
    return jdbc
        .query(
            "SELECT revision_id, dimension_code, version, dimension_version, "
                + "maximum_observation_age_seconds, default_validity_seconds, "
                + "future_skew_tolerance_seconds, minimum_passing_quantity, reason_code, "
                + "changed_by_kind, changed_by_issuer, changed_by_identifier_type, "
                + "changed_by_identifier, changed_at FROM capacity.capacity_policy_version "
                + "WHERE dimension_code = ? AND version = ?",
            (result, rowNumber) -> {
              List<CapacitySourcePriority> sources =
                  jdbc.query(
                      "SELECT source_code, priority, fallback_when_higher_priority_absent, "
                          + "fallback_when_higher_priority_stale FROM capacity.capacity_policy_source "
                          + "WHERE dimension_code = ? AND policy_version = ? "
                          + "ORDER BY priority, source_code",
                      (source, index) ->
                          new CapacitySourcePriority(
                              source.getString("source_code"),
                              source.getInt("priority"),
                              source.getBoolean("fallback_when_higher_priority_absent"),
                              source.getBoolean("fallback_when_higher_priority_stale")),
                      dimensionCode,
                      version);
              Set<String> passingStates =
                  new LinkedHashSet<>(
                      jdbc.query(
                          "SELECT state_code FROM capacity.capacity_policy_readiness_state "
                              + "WHERE dimension_code = ? AND policy_version = ? ORDER BY state_code",
                          (state, index) -> state.getString("state_code"),
                          dimensionCode,
                          version));
              long defaultValidity = result.getLong("default_validity_seconds");
              Long nullableDefaultValidity = result.wasNull() ? null : defaultValidity;
              return new CapacityPolicy(
                  new InternalId(result.getObject("revision_id", UUID.class)),
                  result.getString("dimension_code"),
                  result.getLong("version"),
                  result.getLong("dimension_version"),
                  sources,
                  result.getLong("maximum_observation_age_seconds"),
                  nullableDefaultValidity,
                  result.getLong("future_skew_tolerance_seconds"),
                  result.getBigDecimal("minimum_passing_quantity"),
                  passingStates,
                  result.getString("reason_code"),
                  actor(
                      result.getString("changed_by_kind"),
                      result.getString("changed_by_issuer"),
                      result.getString("changed_by_identifier_type"),
                      result.getString("changed_by_identifier")),
                  result.getTimestamp("changed_at").toInstant());
            },
            dimensionCode,
            version)
        .stream()
        .findFirst();
  }

  private void insertDimensionRevision(CapacityDimension dimension) {
    jdbc.update(
        "INSERT INTO capacity.capacity_dimension_version (revision_id, dimension_code, version, "
            + "display_name, meaning, value_kind, unit_code, minimum_value, maximum_value, "
            + "used_for_readiness, reservable, reason_code, changed_by_kind, changed_by_issuer, "
            + "changed_by_identifier_type, changed_by_identifier, changed_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        dimension.revisionId().value(),
        dimension.code(),
        dimension.version(),
        dimension.displayName(),
        dimension.meaning(),
        dimension.valueKind().name(),
        dimension.unit(),
        dimension.minimumValue(),
        dimension.maximumValue(),
        dimension.usedForReadiness(),
        dimension.reservable(),
        dimension.reasonCode(),
        dimension.changedBy().kind().name(),
        dimension.changedBy().identity().issuer().value(),
        dimension.changedBy().identity().resourceType(),
        dimension.changedBy().identity().value(),
        Timestamp.from(dimension.changedAt()));
    for (String state : dimension.allowedStates().stream().sorted().toList()) {
      jdbc.update(
          "INSERT INTO capacity.capacity_dimension_state "
              + "(dimension_code, dimension_version, state_code) VALUES (?, ?, ?)",
          dimension.code(),
          dimension.version(),
          state);
    }
  }

  private void insertPolicyRevision(CapacityPolicy policy) {
    jdbc.update(
        "INSERT INTO capacity.capacity_policy_version (revision_id, dimension_code, version, "
            + "dimension_version, maximum_observation_age_seconds, default_validity_seconds, "
            + "future_skew_tolerance_seconds, minimum_passing_quantity, reason_code, "
            + "changed_by_kind, changed_by_issuer, changed_by_identifier_type, "
            + "changed_by_identifier, changed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        policy.revisionId().value(),
        policy.dimensionCode(),
        policy.version(),
        policy.dimensionVersion(),
        policy.maximumObservationAgeSeconds(),
        policy.defaultValiditySeconds(),
        policy.futureSkewToleranceSeconds(),
        policy.minimumPassingQuantity(),
        policy.reasonCode(),
        policy.changedBy().kind().name(),
        policy.changedBy().identity().issuer().value(),
        policy.changedBy().identity().resourceType(),
        policy.changedBy().identity().value(),
        Timestamp.from(policy.changedAt()));
    for (CapacitySourcePriority source : policy.sources()) {
      jdbc.update(
          "INSERT INTO capacity.capacity_policy_source (dimension_code, policy_version, "
              + "source_code, priority, fallback_when_higher_priority_absent, "
              + "fallback_when_higher_priority_stale) VALUES (?, ?, ?, ?, ?, ?)",
          policy.dimensionCode(),
          policy.version(),
          source.sourceCode(),
          source.priority(),
          source.fallbackWhenHigherPriorityAbsent(),
          source.fallbackWhenHigherPriorityStale());
    }
    for (String state : policy.passingStates().stream().sorted().toList()) {
      jdbc.update(
          "INSERT INTO capacity.capacity_policy_readiness_state "
              + "(dimension_code, policy_version, state_code) VALUES (?, ?, ?)",
          policy.dimensionCode(),
          policy.version(),
          state);
    }
  }

  private static ActorReference actor(
      String kind, String issuer, String identifierType, String identifier) {
    return new ActorReference(
        ActorKind.valueOf(kind),
        new ExternalReference(new IssuerId(issuer), identifierType, identifier));
  }

  private record DimensionKey(String code, long version) {}

  private record PolicyKey(String dimensionCode, long version) {}
}
