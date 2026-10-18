package org.ayuvahak.analytics.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.application.AnalyticsComputationOperation;
import org.ayuvahak.analytics.application.AnalyticsComputationOperation.State;
import org.ayuvahak.analytics.application.AnalyticsComputationOperationConflictException;
import org.ayuvahak.analytics.application.AnalyticsComputationOperationStore;
import org.ayuvahak.analytics.application.AnalyticsComputationRequest;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL queue for durable, idempotent owner-snapshot computation requests. */
@Repository
public class JdbcAnalyticsComputationOperationStore implements AnalyticsComputationOperationStore {

  private static final String SELECT_OPERATION =
      "SELECT request_id, request_fingerprint, indicator_key, definition_version, "
          + "period_start_local, period_end_exclusive_local, referral_source_cutoff, "
          + "organization_issuer, organization_reference_type, organization_id, requester_issuer, "
          + "requester_client_id, correlation_id, operation_state, attempt_count, available_at, "
          + "lease_until, snapshot_id, failure_class, created_at, updated_at "
          + "FROM analytics.computation_operation ";

  private final JdbcTemplate jdbc;

  public JdbcAnalyticsComputationOperationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public AnalyticsComputationOperation createOrReplay(AnalyticsComputationOperation proposed) {
    int inserted =
        jdbc.update(
            "INSERT INTO analytics.computation_operation (request_id, request_fingerprint, "
                + "indicator_key, definition_version, period_start_local, period_end_exclusive_local, "
                + "referral_source_cutoff, organization_issuer, organization_reference_type, "
                + "organization_id, requester_issuer, requester_client_id, correlation_id, "
                + "operation_state, attempt_count, available_at, lease_until, snapshot_id, failure_class, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (request_id) DO NOTHING",
            proposed.requestId().value(),
            proposed.fingerprint(),
            proposed.request().indicatorKey(),
            proposed.request().indicatorVersion(),
            proposed.request().startInclusive(),
            proposed.request().endExclusive(),
            Timestamp.from(proposed.request().referralSourceCutoff()),
            proposed.organization().issuer().value(),
            proposed.organization().resourceType(),
            UUID.fromString(proposed.organization().value()),
            proposed.requester().identity().issuer().value(),
            proposed.requester().identity().value(),
            proposed.correlationId().value(),
            proposed.state().name(),
            proposed.attemptCount(),
            Timestamp.from(proposed.availableAt()),
            proposed.leaseUntil().map(Timestamp::from).orElse(null),
            proposed.snapshotId().orElse(null),
            proposed.failureClass().orElse(null),
            Timestamp.from(proposed.createdAt()),
            Timestamp.from(proposed.updatedAt()));
    AnalyticsComputationOperation persisted = find(proposed.requestId()).orElseThrow();
    if (!persisted.fingerprint().equals(proposed.fingerprint())) {
      throw new AnalyticsComputationOperationConflictException();
    }
    return persisted;
  }

  @Override
  public Optional<AnalyticsComputationOperation> claimNext(Instant now, Instant leaseUntil) {
    if (!now.isBefore(leaseUntil)) {
      throw new IllegalArgumentException("computation lease must end after its claim time");
    }
    jdbc.update(
        "UPDATE analytics.computation_operation operation SET operation_state = 'COMPLETED', "
            + "snapshot_id = snapshot.snapshot_id, lease_until = NULL, failure_class = NULL, "
            + "updated_at = ? FROM analytics.aggregate_snapshot snapshot "
            + "WHERE operation.operation_state = 'PROCESSING' AND operation.lease_until <= ? "
            + "AND snapshot.computation_request_id = operation.request_id",
        Timestamp.from(now),
        Timestamp.from(now));
    jdbc.update(
        "UPDATE analytics.computation_operation SET operation_state = 'FAILED_PERMANENT', "
            + "lease_until = NULL, failure_class = 'LEASE_EXHAUSTED', updated_at = ? "
            + "WHERE operation_state = 'PROCESSING' AND lease_until <= ? AND attempt_count >= 8",
        Timestamp.from(now),
        Timestamp.from(now));

    List<AnalyticsComputationOperation> claimed =
        jdbc.query(
            "WITH candidate AS (SELECT request_id FROM analytics.computation_operation "
                + "WHERE ((operation_state IN ('PENDING', 'RETRY_PENDING') AND available_at <= ? "
                + "AND attempt_count < 8) OR (operation_state = 'PROCESSING' AND lease_until <= ? "
                + "AND attempt_count < 8)) ORDER BY available_at, created_at, request_id "
                + "FOR UPDATE SKIP LOCKED LIMIT 1) "
                + "UPDATE analytics.computation_operation operation SET operation_state = 'PROCESSING', "
                + "attempt_count = attempt_count + 1, lease_until = ?, updated_at = ? "
                + "FROM candidate WHERE operation.request_id = candidate.request_id RETURNING "
                + "operation.request_id, operation.request_fingerprint, operation.indicator_key, "
                + "operation.definition_version, operation.period_start_local, operation.period_end_exclusive_local, "
                + "operation.referral_source_cutoff, operation.organization_issuer, "
                + "operation.organization_reference_type, operation.organization_id, "
                + "operation.requester_issuer, operation.requester_client_id, operation.correlation_id, "
                + "operation.operation_state, operation.attempt_count, operation.available_at, "
                + "operation.lease_until, operation.snapshot_id, operation.failure_class, "
                + "operation.created_at, operation.updated_at",
            this::operation,
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(leaseUntil),
            Timestamp.from(now));
    return claimed.stream().findFirst();
  }

