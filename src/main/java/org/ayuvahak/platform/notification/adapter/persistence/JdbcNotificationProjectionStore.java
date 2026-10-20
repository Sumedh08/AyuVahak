package org.ayuvahak.platform.notification.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.notification.application.NotificationHint;
import org.ayuvahak.platform.notification.application.NotificationProjectionEvent;
import org.ayuvahak.platform.notification.application.NotificationProjectionPage;
import org.ayuvahak.platform.notification.application.NotificationProjectionStore;
import org.ayuvahak.platform.notification.application.NotificationScope;
import org.ayuvahak.platform.notification.configuration.NotificationProjectionProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL owner for bounded notification hints, cursor epochs, and duplicate receipts. */
@Repository
public class JdbcNotificationProjectionStore implements NotificationProjectionStore {

  private static final String SCOPE_CREATION_LOCK = "SELECT pg_advisory_xact_lock(60120261017)";

  private static final RowMapper<ScopeState> SCOPE_STATE_MAPPER =
      (result, rowNumber) ->
          new ScopeState(
              UUID.fromString(result.getString("cursor_epoch")),
              result.getLong("current_sequence"),
              UUID.fromString(result.getString("current_cursor")));
  private static final RowMapper<CursorPosition> CURSOR_MAPPER =
      (result, rowNumber) ->
          new CursorPosition(
              UUID.fromString(result.getString("cursor_epoch")), result.getLong("sequence_number"));
  private static final RowMapper<NotificationHint> HINT_MAPPER =
      (result, rowNumber) ->
          new NotificationHint(
              result.getString("event_type"),
              result.getString("resource_type"),
              result.getObject("resource_id", UUID.class),
              result.getLong("owner_version"),
              result.getLong("sequence_number"),
              result.getString("cursor_id"),
              result.getString("correlation_id"),
              result.getTimestamp("occurred_at").toInstant(),
              result.getTimestamp("recorded_at").toInstant(),
              result.getTimestamp("projected_at").toInstant());

  private final JdbcTemplate jdbcTemplate;
  private final NotificationProjectionProperties properties;
  private final TransactionTemplate transaction;

  public JdbcNotificationProjectionStore(
      JdbcTemplate jdbcTemplate,
      NotificationProjectionProperties properties,
      PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.transaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
  }

  @Override
  public void append(NotificationProjectionEvent event, Instant projectedAt) {
    Objects.requireNonNull(event, "event");
    Objects.requireNonNull(projectedAt, "projectedAt");
    transaction.executeWithoutResult(
        status -> {
          if (!claimEvent(event.eventId().value(), projectedAt)) {
            return;
          }
          String scopeKey = event.scope().key();
          ScopeState current = ensureScope(event.scope(), projectedAt, true);
          long sequence = Math.addExact(current.sequence(), 1);
          UUID cursor = UUID.randomUUID();
          jdbcTemplate.update(
              "INSERT INTO platform_notification.cursor_position "
                  + "(cursor_id, scope_key, cursor_epoch, sequence_number, created_at) "
                  + "VALUES (?, ?, ?, ?, ?)",
              cursor,
              scopeKey,
              current.epoch(),
              sequence,
              Timestamp.from(projectedAt));
          jdbcTemplate.update(
              "INSERT INTO platform_notification.notification_projection "
                  + "(event_id, cursor_id, scope_key, sequence_number, event_type, resource_type, "
                  + "resource_id, owner_version, correlation_id, occurred_at, recorded_at, projected_at) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              event.eventId().value(),
              cursor,
              scopeKey,
              sequence,
              event.eventType(),
              event.resourceType(),
              event.resourceId(),
              event.ownerVersion(),
              event.correlationId(),
              Timestamp.from(event.occurredAt()),
              Timestamp.from(event.recordedAt()),
              Timestamp.from(projectedAt));
          jdbcTemplate.update(
              "UPDATE platform_notification.scope_cursor "
                  + "SET current_sequence = ?, current_cursor = ?, updated_at = ? WHERE scope_key = ?",
              sequence,
              cursor,
              Timestamp.from(projectedAt),
              scopeKey);
          pruneScope(scopeKey, projectedAt);
          pruneReceipts(projectedAt);
          pruneDormantScopes(projectedAt);
        });
  }

  @Override
  public String currentCursor(NotificationScope scope, Instant now) {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(now, "now");
    return transaction.execute(status -> ensureScope(scope, now, true).currentCursor().toString());
  }

  @Override
  public NotificationProjectionPage listAfter(
      NotificationScope scope,
      Optional<String> cursor,
      int maximumReplayCount,
      int maximumReplayBytes,
      Instant now) {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(cursor, "cursor");
    Objects.requireNonNull(now, "now");
    if (maximumReplayCount < 1 || maximumReplayBytes < 1) {
      throw new IllegalArgumentException("notification replay bounds must be positive");
    }
    return transaction.execute(
        status -> {
          ensureScope(scope, now, true);
          pruneScope(scope.key(), now);
          pruneReceipts(now);
          return readAfter(scope, cursor, maximumReplayCount, maximumReplayBytes, now);
        });
  }

