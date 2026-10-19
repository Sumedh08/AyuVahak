package org.ayuvahak.platform.eventing.adapter.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventEnvelopeJsonCodec;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.DeclaredFutureConsumerEvents;
import org.ayuvahak.platform.eventing.application.DeliveryFailureResult;
import org.ayuvahak.platform.eventing.application.InternalEvent;
import org.ayuvahak.platform.eventing.application.LeaseToken;
import org.ayuvahak.platform.eventing.application.LeasedEvent;
import org.ayuvahak.platform.eventing.application.OutboxStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL implementation of the eventing-owned outbox state boundary. */
@Repository
@Lazy
public class PostgresOutboxStore implements OutboxStore {

  private static final String CLAIM_DUE_SQL =
      "WITH candidates AS ("
          + " SELECT d.event_id, d.delivery_generation"
          + " FROM platform_eventing.outbox_delivery d"
          + " JOIN platform_eventing.internal_event e ON e.event_id = d.event_id"
          + " WHERE d.delivery_generation = e.current_delivery_generation"
          + "   AND ((d.state = 'PENDING' AND d.next_attempt_at <= ?)"
          + "     OR (d.state = 'LEASED' AND d.lease_until <= ? AND d.attempt_count < ?))"
          + "   AND NOT EXISTS ("
          + "     SELECT 1"
          + "     FROM platform_eventing.internal_event earlier"
          + "     JOIN platform_eventing.outbox_delivery earlier_delivery"
          + "       ON earlier_delivery.event_id = earlier.event_id"
          + "      AND earlier_delivery.delivery_generation = earlier.current_delivery_generation"
          + "     WHERE e.aggregate_ref IS NOT NULL"
          + "       AND earlier.producer_context = e.producer_context"
          + "       AND earlier.aggregate_ref = e.aggregate_ref"
          + "       AND earlier.aggregate_sequence < e.aggregate_sequence"
          + "       AND earlier_delivery.state <> 'DELIVERED'"
          + "   )"
          + " ORDER BY d.next_attempt_at, d.created_at, d.event_id"
          + " LIMIT ? FOR UPDATE OF d SKIP LOCKED"
          + ")"
          + " UPDATE platform_eventing.outbox_delivery d"
          + " SET state = 'LEASED', lease_generation = d.lease_generation + 1,"
          + "     lease_until = ?, updated_at = ?"
          + " FROM candidates c"
          + " WHERE d.event_id = c.event_id AND d.delivery_generation = c.delivery_generation"
          + " RETURNING d.event_id, d.delivery_generation, d.lease_generation";

  private final JdbcTemplate jdbcTemplate;
  private final EventEnvelopeJsonCodec codec;
  private final Clock clock;
  private final TransactionTemplate transaction;

  public PostgresOutboxStore(
      JdbcTemplate jdbcTemplate,
      EventEnvelopeJsonCodec codec,
      Clock outboxClock,
      PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.clock = Objects.requireNonNull(outboxClock, "outboxClock");
    this.transaction = new TransactionTemplate(transactionManager);
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
  }

  @Override
  public void record(EventEnvelope event) {
    record(event, "PENDING");
  }

  @Override
  public void recordWaitingForConsumer(EventEnvelope event) {
    if (!DeclaredFutureConsumerEvents.permitsWaiting(event)) {
      throw new IllegalArgumentException("event is not an approved declared-future-consumer event");
    }
    record(event, "WAITING_FOR_CONSUMER");
  }

  private void record(EventEnvelope event, String initialDeliveryState) {
    Objects.requireNonNull(event, "event");
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("outbox intent must join the producer's active transaction");
    }

    byte[] encoded = codec.encode(event);
    codec.requireCompatible(encoded, java.util.Set.of(event.eventType()));
    Instant now = clock.instant();
    Optional<AggregatePosition> position = event.aggregatePosition();
    position.ifPresent(value -> lockAndValidateSequence(event, value));