  @Override
  public AnalyticsComputationOperation complete(
      InternalId requestId, int expectedAttemptCount, UUID snapshotId, Instant completedAt) {
    int updated =
        jdbc.update(
            "UPDATE analytics.computation_operation SET operation_state = 'COMPLETED', "
                + "snapshot_id = ?, lease_until = NULL, failure_class = NULL, updated_at = ? "
                + "WHERE request_id = ? AND operation_state = 'PROCESSING' AND attempt_count = ?",
            snapshotId,
            Timestamp.from(completedAt),
            requestId.value(),
            expectedAttemptCount);
    AnalyticsComputationOperation operation = find(requestId).orElseThrow();
    if (updated == 0
        && (operation.state() != State.COMPLETED
            || operation.snapshotId().filter(snapshotId::equals).isEmpty())) {
      throw new AnalyticsComputationOperationConflictException();
    }
    return operation;
  }

  @Override
  public AnalyticsComputationOperation retry(
      InternalId requestId,
      int expectedAttemptCount,
      String failureClass,
      int maxAttempts,
      Instant nextAttemptAt,
      Instant updatedAt) {
    if (expectedAttemptCount < 1 || maxAttempts < 0 || maxAttempts > 8) {
      throw new IllegalArgumentException("computation retry bound is invalid");
    }
    State state = expectedAttemptCount < maxAttempts ? State.RETRY_PENDING : State.FAILED_PERMANENT;
    int updated =
        jdbc.update(
            "UPDATE analytics.computation_operation SET operation_state = ?, available_at = ?, "
                + "lease_until = NULL, failure_class = ?, updated_at = ? "
                + "WHERE request_id = ? AND operation_state = 'PROCESSING' AND attempt_count = ?",
            state.name(),
            Timestamp.from(nextAttemptAt),
            failureClass,
            Timestamp.from(updatedAt),
            requestId.value(),
            expectedAttemptCount);
    AnalyticsComputationOperation operation = find(requestId).orElseThrow();
    if (updated == 0) {
      throw new AnalyticsComputationOperationConflictException();
    }
    return operation;
  }

  @Override
  public Optional<AnalyticsComputationOperation> find(InternalId requestId) {
    return jdbc
        .query(SELECT_OPERATION + "WHERE request_id = ?", this::operation, requestId.value())
        .stream()
        .findFirst();
  }

  private AnalyticsComputationOperation operation(ResultSet result, int rowNumber)
      throws SQLException {
    InternalId requestId = new InternalId(result.getObject("request_id", UUID.class));
    AnalyticsComputationRequest request =
        new AnalyticsComputationRequest(
            requestId,
            result.getString("indicator_key"),
            result.getInt("definition_version"),
            result.getObject("period_start_local", java.time.LocalDate.class),
            result.getObject("period_end_exclusive_local", java.time.LocalDate.class),
            instant(result, "referral_source_cutoff"));
    ExternalReference organization =
        new ExternalReference(
            new IssuerId(result.getString("organization_issuer")),
            result.getString("organization_reference_type"),
            result.getObject("organization_id", UUID.class).toString());
    ActorReference requester =
        ActorReference.service(
            new IssuerId(result.getString("requester_issuer")),
            result.getString("requester_client_id"));
    return new AnalyticsComputationOperation(
        requestId,
        result.getString("request_fingerprint"),
        request,
        organization,
        requester,
        new org.ayuvahak.platform.core.CorrelationId(result.getString("correlation_id")),
        State.valueOf(result.getString("operation_state")),
        result.getInt("attempt_count"),
        instant(result, "available_at"),
        Optional.ofNullable(instantOrNull(result, "lease_until")),
        Optional.ofNullable(result.getObject("snapshot_id", UUID.class)),
        Optional.ofNullable(result.getString("failure_class")),
        instant(result, "created_at"),
        instant(result, "updated_at"));
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    return result.getTimestamp(column).toInstant();
  }

  private static Instant instantOrNull(ResultSet result, String column) throws SQLException {
    Timestamp value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
