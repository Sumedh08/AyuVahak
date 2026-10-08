package org.ayuvahak.platform.eventing.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.DeliveryReceiptClaim;
import org.ayuvahak.platform.eventing.application.EventDeliveryOperationsStore;
import org.ayuvahak.platform.eventing.application.EventDeliverySummary;
import org.ayuvahak.platform.eventing.application.ReplayCandidate;
import org.ayuvahak.platform.idempotency.configuration.CommandIdempotencyProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for bounded event-delivery inspection and generation replay. */
@Repository
@Lazy
public class PostgresEventDeliveryOperationsStore implements EventDeliveryOperationsStore {

  private final JdbcTemplate jdbc;
  private final CommandIdempotencyProperties idempotencyProperties;

  public PostgresEventDeliveryOperationsStore(
      JdbcTemplate jdbc, CommandIdempotencyProperties idempotencyProperties) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    this.idempotencyProperties =
        java.util.Objects.requireNonNull(idempotencyProperties, "idempotencyProperties");
  }

  @Override
  public List<EventDeliverySummary> listCurrent(Optional<UUID> afterId, int limit) {
    String sql =
        "SELECT e.event_id, e.event_type, e.producer_context, e.aggregate_ref, "
            + "e.aggregate_sequence, e.recorded_at, d.delivery_generation, d.state, "
            + "d.attempt_count, d.created_at, d.updated_at "
            + "FROM platform_eventing.internal_event e JOIN platform_eventing.outbox_delivery d "
            + "ON d.event_id = e.event_id AND d.delivery_generation = e.current_delivery_generation "
            + (afterId.isPresent() ? "WHERE e.event_id > ? " : "")
            + "ORDER BY e.event_id LIMIT ?";
    var mapper =
        (org.springframework.jdbc.core.RowMapper<EventDeliverySummary>)
            (row, index) -> {
              Long sequence = row.getObject("aggregate_sequence", Long.class);
              String aggregateRef = row.getString("aggregate_ref");
              return new EventDeliverySummary(
                  InternalId.parse(row.getString("event_id")),
                  row.getString("event_type"),
                  row.getString("producer_context"),
                  Optional.ofNullable(aggregateRef),
                  Optional.ofNullable(sequence),
                  row.getInt("delivery_generation"),
                  row.getString("state"),
                  row.getInt("attempt_count"),
                  row.getTimestamp("recorded_at").toInstant(),
                  row.getTimestamp("created_at").toInstant(),
                  row.getTimestamp("updated_at").toInstant());
            };
    return afterId.isPresent()
        ? jdbc.query(sql, mapper, afterId.orElseThrow(), limit)
        : jdbc.query(sql, mapper, limit);
  }

  @Override
  public Optional<ReplayCandidate> findCurrent(InternalId eventId) {
    return jdbc
        .query(
            "SELECT e.current_delivery_generation, d.state, e.event_type, e.envelope::text "
                + "FROM platform_eventing.internal_event e JOIN platform_eventing.outbox_delivery d "
                + "ON d.event_id = e.event_id AND d.delivery_generation = e.current_delivery_generation "
                + "WHERE e.event_id = ?",
            (row, index) ->
                new ReplayCandidate(
                    eventId,
                    row.getInt("current_delivery_generation"),
                    row.getString("state"),
                    EventType.parse(row.getString("event_type")),
                    row.getString("envelope").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            eventId.value())
        .stream()
        .findFirst();
  }

  @Override
  public void configureBoundedReceiptWait() {
    jdbc.execute(
        "SET LOCAL lock_timeout = '"
            + idempotencyProperties.getReceiptLockWait().toMillis()
            + "ms'");
  }

  @Override
  public DeliveryReceiptClaim claimReceipt(
      String scopeDigest, String keyDigest, String fingerprint, Instant now) {
    int inserted =
        jdbc.update(
            "INSERT INTO platform_eventing.delivery_command_receipt "
                + "(scope_digest, idempotency_key_digest, request_fingerprint, receipt_state, recorded_at) "
                + "VALUES (?, ?, ?, 'IN_PROGRESS', ?) ON CONFLICT DO NOTHING",
            scopeDigest,
            keyDigest,
            fingerprint,
            Timestamp.from(now));
    if (inserted == 1) {
      return new DeliveryReceiptClaim(
          DeliveryReceiptClaim.State.OWNER, Optional.empty(), Optional.empty());
    }
    return jdbc.queryForObject(
        "SELECT request_fingerprint, receipt_state, event_id, delivery_generation "
            + "FROM platform_eventing.delivery_command_receipt "
            + "WHERE scope_digest = ? AND idempotency_key_digest = ?",
        (row, index) -> {
          if (!fingerprint.equals(row.getString("request_fingerprint"))) {
            return new DeliveryReceiptClaim(
                DeliveryReceiptClaim.State.KEY_REUSED, Optional.empty(), Optional.empty());
          }
          if ("IN_PROGRESS".equals(row.getString("receipt_state"))) {
            return new DeliveryReceiptClaim(
                DeliveryReceiptClaim.State.IN_PROGRESS, Optional.empty(), Optional.empty());
          }
          return new DeliveryReceiptClaim(
              DeliveryReceiptClaim.State.REPLAY,
              Optional.of(InternalId.parse(row.getString("event_id"))),
              Optional.of(row.getInt("delivery_generation")));
        },
        scopeDigest,
        keyDigest);
  }

  @Override
  public Optional<Integer> replay(InternalId eventId, int expectedGeneration, Instant now) {
    List<Integer> generations =
        jdbc.query(
            "UPDATE platform_eventing.internal_event e "
                + "SET current_delivery_generation = current_delivery_generation + 1 "
                + "WHERE e.event_id = ? AND e.current_delivery_generation = ? "
                + "AND EXISTS (SELECT 1 FROM platform_eventing.outbox_delivery d "
                + "WHERE d.event_id = e.event_id AND d.delivery_generation = e.current_delivery_generation "
                + "AND d.state IN ('STALLED', 'UNHANDLED')) "
                + "RETURNING current_delivery_generation",
            (row, index) -> row.getInt(1),
            eventId.value(),
            expectedGeneration);
    if (generations.isEmpty()) {
      return Optional.empty();
    }
    int generation = generations.getFirst();
    jdbc.update(
        "INSERT INTO platform_eventing.outbox_delivery "
            + "(event_id, delivery_generation, state, attempt_count, next_attempt_at, "
            + "lease_generation, lease_until, last_failure_category, created_at, updated_at) "
            + "VALUES (?, ?, 'PENDING', 0, ?, 0, NULL, NULL, ?, ?)",
        eventId.value(),
        generation,
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now));
    return Optional.of(generation);
  }

  @Override
  public void completeReceipt(
      String scopeDigest, String keyDigest, InternalId eventId, int generation, Instant now) {
    int updated =
        jdbc.update(
            "UPDATE platform_eventing.delivery_command_receipt "
                + "SET receipt_state = 'COMPLETED', response_status = 202, event_id = ?, "
                + "delivery_generation = ?, recorded_at = ? "
                + "WHERE scope_digest = ? AND idempotency_key_digest = ? AND receipt_state = 'IN_PROGRESS'",
            eventId.value(),
            generation,
            Timestamp.from(now),
            scopeDigest,
            keyDigest);
    if (updated != 1) {
      throw new IllegalStateException("event delivery command receipt could not be completed");
    }
  }
}