    String sql =
        "INSERT INTO platform_eventing.internal_event "
            + "(event_id, event_type, producer_context, aggregate_ref, aggregate_sequence, "
            + "envelope, recorded_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)";
    jdbcTemplate.update(
        sql,
        event.eventId().value(),
        event.eventType().toString(),
        event.producerContext(),
        position.map(AggregatePosition::aggregateRef).orElse(null),
        position.map(AggregatePosition::sequence).orElse(null),
        new String(encoded, StandardCharsets.UTF_8),
        Timestamp.from(now));
    jdbcTemplate.update(
        "INSERT INTO platform_eventing.outbox_delivery "
            + "(event_id, delivery_generation, state, next_attempt_at, created_at, updated_at) "
            + "VALUES (?, 1, ?, ?, ?, ?)",
        event.eventId().value(),
        initialDeliveryState,
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now));
  }

  @Override
  public List<LeasedEvent> claimDue(
      Instant now, Duration leaseDuration, int maximumAttempts, int limit) {
    requirePositive(leaseDuration, "leaseDuration");
    if (maximumAttempts < 1 || maximumAttempts > 100 || limit < 1) {
      throw new IllegalArgumentException("claim attempts and limit must be within their bounds");
    }
    Objects.requireNonNull(now, "now");
    return transaction.execute(
        status -> {
          stallExpiredExhaustedLeases(now, maximumAttempts);
          List<LeaseToken> tokens =
              jdbcTemplate.query(
                  CLAIM_DUE_SQL,
                  (rs, rowNum) ->
                      new LeaseToken(
                          InternalId.parse(rs.getString("event_id")),
                          rs.getInt("delivery_generation"),
                          rs.getLong("lease_generation")),
                  Timestamp.from(now),
                  Timestamp.from(now),
                  maximumAttempts,
                  limit,
                  Timestamp.from(now.plus(leaseDuration)),
                  Timestamp.from(now));
          List<LeasedEvent> claimed = new ArrayList<>(tokens.size());
          for (LeaseToken token : tokens) {
            claimed.add(loadLeasedEvent(token));
          }
          return List.copyOf(claimed);
        });
  }

  @Override
  public boolean markUnhandled(LeaseToken token, Instant now) {
    Objects.requireNonNull(token, "token");
    return jdbcTemplate.update(
            "UPDATE platform_eventing.outbox_delivery "
                + "SET state = 'UNHANDLED', lease_until = NULL, "
                + "last_failure_category = 'NO_HANDLER', updated_at = ? "
                + "WHERE event_id = ? AND delivery_generation = ? AND state = 'LEASED' "
                + "AND lease_generation = ? AND lease_until > ?",
            Timestamp.from(now),
            token.eventId().value(),
            token.deliveryGeneration(),
            token.leaseGeneration(),
            Timestamp.from(now))
        == 1;
  }

  @Override
  public int beginAttempt(LeaseToken token, Instant now, int maximumAttempts) {
    Objects.requireNonNull(token, "token");
    if (maximumAttempts < 1 || maximumAttempts > 100) {
      throw new IllegalArgumentException("maximum attempts must be between 1 and 100");
    }
    List<Integer> attempts =
        jdbcTemplate.query(
            "UPDATE platform_eventing.outbox_delivery "
                + "SET attempt_count = attempt_count + 1, updated_at = ? "
                + "WHERE event_id = ? AND delivery_generation = ? AND state = 'LEASED' "
                + "AND lease_generation = ? AND lease_until > ? AND attempt_count < ? "
                + "RETURNING attempt_count",
            (rs, rowNum) -> rs.getInt(1),
            Timestamp.from(now),
            token.eventId().value(),
            token.deliveryGeneration(),
            token.leaseGeneration(),
            Timestamp.from(now),
            maximumAttempts);
    return attempts.isEmpty() ? 0 : attempts.getFirst();
  }

  @Override
  public boolean renewLease(LeaseToken token, Instant now, Duration leaseDuration) {
    requirePositive(leaseDuration, "leaseDuration");
    return jdbcTemplate.update(
            "UPDATE platform_eventing.outbox_delivery "
                + "SET lease_until = ?, updated_at = ? "
                + "WHERE event_id = ? AND delivery_generation = ? AND state = 'LEASED' "
                + "AND lease_generation = ? AND lease_until > ?",
            Timestamp.from(now.plus(leaseDuration)),
            Timestamp.from(now),
            token.eventId().value(),
            token.deliveryGeneration(),
            token.leaseGeneration(),
            Timestamp.from(now))
        == 1;
  }

  @Override
  public boolean markDelivered(LeaseToken token, Instant now) {
    return jdbcTemplate.update(
            "UPDATE platform_eventing.outbox_delivery "
                + "SET state = 'DELIVERED', lease_until = NULL, last_failure_category = NULL, "
                + "updated_at = ? WHERE event_id = ? AND delivery_generation = ? "
                + "AND state = 'LEASED' AND lease_generation = ? AND lease_until > ?",
            Timestamp.from(now),
            token.eventId().value(),
            token.deliveryGeneration(),
            token.leaseGeneration(),
            Timestamp.from(now))
        == 1;
  }

  @Override
  public int purgeDeliveredBefore(Instant cutoff, int limit) {
    Objects.requireNonNull(cutoff, "cutoff");
    if (limit < 1 || limit > 1000) {
      throw new IllegalArgumentException("purge limit must be between 1 and 1000");
    }
    return transaction.execute(
        status -> {
          List<UUID> expired =
              jdbcTemplate.query(
                  "SELECT e.event_id FROM platform_eventing.internal_event e "
                      + "JOIN platform_eventing.outbox_delivery d "
                      + "ON d.event_id = e.event_id "
                      + "AND d.delivery_generation = e.current_delivery_generation "
                      + "WHERE d.state = 'DELIVERED' AND d.updated_at <= ? "
                      + "AND NOT EXISTS (SELECT 1 FROM platform_eventing.retention_hold h "
                      + "WHERE h.event_id = e.event_id AND h.released_at IS NULL) "
                      + "ORDER BY d.updated_at, e.event_id LIMIT ? FOR UPDATE OF e SKIP LOCKED",
                  (rs, rowNum) -> rs.getObject("event_id", UUID.class),
                  Timestamp.from(cutoff),
                  limit);
          for (UUID eventId : expired) {
            jdbcTemplate.update(
                "INSERT INTO platform_eventing.retention_purge_receipt "
                    + "(event_id, delivery_generation, purged_at) "
                    + "SELECT e.event_id, e.current_delivery_generation, ? "
                    + "FROM platform_eventing.internal_event e WHERE e.event_id = ? "
                    + "ON CONFLICT (event_id, delivery_generation) DO NOTHING",
                Timestamp.from(clock.instant()),
                eventId);
            jdbcTemplate.update(
                "DELETE FROM platform_eventing.delivery_command_receipt WHERE event_id = ?",
                eventId);
            jdbcTemplate.update(
                "DELETE FROM platform_eventing.outbox_delivery WHERE event_id = ?", eventId);
            jdbcTemplate.update(
                "DELETE FROM platform_eventing.internal_event WHERE event_id = ?", eventId);
          }
          return expired.size();
        });
  }

  @Override
  public DeliveryFailureResult markFailed(
      LeaseToken token, Instant failedAt, Instant nextAttemptAt, int maximumAttempts) {
    return transaction.execute(
        status -> {
          int updated =
              jdbcTemplate.update(
                  "UPDATE platform_eventing.outbox_delivery "
                      + "SET state = CASE WHEN attempt_count >= ? THEN 'STALLED' ELSE 'PENDING' END, "
                      + "next_attempt_at = ?, lease_until = NULL, "
                      + "last_failure_category = 'HANDLER_FAILURE', updated_at = ? "
                      + "WHERE event_id = ? AND delivery_generation = ? AND state = 'LEASED' "
                      + "AND lease_generation = ? AND lease_until > ?",
                  maximumAttempts,
                  Timestamp.from(nextAttemptAt),
                  Timestamp.from(failedAt),
                  token.eventId().value(),
                  token.deliveryGeneration(),
                  token.leaseGeneration(),
                  Timestamp.from(failedAt));
          if (updated == 0) {
            return DeliveryFailureResult.LEASE_LOST;
          }
          String state =
              jdbcTemplate.queryForObject(
                  "SELECT state FROM platform_eventing.outbox_delivery "
                      + "WHERE event_id = ? AND delivery_generation = ?",
                  String.class,
                  token.eventId().value(),
                  token.deliveryGeneration());
          return "STALLED".equals(state)
              ? DeliveryFailureResult.STALLED
              : DeliveryFailureResult.RETRY_SCHEDULED;
        });
  }

  private void stallExpiredExhaustedLeases(Instant now, int maximumAttempts) {
    jdbcTemplate.update(
        "UPDATE platform_eventing.outbox_delivery "
            + "SET state = 'STALLED', lease_until = NULL, "
            + "last_failure_category = 'LEASE_EXPIRED_AFTER_MAX_ATTEMPTS', updated_at = ? "
            + "WHERE state = 'LEASED' AND lease_until <= ? AND attempt_count >= ?",
        Timestamp.from(now),
        Timestamp.from(now),
        maximumAttempts);
  }

  private LeasedEvent loadLeasedEvent(LeaseToken token) {
    return jdbcTemplate
        .queryForObject(
            "SELECT e.event_id, e.event_type, e.producer_context, e.aggregate_ref, e.aggregate_sequence, "
                + "e.envelope::text AS envelope "
                + "FROM platform_eventing.internal_event e "
                + "JOIN platform_eventing.outbox_delivery d ON d.event_id = e.event_id "
                + "WHERE e.event_id = ? AND d.delivery_generation = ? AND d.lease_generation = ?",
            LEASED_EVENT_ROW_MAPPER,
            token.eventId().value(),
            token.deliveryGeneration(),
            token.leaseGeneration())
        .withLeaseToken(token);
  }

  private void lockAndValidateSequence(EventEnvelope event, AggregatePosition position) {
    String lockName = event.producerContext() + ":" + position.aggregateRef();
    jdbcTemplate.execute(
        (ConnectionCallback<Void>)
            connection -> {
              try (var statement =
                  connection.prepareStatement(
                      "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, lockName);
                statement.execute();
              }
              return null;
            });
    Boolean hasLaterSequence =
        jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM platform_eventing.internal_event "
                + "WHERE producer_context = ? AND aggregate_ref = ? AND aggregate_sequence > ?)",
            Boolean.class,
            event.producerContext(),
            position.aggregateRef(),
            position.sequence());
    if (Boolean.TRUE.equals(hasLaterSequence)) {
      throw new IllegalStateException("aggregate event sequence cannot be inserted out of order");
    }
  }

  private void requirePositive(Duration duration, String name) {
    if (duration == null || duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static final RowMapper<LeasedEventRow> LEASED_EVENT_ROW_MAPPER =
      (rs, rowNum) -> {
        Long sequence = rs.getObject("aggregate_sequence", Long.class);
        String aggregateRef = rs.getString("aggregate_ref");
        Optional<AggregatePosition> position =
            sequence == null
                ? Optional.empty()
                : Optional.of(new AggregatePosition(aggregateRef, sequence));
        InternalEvent event =
            new InternalEvent(
                InternalId.parse(rs.getString("event_id")),
                EventType.parse(rs.getString("event_type")),
                rs.getString("producer_context"),
                position,
                rs.getString("envelope").getBytes(StandardCharsets.UTF_8));
        return new LeasedEventRow(event);
      };

  private record LeasedEventRow(InternalEvent event) {
    private LeasedEvent withLeaseToken(LeaseToken token) {
      return new LeasedEvent(token, event);
    }
  }
}
