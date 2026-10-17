package org.ayuvahak.transfer.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.application.FederatedOriginReceivingAttemptStore;
import org.ayuvahak.transfer.application.TransferStore;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** PostgreSQL persistence for origin-owned, ordered federated destination attempts. */
@Repository
public class JdbcFederatedOriginReceivingAttemptStore
    implements FederatedOriginReceivingAttemptStore {

  private static final RowMapper<FederatedOriginReceivingAttempt> ROW_MAPPER =
      JdbcFederatedOriginReceivingAttemptStore::map;

  private final JdbcTemplate jdbc;

  public JdbcFederatedOriginReceivingAttemptStore(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "JDBC template");
  }

  @Override
  public TransferStore.ReceiptClaim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt) {
    int inserted =
        jdbc.update(
            "INSERT INTO transfer.federated_origin_receiving_receipt (scope_digest, "
                + "idempotency_key_digest, request_fingerprint, receipt_state, recorded_at) "
                + "VALUES (?, ?, ?, 'IN_PROGRESS', ?) ON CONFLICT DO NOTHING",
            key.scopeDigest(),
            key.idempotencyKey().value(),
            fingerprint.value(),
            timestamp(recordedAt));
    if (inserted == 1) {
      return new TransferStore.ReceiptClaim(
          TransferStore.ReceiptState.OWNER, Optional.empty(), Optional.empty(), Optional.empty());
    }
    return jdbc.queryForObject(
        "SELECT request_fingerprint, receipt_state, response_status, result_reference, "
            + "result_version FROM transfer.federated_origin_receiving_receipt "
            + "WHERE scope_digest = ? AND idempotency_key_digest = ?",
        (result, row) -> receiptClaim(result, fingerprint),
        key.scopeDigest(),
        key.idempotencyKey().value());
  }

  @Override
  public void completeReceipt(
      CommandReceiptKey key,
      int responseStatus,
      InternalId attemptId,
      long attemptNumber,
      Instant recordedAt) {
    int updated =
        jdbc.update(
            "UPDATE transfer.federated_origin_receiving_receipt SET receipt_state = 'COMPLETED', "
                + "response_status = ?, result_reference = ?, result_version = ?, recorded_at = ? "
                + "WHERE scope_digest = ? AND idempotency_key_digest = ? AND receipt_state = 'IN_PROGRESS'",
            responseStatus,
            attemptId.value(),
            attemptNumber,
            timestamp(recordedAt),
            key.scopeDigest(),
            key.idempotencyKey().value());
    if (updated != 1) {
      throw new IllegalStateException("federated origin receiving receipt could not be completed");
    }
  }

  @Override
  public void insert(FederatedOriginReceivingAttempt attempt) {
    jdbc.update(
        "INSERT INTO transfer.federated_origin_receiving_attempt (origin_attempt_id, journey_id, "
            + "attempt_number, origin_deployment_id, peer_deployment_id, partner_key, "
            + "partner_configuration_version, operation_id, request_fingerprint, command_fingerprint, "
            + "request_body, operation_state, remote_outcome, remote_receiving_request_id, "
            + "remote_capacity_reservation_id, remote_version, attempt_version, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        attempt.id().value(),
        attempt.journeyId().value(),
        attempt.attemptNumber(),
        attempt.originDeployment().value(),
        attempt.peerDeployment().value(),
        attempt.partnerKey().value(),
        attempt.configurationVersion(),
        attempt.operationId().value(),
        attempt.requestFingerprint(),
        attempt.commandFingerprint(),
        attempt.requestBody(),
        attempt.state().name(),
        attempt.remoteOutcome().map(Enum::name).orElse(null),
        attempt.remoteReceivingRequestId().map(InternalId::value).orElse(null),
        attempt.remoteCapacityReservationId().map(InternalId::value).orElse(null),
        attempt.remoteVersion(),
        attempt.version(),
        timestamp(attempt.createdAt()),
        timestamp(attempt.updatedAt()));
  }

  @Override
  public Optional<FederatedOriginReceivingAttempt> findById(InternalId attemptId) {
    return jdbc
        .query(
            "SELECT * FROM transfer.federated_origin_receiving_attempt WHERE origin_attempt_id = ?",
            ROW_MAPPER,
            attemptId.value())
        .stream()
        .findFirst();
  }

  @Override
  public Optional<FederatedOriginReceivingAttempt> lockByOperationId(InternalId operationId) {
    return jdbc
        .query(
            "SELECT * FROM transfer.federated_origin_receiving_attempt WHERE operation_id = ? FOR UPDATE",
            ROW_MAPPER,
            operationId.value())
        .stream()
        .findFirst();
  }

  @Override
  public int latestAttemptNumber(InternalId journeyId) {
    Integer number =
        jdbc.queryForObject(
            "SELECT GREATEST(COALESCE((SELECT MAX(attempt_number) FROM transfer.request_attempt "
                + "WHERE journey_id = ?), 0), COALESCE((SELECT MAX(attempt_number) FROM "
                + "transfer.federated_origin_receiving_attempt WHERE journey_id = ?), 0))",
            Integer.class,
            journeyId.value(),
            journeyId.value());
    return number == null ? 0 : number;
  }

  @Override
  public boolean update(FederatedOriginReceivingAttempt attempt, long expectedVersion) {
    return jdbc.update(
            "UPDATE transfer.federated_origin_receiving_attempt SET operation_state = ?, "
                + "remote_outcome = ?, remote_receiving_request_id = ?, "
                + "remote_capacity_reservation_id = ?, remote_version = ?, attempt_version = ?, "
                + "updated_at = ? WHERE operation_id = ? AND attempt_version = ?",
            attempt.state().name(),
            attempt.remoteOutcome().map(Enum::name).orElse(null),
            attempt.remoteReceivingRequestId().map(InternalId::value).orElse(null),
            attempt.remoteCapacityReservationId().map(InternalId::value).orElse(null),
            attempt.remoteVersion(),
            attempt.version(),
            timestamp(attempt.updatedAt()),
            attempt.operationId().value(),
            expectedVersion)
        == 1;
  }

  private static FederatedOriginReceivingAttempt map(ResultSet row, int index) throws SQLException {
    String remoteOutcome = row.getString("remote_outcome");
    return new FederatedOriginReceivingAttempt(
        id(row, "origin_attempt_id"),
        id(row, "journey_id"),
        row.getInt("attempt_number"),
        new DeploymentId(row.getString("origin_deployment_id")),
        new DeploymentId(row.getString("peer_deployment_id")),
        new PartnerKey(row.getString("partner_key")),
        row.getInt("partner_configuration_version"),
        id(row, "operation_id"),
        row.getString("request_fingerprint"),
        row.getString("command_fingerprint"),
        row.getBytes("request_body"),
        FederatedOriginReceivingAttempt.State.valueOf(row.getString("operation_state")),
        Optional.ofNullable(remoteOutcome)
            .map(FederatedOriginReceivingAttempt.RemoteOutcome::valueOf),
        optionalId(row, "remote_receiving_request_id"),
        optionalId(row, "remote_capacity_reservation_id"),
        row.getLong("remote_version"),
        row.getLong("attempt_version"),
        row.getTimestamp("created_at").toInstant(),
        row.getTimestamp("updated_at").toInstant());
  }

  private static InternalId id(ResultSet row, String column) throws SQLException {
    return new InternalId(row.getObject(column, UUID.class));
  }

  private static Optional<InternalId> optionalId(ResultSet row, String column) throws SQLException {
    UUID value = row.getObject(column, UUID.class);
    return value == null ? Optional.empty() : Optional.of(new InternalId(value));
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }

  private static TransferStore.ReceiptClaim receiptClaim(
      ResultSet row, RequestFingerprint expectedFingerprint) throws SQLException {
    if (!expectedFingerprint.value().equals(row.getString("request_fingerprint"))) {
      return new TransferStore.ReceiptClaim(
          TransferStore.ReceiptState.KEY_REUSED,
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }
    if ("IN_PROGRESS".equals(row.getString("receipt_state"))) {
      return new TransferStore.ReceiptClaim(
          TransferStore.ReceiptState.IN_PROGRESS,
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }
    UUID resultReference = row.getObject("result_reference", UUID.class);
    long resultVersion = row.getLong("result_version");
    return new TransferStore.ReceiptClaim(
        TransferStore.ReceiptState.REPLAY,
        Optional.of(row.getInt("response_status")),
        Optional.of(new InternalId(resultReference)),
        Optional.of(resultVersion));
  }
}
