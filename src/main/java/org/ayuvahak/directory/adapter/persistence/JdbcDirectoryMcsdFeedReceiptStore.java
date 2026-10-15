package org.ayuvahak.directory.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.directory.application.DirectoryMcsdFeedReceiptStore;
import org.ayuvahak.directory.application.DirectorySourceProjectionReconciliation;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.idempotency.configuration.CommandIdempotencyProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC adapter for Directory-owned inbound mCSD feed replay receipts. */
@Repository
@Lazy
public class JdbcDirectoryMcsdFeedReceiptStore implements DirectoryMcsdFeedReceiptStore {

  private final JdbcTemplate jdbc;
  private final CommandIdempotencyProperties idempotencyProperties;

  public JdbcDirectoryMcsdFeedReceiptStore(
      JdbcTemplate jdbc, CommandIdempotencyProperties idempotencyProperties) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.idempotencyProperties =
        Objects.requireNonNull(idempotencyProperties, "idempotencyProperties");
  }

  @Override
  public void configureBoundedReceiptWait() {
    jdbc.execute(
        "SET LOCAL lock_timeout = '"
            + idempotencyProperties.getReceiptLockWait().toMillis()
            + "ms'");
  }

  @Override
  public boolean claim(
      IssuerId sourceNamespace,
      String externalMessageId,
      String operationCode,
      String requestFingerprint,
      String correlationId,
      Instant recordedAt) {
    return jdbc.update(
            "INSERT INTO directory.mcsd_feed_receipt (source_issuer, external_message_id, "
                + "operation_code, request_fingerprint, original_correlation_id, "
                + "response_outcomes, recorded_at, completed_at) VALUES (?, ?, ?, ?, ?, NULL, ?, NULL) "
                + "ON CONFLICT (source_issuer, external_message_id, operation_code) DO NOTHING",
            sourceNamespace.value(),
            externalMessageId,
            operationCode,
            requestFingerprint,
            correlationId,
            Timestamp.from(recordedAt))
        == 1;
  }

  @Override
  public Optional<Receipt> findForUpdate(
      IssuerId sourceNamespace, String externalMessageId, String operationCode) {
    return jdbc
        .query(
            "SELECT request_fingerprint, response_outcomes FROM directory.mcsd_feed_receipt "
                + "WHERE source_issuer = ? AND external_message_id = ? AND operation_code = ? FOR UPDATE",
            (result, row) ->
                new RawReceipt(
                    result.getString("request_fingerprint"), result.getString("response_outcomes")),
            sourceNamespace.value(),
            externalMessageId,
            operationCode)
        .stream()
        .findFirst()
        .map(
            stored -> {
              if (stored.responseOutcomes() == null) {
                throw new IllegalStateException("a committed Directory feed receipt is incomplete");
              }
              return new Receipt(
                  stored.requestFingerprint(), decodeOutcomes(stored.responseOutcomes()));
            });
  }

  @Override
  public void complete(
      IssuerId sourceNamespace,
      String externalMessageId,
      String operationCode,
      String requestFingerprint,
      List<DirectorySourceProjectionReconciliation.Outcome> outcomes,
      Instant completedAt) {
    String encoded =
        Objects.requireNonNull(outcomes, "outcomes").stream()
            .map(DirectorySourceProjectionReconciliation.Outcome::name)
            .collect(java.util.stream.Collectors.joining(","));
    if (encoded.isBlank() || encoded.length() > 12_000) {
      throw new IllegalArgumentException("Directory feed outcomes are outside the supported bound");
    }
    int updated =
        jdbc.update(
            "UPDATE directory.mcsd_feed_receipt SET response_outcomes = ?, completed_at = ? "
                + "WHERE source_issuer = ? AND external_message_id = ? AND operation_code = ? "
                + "AND request_fingerprint = ? AND response_outcomes IS NULL",
            encoded,
            Timestamp.from(completedAt),
            sourceNamespace.value(),
            externalMessageId,
            operationCode,
            requestFingerprint);
    if (updated != 1) {
      throw new IllegalStateException("Directory feed receipt could not be completed atomically");
    }
  }

  private static List<DirectorySourceProjectionReconciliation.Outcome> decodeOutcomes(
      String encoded) {
    try {
      return java.util.Arrays.stream(encoded.split(",", -1))
          .map(DirectorySourceProjectionReconciliation.Outcome::valueOf)
          .toList();
    } catch (IllegalArgumentException invalidOutcome) {
      throw new IllegalStateException("Directory feed receipt contains an invalid owner outcome");
    }
  }

  private record RawReceipt(String requestFingerprint, String responseOutcomes) {}
}
