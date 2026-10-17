package org.ayuvahak.platform.notification.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.event.EventType;

/** Allowlisted, payload-free event input from the internal outbox. */
public record NotificationProjectionEvent(
    InternalId eventId,
    NotificationScope scope,
    String eventType,
    String resourceType,
    UUID resourceId,
    long ownerVersion,
    String correlationId,
    Instant occurredAt,
    Instant recordedAt) {

  public NotificationProjectionEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(scope, "scope");
    EventType.parse(Objects.requireNonNull(eventType, "eventType"));
    if (resourceType == null
        || !resourceType.matches("[a-z][a-z0-9-]{0,39}")
        || resourceId == null
        || ownerVersion < 1) {
      throw new IllegalArgumentException("notification resource reference is invalid");
    }
    if (correlationId == null || !correlationId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("notification correlation identifier is invalid");
    }
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(recordedAt, "recordedAt");
    if (scope.kind() == org.ayuvahak.platform.executioncontext.ExecutionScope.Kind.PLATFORM) {
      throw new IllegalArgumentException("platform notification scope is not supported");
    }
  }
}
