package org.ayuvahak.platform.configuration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL token buckets shared by application replicas through the authoritative database. */
public final class JdbcRequestBudgetStore implements RequestBudgetStore {

  private static final int SLOT_COUNT = 16_384;
  private static final long MICROS_PER_TOKEN = 1_000_000L;
  private static final long MICROS_PER_MINUTE = Duration.ofMinutes(1).toNanos() / 1_000L;
  private static final long MAX_REFILL_MICROS = MICROS_PER_MINUTE;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  public JdbcRequestBudgetStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc template");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
  }

  @Override
  public boolean tryAcquire(List<Charge> charges) {
    if (charges == null || charges.isEmpty()) {
      throw new IllegalArgumentException("at least one request budget charge is required");
    }
    List<Charge> ordered =
        charges.stream()
            .map(Objects::requireNonNull)
            .sorted(
                java.util.Comparator.comparing((Charge charge) -> charge.dimension().name())
                    .thenComparing(Charge::budgetClass)
                    .thenComparingInt(charge -> slot(charge.identity())))
            .toList();

    return Boolean.TRUE.equals(
        transaction.execute(
            status -> {
              Instant now =
                  jdbc.queryForObject(
                      "SELECT clock_timestamp()",
                      (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant());
              List<BucketState> states = new ArrayList<>(ordered.size());
              for (Charge charge : ordered) {
                BucketKey key =
                    new BucketKey(
                        charge.dimension(), charge.budgetClass(), slot(charge.identity()));
                BucketState state = lockOrCreate(key, charge.requestsPerMinute(), now);
                boolean changedConfiguration =
                    state.configuredLimit() != charge.requestsPerMinute();
                if (changedConfiguration) {
                  // During a mixed-configuration rollout, deny and start the new bucket empty.
                  // A request can pass again only after all replicas converge on one limit.
                  state = new BucketState(key, charge.requestsPerMinute(), 0, now, true);
                } else {
                  state = refill(state, charge.requestsPerMinute(), now);
                }
                states.add(state);
              }

              boolean allowed =
                  states.stream()
                      .noneMatch(
                          state ->
                              state.configurationChanged()
                                  || state.availableMicros() < MICROS_PER_TOKEN);
              for (BucketState state : states) {
                long remaining =
                    allowed ? state.availableMicros() - MICROS_PER_TOKEN : state.availableMicros();
                persist(state, remaining);
              }
              return allowed;
            }));
  }

  private BucketState lockOrCreate(BucketKey key, int limit, Instant now) {
    jdbc.update(
        "INSERT INTO platform_security.request_budget_bucket "
            + "(budget_dimension, budget_class, bucket_slot, tokens_micros, last_refill_at, configured_limit) "
            + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        key.dimension().name(),
        key.budgetClass(),
        key.slot(),
        (long) limit * MICROS_PER_TOKEN,
        Timestamp.from(now),
        limit);
    return jdbc.queryForObject(
        "SELECT tokens_micros, last_refill_at, configured_limit "
            + "FROM platform_security.request_budget_bucket "
            + "WHERE budget_dimension = ? AND budget_class = ? AND bucket_slot = ? FOR UPDATE",
        (resultSet, rowNumber) ->
            new BucketState(
                key,
                resultSet.getInt("configured_limit"),
                resultSet.getLong("tokens_micros"),
                resultSet.getTimestamp("last_refill_at").toInstant(),
                false),
        key.dimension().name(),
        key.budgetClass(),
        key.slot());
  }

  private static BucketState refill(BucketState state, int limit, Instant now) {
    // A backward database-clock adjustment never creates tokens or moves refill time backward.
    Instant refillAt = now.isBefore(state.lastRefillAt()) ? state.lastRefillAt() : now;
    Duration elapsed = Duration.between(state.lastRefillAt(), refillAt);
    long elapsedMicros =
        elapsed.compareTo(Duration.ofMinutes(1)) >= 0
            ? MAX_REFILL_MICROS
            : Math.max(0, elapsed.toNanos() / 1_000L);
    long gainedMicros = (elapsedMicros * limit) / 60L;
    long capacityMicros = (long) limit * MICROS_PER_TOKEN;
    long availableMicros = Math.min(capacityMicros, state.availableMicros() + gainedMicros);
    return new BucketState(state.key(), limit, availableMicros, refillAt, false);
  }

  private void persist(BucketState state, long tokensMicros) {
    jdbc.update(
        "UPDATE platform_security.request_budget_bucket "
            + "SET tokens_micros = ?, last_refill_at = ?, configured_limit = ? "
            + "WHERE budget_dimension = ? AND budget_class = ? AND bucket_slot = ?",
        tokensMicros,
        Timestamp.from(state.lastRefillAt()),
        state.configuredLimit(),
        state.key().dimension().name(),
        state.key().budgetClass(),
        state.key().slot());
  }

  private static int slot(String identity) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
      int nonNegative = ByteBuffer.wrap(digest).getInt() & Integer.MAX_VALUE;
      return nonNegative % SLOT_COUNT;
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required for rate-limit keying.", impossible);
    }
  }

  private record BucketKey(Dimension dimension, String budgetClass, int slot) {}

  private record BucketState(
      BucketKey key,
      int configuredLimit,
      long availableMicros,
      Instant lastRefillAt,
      boolean configurationChanged) {}
}