  @Override
  public void reset(Instant now) {
    Objects.requireNonNull(now, "now");
    transaction.executeWithoutResult(
        status -> {
          List<String> scopeKeys =
              jdbcTemplate.query(
                  "SELECT scope_key FROM platform_notification.scope_cursor ORDER BY scope_key FOR UPDATE",
                  (result, rowNumber) -> result.getString("scope_key"));
          jdbcTemplate.update("DELETE FROM platform_notification.notification_projection");
          jdbcTemplate.update("DELETE FROM platform_notification.cursor_position");
          for (String scopeKey : scopeKeys) {
            UUID epoch = UUID.randomUUID();
            UUID cursor = UUID.randomUUID();
            jdbcTemplate.update(
                "UPDATE platform_notification.scope_cursor SET cursor_epoch = ?, "
                    + "current_sequence = 0, current_cursor = ?, updated_at = ? WHERE scope_key = ?",
                epoch,
                cursor,
                Timestamp.from(now),
                scopeKey);
            jdbcTemplate.update(
                "INSERT INTO platform_notification.cursor_position "
                    + "(cursor_id, scope_key, cursor_epoch, sequence_number, created_at) "
                    + "VALUES (?, ?, ?, 0, ?)",
                cursor,
                scopeKey,
                epoch,
                Timestamp.from(now));
          }
        });
  }

  private NotificationProjectionPage readAfter(
      NotificationScope scope,
      Optional<String> requestedCursor,
      int maximumReplayCount,
      int maximumReplayBytes,
      Instant now) {
    ScopeState state = ensureScope(scope, now, false);
    String scopeKey = scope.key();
    String latestCursor = state.currentCursor().toString();
    if (requestedCursor.isEmpty()) {
      return new NotificationProjectionPage(List.of(), Optional.of(latestCursor), false);
    }

    UUID cursorId;
    try {
      cursorId = UUID.fromString(requestedCursor.orElseThrow());
    } catch (IllegalArgumentException invalidCursor) {
      return refresh(latestCursor);
    }
    List<CursorPosition> cursorPositions =
        jdbcTemplate.query(
            "SELECT cursor_epoch, sequence_number FROM platform_notification.cursor_position "
                + "WHERE cursor_id = ? AND scope_key = ?",
            CURSOR_MAPPER,
            cursorId,
            scopeKey);
    if (cursorPositions.isEmpty()) {
      return refresh(latestCursor);
    }
    CursorPosition position = cursorPositions.getFirst();
    if (!position.epoch().equals(state.epoch()) || position.sequence() > state.sequence()) {
      return refresh(latestCursor);
    }

    Long firstRetainedSequence =
        jdbcTemplate.queryForObject(
            "SELECT MIN(sequence_number) FROM platform_notification.notification_projection "
                + "WHERE scope_key = ?",
            Long.class,
            scopeKey);
    if (position.sequence() < state.sequence()
        && (firstRetainedSequence == null || position.sequence() < firstRetainedSequence - 1)) {
      return refresh(latestCursor);
    }

    List<NotificationHint> candidates =
        jdbcTemplate.query(
            "SELECT event_type, resource_type, resource_id, owner_version, sequence_number, "
                + "cursor_id, correlation_id, occurred_at, recorded_at, projected_at "
                + "FROM platform_notification.notification_projection "
                + "WHERE scope_key = ? AND sequence_number > ? "
                + "ORDER BY sequence_number LIMIT ?",
            HINT_MAPPER,
            scopeKey,
            position.sequence(),
            maximumReplayCount + 1);

    List<NotificationHint> page = new ArrayList<>();
    long bytes = 0;
    boolean byteLimitReached = false;
    for (NotificationHint candidate : candidates) {
      if (page.size() == maximumReplayCount) {
        break;
      }
      long candidateBytes = candidate.estimatedJsonBytes();
      if (candidateBytes > maximumReplayBytes && page.isEmpty()) {
        return refresh(latestCursor);
      }
      if (bytes + candidateBytes > maximumReplayBytes) {
        byteLimitReached = true;
        break;
      }
      page.add(candidate);
      bytes += candidateBytes;
    }
    String nextCursor = page.isEmpty() ? cursorId.toString() : page.getLast().cursor();
    // A full bounded page remains resumable from its last item; any excess is fetched on the next
    // call.
    if (byteLimitReached && page.isEmpty()) {
      return refresh(latestCursor);
    }
    return new NotificationProjectionPage(page, Optional.of(nextCursor), false);
  }

  private boolean claimEvent(UUID eventId, Instant now) {
    List<UUID> claimed =
        jdbcTemplate.query(
            "INSERT INTO platform_notification.event_receipt (event_id, processed_at) "
                + "VALUES (?, ?) ON CONFLICT (event_id) DO NOTHING RETURNING event_id",
            (result, rowNumber) -> result.getObject("event_id", UUID.class),
            eventId,
            Timestamp.from(now));
    return !claimed.isEmpty();
  }

