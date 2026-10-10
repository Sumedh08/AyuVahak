package org.ayuvahak.transfer.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.platform.idempotency.configuration.CommandIdempotencyProperties;
import org.ayuvahak.transfer.application.TransferStore;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for Transfer-owned Journey, first-request, and idempotency state. */
@Repository
@Lazy
public class JdbcTransferStore implements TransferStore {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private final JdbcTemplate jdbc;
  private final CommandIdempotencyProperties idempotencyProperties;

  public JdbcTransferStore(JdbcTemplate jdbc, CommandIdempotencyProperties idempotencyProperties) {
    this.jdbc = jdbc;
    this.idempotencyProperties = idempotencyProperties;
  }

  @Override
  public Optional<ReceiptClaim> findReceipt(CommandReceiptKey key, RequestFingerprint fingerprint) {
    List<ReceiptClaim> claims =
        jdbc.query(
            "SELECT request_fingerprint, receipt_state, response_status, result_reference, result_version "
                + "FROM transfer.command_receipt WHERE scope_digest = ? AND idempotency_key_digest = ?",
            (result, row) -> receiptClaim(result, fingerprint),
            key.scopeDigest(),
            key.idempotencyKey().value());
    return claims.stream().findFirst();
  }

  @Override
  public ReceiptClaim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt) {
    int inserted =
        jdbc.update(
            "INSERT INTO transfer.command_receipt (scope_digest, idempotency_key_digest, "
                + "request_fingerprint, receipt_state, recorded_at) VALUES (?, ?, ?, 'IN_PROGRESS', ?) "
                + "ON CONFLICT DO NOTHING",
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
            + "FROM transfer.command_receipt WHERE scope_digest = ? AND idempotency_key_digest = ?",
        (result, row) -> receiptClaim(result, fingerprint),
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
  public void insertFirstRequest(TransferJourney journey, TransferRequestAttempt attempt) {
    jdbc.update(
        "INSERT INTO transfer.journey (journey_id, referral_id, origin_organization_id, "
            + "origin_facility_id, created_by_kind, created_by_issuer, created_by_identifier_type, "
            + "created_by_identifier, created_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        journey.id().value(),
        journey.referralId().value(),
        directoryId(journey.originOrganization()),
        journey.originFacility().map(JdbcTransferStore::directoryId).orElse(null),
        journey.createdBy().kind().name(),
        journey.createdBy().identity().issuer().value(),
        journey.createdBy().identity().resourceType(),
        journey.createdBy().identity().value(),
        Timestamp.from(journey.createdAt()),
        journey.version());
    jdbc.update(
        "INSERT INTO transfer.request_attempt (request_attempt_id, journey_id, attempt_number, "
            + "referral_id, referral_revision, recommendation_id, destination_facility_id, "
            + "request_status, requested_by_kind, requested_by_issuer, requested_by_identifier_type, "
            + "requested_by_identifier, requested_at, expires_at, version) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        attempt.id().value(),
        attempt.journeyId().value(),
        attempt.attemptNumber(),
        attempt.referralId().value(),
        attempt.referralRevision(),
        attempt.recommendationId().value(),
        directoryId(attempt.destinationFacility()),
        attempt.status().name(),
        attempt.requestedBy().kind().name(),
        attempt.requestedBy().identity().issuer().value(),
        attempt.requestedBy().identity().resourceType(),
        attempt.requestedBy().identity().value(),
        Timestamp.from(attempt.requestedAt()),
        Timestamp.from(attempt.expiresAt()),
        attempt.version());
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
            "UPDATE transfer.command_receipt SET receipt_state = 'COMPLETED', response_status = ?, "
                + "result_reference = ?, result_version = ?, recorded_at = ? "
                + "WHERE scope_digest = ? AND idempotency_key_digest = ? AND receipt_state = 'IN_PROGRESS'",
            responseStatus,
            resultReference.value(),
            resultVersion,
            Timestamp.from(recordedAt),
            key.scopeDigest(),
            key.idempotencyKey().value());
    if (changed != 1) {
      throw new IllegalStateException("Transfer command receipt could not be completed");
    }
  }

  @Override
  public Optional<TransferJourney> findJourney(InternalId journeyId) {
    return findJourney("journey_id", journeyId);
  }

  @Override
  public Optional<TransferJourney> findJourneyByReferral(InternalId referralId) {
    return findJourney("referral_id", referralId);
  }

  private Optional<TransferJourney> findJourney(String column, InternalId id) {
    List<TransferJourney> rows =
        jdbc.query(
            "SELECT journey_id, referral_id, origin_organization_id, origin_facility_id, "
                + "created_by_kind, created_by_issuer, created_by_identifier_type, created_by_identifier, "
                + "created_at, version FROM transfer.journey WHERE "
                + column
                + " = ?",
            JdbcTransferStore::journey,
            id.value());
    return rows.stream().findFirst();
  }

  @Override
  public Optional<TransferRequestAttempt> findFirstRequest(InternalId journeyId) {
    List<TransferRequestAttempt> rows =
        jdbc.query(
            "SELECT request_attempt_id, journey_id, attempt_number, referral_id, referral_revision, "
                + "recommendation_id, destination_facility_id, request_status, requested_by_kind, "
                + "requested_by_issuer, requested_by_identifier_type, requested_by_identifier, "
                + "requested_at, expires_at, version FROM transfer.request_attempt "
                + "WHERE journey_id = ? AND attempt_number = 1",
            JdbcTransferStore::requestAttempt,
            journeyId.value());
    return rows.stream().findFirst();
  }

  private static ReceiptClaim receiptClaim(ResultSet result, RequestFingerprint fingerprint)
      throws SQLException {
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
  }

  private static TransferJourney journey(ResultSet result, int row) throws SQLException {
    UUID facility = result.getObject("origin_facility_id", UUID.class);
    return new TransferJourney(
        internalId(result, "journey_id"),
        internalId(result, "referral_id"),
        directoryReference("Organization", result.getObject("origin_organization_id", UUID.class)),
        Optional.ofNullable(facility).map(id -> directoryReference("Facility", id)),
        actor(result, "created_by"),
        result.getTimestamp("created_at").toInstant(),
        result.getLong("version"));
  }

  private static TransferRequestAttempt requestAttempt(ResultSet result, int row)
      throws SQLException {
    return new TransferRequestAttempt(
        internalId(result, "request_attempt_id"),
        internalId(result, "journey_id"),
        result.getInt("attempt_number"),
        internalId(result, "referral_id"),
        result.getLong("referral_revision"),
        internalId(result, "recommendation_id"),
        directoryReference("Facility", result.getObject("destination_facility_id", UUID.class)),
        TransferRequestAttempt.Status.valueOf(result.getString("request_status")),
        actor(result, "requested_by"),
        result.getTimestamp("requested_at").toInstant(),
        result.getTimestamp("expires_at").toInstant(),
        result.getLong("version"));
  }

  private static InternalId internalId(ResultSet result, String column) throws SQLException {
    return new InternalId(result.getObject(column, UUID.class));
  }

  private static ActorReference actor(ResultSet result, String prefix) throws SQLException {
    return new ActorReference(
        ActorKind.valueOf(result.getString(prefix + "_kind")),
        new ExternalReference(
            new IssuerId(result.getString(prefix + "_issuer")),
            result.getString(prefix + "_identifier_type"),
            result.getString(prefix + "_identifier")));
  }

  private static UUID directoryId(ExternalReference reference) {
    if (!DIRECTORY_ISSUER.equals(reference.issuer().value())) {
      throw new IllegalArgumentException("Transfer Directory reference has an unexpected issuer");
    }
    return UUID.fromString(reference.value());
  }

  private static ExternalReference directoryReference(String type, UUID id) {
    return new ExternalReference(new IssuerId(DIRECTORY_ISSUER), type, id.toString());
  }
}
