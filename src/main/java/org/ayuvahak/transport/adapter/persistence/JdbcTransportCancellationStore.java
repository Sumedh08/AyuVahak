package org.ayuvahak.transport.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transport.application.TransportCancellationStore;
import org.ayuvahak.transport.domain.TransportCancellationOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for durable cancellation operations and owner-local receipts. */
@Repository
public class JdbcTransportCancellationStore implements TransportCancellationStore {

  private final JdbcTemplate jdbc;

  public JdbcTransportCancellationStore(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "JDBC template");
  }

  @Override
  public ReceiptClaim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt) {
    int inserted =
        jdbc.update(
            "INSERT INTO transport.cancellation_command_receipt (scope_digest, idempotency_key_digest, "
                + "request_fingerprint, receipt_state, recorded_at) VALUES (?, ?, ?, 'IN_PROGRESS', ?) "
                + "ON CONFLICT DO NOTHING",
            key.scopeDigest(),
            key.idempotencyKey().value(),
            fingerprint.value(),
            Timestamp.from(recordedAt));
    if (inserted == 1) {
      return new ReceiptClaim(ClaimState.OWNER, Optional.empty());
    }
    return jdbc.queryForObject(
        "SELECT request_fingerprint, receipt_state, cancellation_operation_id "
            + "FROM transport.cancellation_command_receipt WHERE scope_digest = ? "
            + "AND idempotency_key_digest = ?",
        (result, row) -> {
          if (!fingerprint.value().equals(result.getString("request_fingerprint").trim())) {
            return new ReceiptClaim(ClaimState.KEY_REUSED, Optional.empty());
          }
          if ("IN_PROGRESS".equals(result.getString("receipt_state"))) {
            return new ReceiptClaim(ClaimState.IN_PROGRESS, Optional.empty());
          }
          UUID id = result.getObject("cancellation_operation_id", UUID.class);
          return new ReceiptClaim(ClaimState.REPLAY, Optional.of(InternalId.parse(id.toString())));
        },
        key.scopeDigest(),
        key.idempotencyKey().value());
  }

  @Override
  public void completeReceipt(CommandReceiptKey key, InternalId operationId, Instant recordedAt) {
    int updated =
        jdbc.update(
            "UPDATE transport.cancellation_command_receipt SET receipt_state = 'COMPLETED', "
                + "cancellation_operation_id = ?, recorded_at = ? WHERE scope_digest = ? "
                + "AND idempotency_key_digest = ? AND receipt_state = 'IN_PROGRESS'",
            operationId.value(),
            Timestamp.from(recordedAt),
            key.scopeDigest(),
            key.idempotencyKey().value());
    if (updated != 1) {
      throw new IllegalStateException("Transport cancellation receipt could not be completed");
    }
  }

  @Override
  public boolean insert(
      TransportCancellationOperation operation,
      String actorKind,
      String actorIssuer,
      String actorIdentifierType,
      String actorIdentifier,
      String organizationId,
      String facilityId,
      String correlationId,
      String authorityCode) {
    return jdbc.update(
            "INSERT INTO transport.cancellation_operation (cancellation_operation_id, "
                + "transport_request_id, reason_code, operation_state, outcome, initiated_at, "
                + "initiated_by_kind, initiated_by_issuer, initiated_by_identifier_type, "
                + "initiated_by_identifier, authority_code, organization_id, facility_id, "
                + "correlation_id, resolved_at, source_namespace, source_observation_id, "
                + "resolution_fingerprint, source_occurred_at, resolution_correlation_id, "
                + "resolution_event_id, version) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            operation.id().value(),
            operation.transportRequestId().value(),
            operation.reasonCode(),
            operation.state().name(),
            operation.outcome().map(Enum::name).orElse(null),
            Timestamp.from(operation.initiatedAt()),
            actorKind,
            actorIssuer,
            actorIdentifierType,
            actorIdentifier,
            authorityCode,
            UUID.fromString(organizationId),
            UUID.fromString(facilityId),
            correlationId,
            operation.resolvedAt().map(Timestamp::from).orElse(null),
            operation.sourceNamespace().orElse(null),
            operation.sourceObservationId().orElse(null),
            operation.resolutionFingerprint().orElse(null),
            operation.sourceOccurredAt().map(Timestamp::from).orElse(null),
            operation.resolutionCorrelationId().orElse(null),
            operation.resolutionEventId().map(InternalId::value).orElse(null),
            operation.version())
        == 1;
  }

  @Override
  public Optional<TransportCancellationOperation> find(InternalId operationId) {
    return find(operationId, false);
  }

  @Override
  public Optional<TransportCancellationOperation> lock(InternalId operationId) {
    return find(operationId, true);
  }

  @Override
  public Optional<TransportCancellationOperation> findUnresolvedForRequest(InternalId requestId) {
    List<TransportCancellationOperation> found =
        jdbc.query(
            "SELECT * FROM transport.cancellation_operation WHERE transport_request_id = ? "
                + "AND operation_state IN ('PENDING', 'OUTCOME_UNKNOWN', 'RECONCILIATION_REQUIRED') "
                + "ORDER BY initiated_at DESC LIMIT 1",
            JdbcTransportCancellationStore::map,
            requestId.value());
    return found.stream().findFirst();
  }

  @Override
  public Optional<TransportCancellationOperation> findLatestForRequest(InternalId requestId) {
    List<TransportCancellationOperation> found =
        jdbc.query(
            "SELECT * FROM transport.cancellation_operation WHERE transport_request_id = ? "
                + "ORDER BY initiated_at DESC, cancellation_operation_id DESC LIMIT 1",
            JdbcTransportCancellationStore::map,
            requestId.value());
    return found.stream().findFirst();
  }

  @Override
  public boolean markState(
      InternalId operationId, long expectedVersion, TransportCancellationOperation.State state) {
    if (state != TransportCancellationOperation.State.OUTCOME_UNKNOWN
        && state != TransportCancellationOperation.State.RECONCILIATION_REQUIRED) {
      throw new IllegalArgumentException("uncertain cancellation state is required");
    }
    return jdbc.update(
            "UPDATE transport.cancellation_operation SET operation_state = ?, version = version + 1 "
                + "WHERE cancellation_operation_id = ? AND version = ? AND ("
                + "(operation_state = 'PENDING' AND ? IN ('OUTCOME_UNKNOWN', 'RECONCILIATION_REQUIRED')) "
                + "OR (operation_state = 'OUTCOME_UNKNOWN' AND ? = 'RECONCILIATION_REQUIRED'))",
            state.name(),
            operationId.value(),
            expectedVersion,
            state.name(),
            state.name())
        == 1;
  }

  @Override
  public boolean resolve(
      InternalId operationId,
      long expectedVersion,
      TransportCancellationOperation.Outcome outcome,
      Instant resolvedAt,
      String sourceNamespace,
      String sourceObservationId,
      String fingerprint,
      Instant sourceOccurredAt,
      String resolutionCorrelationId,
      InternalId resolutionEventId) {
    return jdbc.update(
            "UPDATE transport.cancellation_operation SET operation_state = 'RESOLVED', outcome = ?, "
                + "resolved_at = ?, source_namespace = ?, source_observation_id = ?, "
                + "resolution_fingerprint = ?, source_occurred_at = ?, "
                + "resolution_correlation_id = ?, resolution_event_id = ?, version = version + 1 "
                + "WHERE cancellation_operation_id = ? "
                + "AND operation_state <> 'RESOLVED' AND version = ?",
            outcome.name(),
            Timestamp.from(resolvedAt),
            sourceNamespace,
            sourceObservationId,
            fingerprint,
            Timestamp.from(sourceOccurredAt),
            resolutionCorrelationId,
            resolutionEventId.value(),
            operationId.value(),
            expectedVersion)
        == 1;
  }

  private Optional<TransportCancellationOperation> find(InternalId operationId, boolean lock) {
    List<TransportCancellationOperation> found =
        jdbc.query(
            "SELECT * FROM transport.cancellation_operation WHERE cancellation_operation_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            JdbcTransportCancellationStore::map,
            operationId.value());
    return found.stream().findFirst();
  }

  private static TransportCancellationOperation map(java.sql.ResultSet row, int rowNumber)
      throws java.sql.SQLException {
    Timestamp resolved = row.getTimestamp("resolved_at");
    String outcome = row.getString("outcome");
    return new TransportCancellationOperation(
        InternalId.parse(row.getObject("cancellation_operation_id", UUID.class).toString()),
        InternalId.parse(row.getObject("transport_request_id", UUID.class).toString()),
        row.getString("reason_code"),
        TransportCancellationOperation.State.valueOf(row.getString("operation_state")),
        Optional.ofNullable(outcome).map(TransportCancellationOperation.Outcome::valueOf),
        row.getTimestamp("initiated_at").toInstant(),
        Optional.ofNullable(resolved).map(Timestamp::toInstant),
        Optional.ofNullable(row.getString("source_namespace")),
        Optional.ofNullable(row.getString("source_observation_id")),
        Optional.ofNullable(row.getString("resolution_fingerprint")).map(String::trim),
        Optional.ofNullable(row.getTimestamp("source_occurred_at")).map(Timestamp::toInstant),
        Optional.ofNullable(row.getString("resolution_correlation_id")),
        Optional.ofNullable(row.getObject("resolution_event_id", UUID.class))
            .map(id -> InternalId.parse(id.toString())),
        row.getLong("version"));
  }
}