  private ScopeState ensureScope(NotificationScope scope, Instant now, boolean lockForUpdate) {
    String scopeKey = scope.key();
    ScopeState existing = findScope(scopeKey, lockForUpdate);
    if (existing != null) {
      return existing;
    }

    // Serialize only scope creation so the configured cardinality limit cannot race.
    jdbcTemplate.execute(SCOPE_CREATION_LOCK);
    existing = findScope(scopeKey, lockForUpdate);
    if (existing != null) {
      return existing;
    }
    Integer scopeCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM platform_notification.scope_cursor", Integer.class);
    if (scopeCount == null || scopeCount >= properties.getMaximumScopes()) {
      throw new IllegalStateException("notification projection scope capacity is exhausted");
    }

    UUID epoch = UUID.randomUUID();
    UUID cursor = UUID.randomUUID();
    List<ScopeState> inserted =
        jdbcTemplate.query(
            "INSERT INTO platform_notification.scope_cursor "
                + "(scope_key, cursor_epoch, current_sequence, current_cursor, updated_at) "
                + "VALUES (?, ?, 0, ?, ?) ON CONFLICT (scope_key) DO NOTHING "
                + "RETURNING cursor_epoch, current_sequence, current_cursor",
            SCOPE_STATE_MAPPER,
            scopeKey,
            epoch,
            cursor,
            Timestamp.from(now));
    if (!inserted.isEmpty()) {
      jdbcTemplate.update(
          "INSERT INTO platform_notification.cursor_position "
              + "(cursor_id, scope_key, cursor_epoch, sequence_number, created_at) "
              + "VALUES (?, ?, ?, 0, ?)",
          cursor,
          scopeKey,
          epoch,
          Timestamp.from(now));
      return inserted.getFirst();
    }
    return Objects.requireNonNull(findScope(scopeKey, lockForUpdate));
  }

  private ScopeState findScope(String scopeKey, boolean lockForUpdate) {
    String lock = lockForUpdate ? " FOR UPDATE" : " FOR SHARE";
    List<ScopeState> matches =
        jdbcTemplate.query(
            "SELECT cursor_epoch, current_sequence, current_cursor "
                + "FROM platform_notification.scope_cursor WHERE scope_key = ?"
                + lock,
            SCOPE_STATE_MAPPER,
            scopeKey);
    return matches.isEmpty() ? null : matches.getFirst();
  }

  private void pruneScope(String scopeKey, Instant now) {
    Instant cutoff = now.minus(properties.getRetention());
    jdbcTemplate.update(
        "DELETE FROM platform_notification.notification_projection projection "
            + "WHERE projection.scope_key = ? AND (projection.projected_at < ? OR projection.event_id IN ("
            + "SELECT event_id FROM (SELECT event_id, row_number() OVER (ORDER BY sequence_number DESC) AS rank "
            + "FROM platform_notification.notification_projection WHERE scope_key = ?) retained "
            + "WHERE retained.rank > ?))",
        scopeKey,
        Timestamp.from(cutoff),
        scopeKey,
        properties.getMaximumEntriesPerScope());
    jdbcTemplate.update(
        "DELETE FROM platform_notification.cursor_position cursor "
            + "WHERE cursor.scope_key = ? AND cursor.sequence_number > 0 "
            + "AND cursor.cursor_id <> (SELECT current_cursor FROM platform_notification.scope_cursor "
            + "WHERE scope_key = ?) AND NOT EXISTS (SELECT 1 FROM "
            + "platform_notification.notification_projection projection WHERE projection.cursor_id = cursor.cursor_id)",
        scopeKey,
        scopeKey);
    jdbcTemplate.update(
        "DELETE FROM platform_notification.cursor_position cursor "
            + "WHERE cursor.scope_key = ? AND cursor.sequence_number = 0 "
            + "AND cursor.cursor_id <> (SELECT current_cursor FROM platform_notification.scope_cursor "
            + "WHERE scope_key = ?) AND cursor.created_at < ?",
        scopeKey,
        scopeKey,
        Timestamp.from(cutoff));
  }

  private void pruneReceipts(Instant now) {
    jdbcTemplate.update(
        "DELETE FROM platform_notification.event_receipt WHERE processed_at < ?",
        Timestamp.from(now.minus(properties.getDeduplicationRetention())));
  }

  private void pruneDormantScopes(Instant now) {
    jdbcTemplate.update(
        "DELETE FROM platform_notification.scope_cursor scope "
            + "WHERE scope.updated_at < ? AND NOT EXISTS (SELECT 1 FROM "
            + "platform_notification.notification_projection projection WHERE projection.scope_key = scope.scope_key)",
        Timestamp.from(now.minus(properties.getRetention())));
  }

  private static NotificationProjectionPage refresh(String currentCursor) {
    return new NotificationProjectionPage(List.of(), Optional.of(currentCursor), true);
  }

  private record ScopeState(UUID epoch, long sequence, UUID currentCursor) {}

  private record CursorPosition(UUID epoch, long sequence) {}
}
