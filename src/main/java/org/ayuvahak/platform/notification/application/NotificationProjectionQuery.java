package org.ayuvahak.platform.notification.application;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.notification.configuration.NotificationProjectionProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Scope-bound application queries for opening and replaying a bounded notification window. */
@Service
public final class NotificationProjectionQuery {

  private final NotificationProjectionStore store;
  private final NotificationProjectionProperties properties;
  private final Clock clock;

  public NotificationProjectionQuery(
      NotificationProjectionStore store,
      NotificationProjectionProperties properties,
      @Qualifier("notificationClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Establish a baseline cursor at current scope sequence; it does not replay old hints. */
  public String currentCursor(ExecutionScope authorizedScope) {
    NotificationScope scope = NotificationScope.from(authorizedScope);
    return store.currentCursor(scope, clock.instant());
  }

  /** Return only events after a cursor that belongs to this exact authorized scope and epoch. */
  public NotificationProjectionPage listAfter(
      ExecutionScope authorizedScope, Optional<String> cursor, int requestedLimit) {
    Objects.requireNonNull(cursor, "cursor");
    NotificationScope scope = NotificationScope.from(authorizedScope);
    if (scope.kind() == ExecutionScope.Kind.PLATFORM) {
      throw new IllegalArgumentException("platform-wide notification scope is not supported");
    }
    int limit = requestedLimit == 0 ? properties.getMaximumReplayCount() : requestedLimit;
    if (limit < 1 || limit > properties.getMaximumReplayCount()) {
      throw new IllegalArgumentException("notification replay limit exceeds its configured bound");
    }
    if (cursor.isPresent() && !isCanonicalCursor(cursor.orElseThrow())) {
      return refreshRequired(scope);
    }
    return store.listAfter(
        scope, cursor, limit, properties.getMaximumReplayBytes(), clock.instant());
  }

  /**
   * Invalidates every prior cursor without trying to reconstruct history from expired outbox rows.
   */
  public void resetProjection() {
    store.reset(clock.instant());
  }

  private NotificationProjectionPage refreshRequired(NotificationScope scope) {
    return new NotificationProjectionPage(
        java.util.List.of(), Optional.of(store.currentCursor(scope, clock.instant())), true);
  }

  private static boolean isCanonicalCursor(String value) {
    if (value == null || value.length() != 36) {
      return false;
    }
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException failure) {
      return false;
    }
  }
}
