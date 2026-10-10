package org.ayuvahak.matching.adapter.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.matching.application.MatchingStore;
import org.ayuvahak.matching.domain.RecommendationSnapshot;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.platform.idempotency.configuration.CommandIdempotencyProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** JDBC persistence for Matching-owned immutable recommendation evidence and retries. */
@Repository
@Lazy
public class JdbcMatchingStore implements MatchingStore {

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final CommandIdempotencyProperties idempotencyProperties;

  public JdbcMatchingStore(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      CommandIdempotencyProperties idempotencyProperties) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.idempotencyProperties = idempotencyProperties;
  }

  @Override
  public void insertSnapshot(RecommendationSnapshot snapshot) {
    jdbc.update(
        "INSERT INTO matching.recommendation_snapshot (recommendation_id, referral_id, "
            + "referral_revision, origin_organization_id, origin_facility_id, "
            + "matching_policy_code, matching_policy_version, mapping_policy_code, "
            + "mapping_policy_version, evaluation_outcome, generated_at, applicable_until, "
            + "snapshot_document) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
        snapshot.id().value(),
        snapshot.referralId().value(),
        snapshot.referralRevision(),
        UUID.fromString(snapshot.attribution().organization().value()),
        snapshot
            .attribution()
            .facility()
            .map(reference -> UUID.fromString(reference.value()))
            .orElse(null),
        snapshot.matchingPolicy().reference().value(),
        snapshot.matchingPolicy().version(),
        snapshot.mappingPolicy().reference().value(),
        snapshot.mappingPolicy().version(),
        snapshot.outcome().name(),
        Timestamp.from(snapshot.generatedAt()),
        Timestamp.from(snapshot.applicableUntil()),
        serialize(snapshot));
  }

  @Override
  public Optional<RecommendationSnapshot> findSnapshot(InternalId recommendationId) {
    return jdbc
        .query(
            "SELECT snapshot_document::text FROM matching.recommendation_snapshot "
                + "WHERE recommendation_id = ?",
            (result, rowNumber) -> deserialize(result.getString(1)),
            recommendationId.value())
        .stream()
        .findFirst();
  }

  @Override
  public List<RecommendationSnapshot> findHistory(
      InternalId referralId,
      InternalId originOrganizationId,
      Optional<InternalId> originFacilityId,
      int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("recommendation history limit must be 1 through 100");
    }
    return jdbc.query(
        "SELECT snapshot_document::text FROM matching.recommendation_snapshot "
            + "WHERE referral_id = ? AND origin_organization_id = ? "
            + "AND (CAST(? AS uuid) IS NULL OR origin_facility_id = ?) "
            + "ORDER BY generated_at DESC, recommendation_id DESC LIMIT ?",
        (result, rowNumber) -> deserialize(result.getString(1)),
        referralId.value(),
        originOrganizationId.value(),
        originFacilityId.map(InternalId::value).orElse(null),
        originFacilityId.map(InternalId::value).orElse(null),
        limit);
  }

  @Override
  public void configureBoundedReceiptWait() {
    jdbc.execute(
        "SET LOCAL lock_timeout = '"
            + idempotencyProperties.getReceiptLockWait().toMillis()
            + "ms'");
  }

  @Override
  public Optional<ReceiptClaim> findReceipt(CommandReceiptKey key, RequestFingerprint fingerprint) {
    return jdbc
        .query(
            "SELECT request_fingerprint, receipt_state, response_status, result_reference "
                + "FROM matching.command_receipt WHERE scope_digest = ? "
                + "AND idempotency_key_digest = ?",
            (result, rowNumber) -> {
              if (!fingerprint.value().equals(result.getString("request_fingerprint"))) {
                return new ReceiptClaim(
                    ReceiptState.KEY_REUSED, Optional.empty(), Optional.empty());
              }
              if ("IN_PROGRESS".equals(result.getString("receipt_state"))) {
                return new ReceiptClaim(
                    ReceiptState.IN_PROGRESS, Optional.empty(), Optional.empty());
              }
              return new ReceiptClaim(
                  ReceiptState.REPLAY,
                  Optional.of(result.getInt("response_status")),
                  Optional.of(new InternalId(result.getObject("result_reference", UUID.class))));
            },
            key.scopeDigest(),
            key.idempotencyKey().value())
        .stream()
        .findFirst();
  }

  @Override
  public ReceiptClaim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, java.time.Instant recordedAt) {
    int inserted =
        jdbc.update(
            "INSERT INTO matching.command_receipt (scope_digest, idempotency_key_digest, "
                + "request_fingerprint, receipt_state, recorded_at) "
                + "VALUES (?, ?, ?, 'IN_PROGRESS', ?) ON CONFLICT DO NOTHING",
            key.scopeDigest(),
            key.idempotencyKey().value(),
            fingerprint.value(),
            Timestamp.from(recordedAt));
    if (inserted == 1) {
      return new ReceiptClaim(ReceiptState.OWNER, Optional.empty(), Optional.empty());
    }
    return findReceipt(key, fingerprint)
        .orElseThrow(() -> new IllegalStateException("Matching receipt claim disappeared"));
  }

  @Override
  public void completeReceipt(
      CommandReceiptKey key,
      int responseStatus,
      InternalId resultReference,
      java.time.Instant recordedAt) {
    int changed =
        jdbc.update(
            "UPDATE matching.command_receipt SET receipt_state = 'COMPLETED', "
                + "response_status = ?, result_reference = ?, recorded_at = ? "
                + "WHERE scope_digest = ? AND idempotency_key_digest = ? "
                + "AND receipt_state = 'IN_PROGRESS'",
            responseStatus,
            resultReference.value(),
            Timestamp.from(recordedAt),
            key.scopeDigest(),
            key.idempotencyKey().value());
    if (changed != 1) {
      throw new IllegalStateException("Matching command receipt could not be completed");
    }
  }

  private String serialize(RecommendationSnapshot snapshot) {
    try {
      return objectMapper.writeValueAsString(snapshot);
    } catch (JacksonException failure) {
      throw new IllegalStateException("Recommendation Snapshot could not be serialized", failure);
    }
  }

  private RecommendationSnapshot deserialize(String snapshotJson) {
    try {
      return objectMapper.readValue(snapshotJson, RecommendationSnapshot.class);
    } catch (JacksonException failure) {
      throw new IllegalStateException("Stored Recommendation Snapshot could not be read", failure);
    }
  }
}
