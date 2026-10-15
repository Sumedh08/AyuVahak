package org.ayuvahak.transport.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transport.application.TransportRequestStore;
import org.ayuvahak.transport.domain.TransportRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for Transport-owned request history and receipts. */
@Repository
public class JdbcTransportRequestStore implements TransportRequestStore {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private final JdbcTemplate jdbc;

  public JdbcTransportRequestStore(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "JDBC template");
  }

  @Override
  public void configureBoundedReceiptWait() {
    jdbc.execute("SET LOCAL lock_timeout = '5000ms'");
  }

  @Override
  public Claim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt) {
    int inserted =
        jdbc.update(
            "INSERT INTO transport.command_receipt (scope_digest, idempotency_key_digest, "
                + "request_fingerprint, receipt_state, recorded_at) VALUES (?, ?, ?, 'IN_PROGRESS', ?) "
                + "ON CONFLICT DO NOTHING",
            key.scopeDigest(),
            key.idempotencyKey().value(),
            fingerprint.value(),
            Timestamp.from(recordedAt));
    if (inserted == 1) {
      return new Claim(ClaimState.OWNER, Optional.empty());
    }
    return jdbc.queryForObject(
        "SELECT request_fingerprint, receipt_state, result_reference FROM transport.command_receipt "
            + "WHERE scope_digest = ? AND idempotency_key_digest = ?",
        (result, row) -> {
          if (!fingerprint.value().equals(result.getString("request_fingerprint").trim())) {
            return new Claim(ClaimState.KEY_REUSED, Optional.empty());
          }
          if ("IN_PROGRESS".equals(result.getString("receipt_state"))) {
            return new Claim(ClaimState.IN_PROGRESS, Optional.empty());
          }
          UUID reference = result.getObject("result_reference", UUID.class);
          return new Claim(ClaimState.REPLAY, Optional.of(InternalId.parse(reference.toString())));
        },
        key.scopeDigest(),
        key.idempotencyKey().value());
  }

  @Override
  public void completeReceipt(CommandReceiptKey key, InternalId requestId, Instant recordedAt) {
    int updated =
        jdbc.update(
            "UPDATE transport.command_receipt SET receipt_state = 'COMPLETED', response_status = 201, "
                + "result_reference = ?, result_version = 1, recorded_at = ? "
                + "WHERE scope_digest = ? AND idempotency_key_digest = ? AND receipt_state = 'IN_PROGRESS'",
            requestId.value(),
            Timestamp.from(recordedAt),
            key.scopeDigest(),
            key.idempotencyKey().value());
    if (updated != 1) {
      throw new IllegalStateException("Transport command receipt could not be completed");
    }
  }

  @Override
  public boolean insert(
      TransportRequest request,
      String initiatingActorIssuer,
      String initiatingActorType,
      String recordingActorIssuer,
      String recordingActorType) {
    return jdbc.update(
            "INSERT INTO transport.transport_request (transport_request_id, transfer_id, "
                + "origin_facility_id, destination_facility_id, initiated_by_kind, initiated_by_issuer, "
                + "initiated_by_identifier_type, initiated_by_identifier, recorded_by_kind, recorded_by_issuer, "
                + "recorded_by_identifier_type, recorded_by_identifier, request_status, requested_at, "
                + "correlation_id, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            request.id().value(),
            request.transferId().value(),
            InternalId.parse(request.originFacility().value()).value(),
            InternalId.parse(request.destinationFacility().value()).value(),
            request.initiatedBy().kind().name(),
            initiatingActorIssuer,
            initiatingActorType,
            request.initiatedBy().identity().value(),
            request.recordedBy().kind().name(),
            recordingActorIssuer,
            recordingActorType,
            request.recordedBy().identity().value(),
            request.status().name(),
            Timestamp.from(request.requestedAt()),
            request.correlationId().value(),
            request.version())
        == 1;
  }

  @Override
  public Optional<TransportRequest> find(InternalId requestId) {
    return find(requestId, false);
  }

  @Override
  public Optional<TransportRequest> lock(InternalId requestId) {
    return find(requestId, true);
  }

  private Optional<TransportRequest> find(InternalId requestId, boolean lock) {
    List<TransportRequest> found =
        jdbc.query(
            "SELECT * FROM transport.transport_request WHERE transport_request_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            (result, row) -> {
              ActorReference initiated =
                  actor(
                      result.getString("initiated_by_kind"),
                      result.getString("initiated_by_issuer"),
                      result.getString("initiated_by_identifier_type"),
                      result.getString("initiated_by_identifier"));
              ActorReference recorded =
                  actor(
                      result.getString("recorded_by_kind"),
                      result.getString("recorded_by_issuer"),
                      result.getString("recorded_by_identifier_type"),
                      result.getString("recorded_by_identifier"));
              return new TransportRequest(
                  id(result, "transport_request_id"),
                  id(result, "transfer_id"),
                  facility(result.getObject("origin_facility_id", UUID.class)),
                  facility(result.getObject("destination_facility_id", UUID.class)),
                  initiated,
                  recorded,
                  TransportRequest.Status.valueOf(result.getString("request_status")),
                  result.getTimestamp("requested_at").toInstant(),
                  new CorrelationId(result.getString("correlation_id")),
                  result.getLong("version"));
            },
            requestId.value());
    return found.stream().findFirst();
  }

  private static ActorReference actor(String kind, String issuer, String type, String identifier) {
    ActorKind actorKind = ActorKind.valueOf(kind);
    return new ActorReference(
        actorKind, new ExternalReference(new IssuerId(issuer), type, identifier));
  }

  private static ExternalReference facility(UUID value) {
    return new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", value.toString());
  }

  private static InternalId id(java.sql.ResultSet result, String column)
      throws java.sql.SQLException {
    return InternalId.parse(result.getObject(column, UUID.class).toString());
  }
}
