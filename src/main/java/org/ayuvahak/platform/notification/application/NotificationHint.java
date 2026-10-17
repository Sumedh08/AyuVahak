package org.ayuvahak.platform.notification.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Minimal, non-authoritative indication that one owner resource may have changed. */
public record NotificationHint(
    String eventType,
    String resourceType,
    UUID resourceId,
    long ownerVersion,
    long sequence,
    String cursor,
    String correlationId,
    Instant occurredAt,
    Instant recordedAt,
    Instant projectedAt) {

  public NotificationHint {
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(resourceType, "resourceType");
    Objects.requireNonNull(resourceId, "resourceId");
    if (ownerVersion < 1 || sequence < 1) {
      throw new IllegalArgumentException("notification versions must be positive");
    }
    Objects.requireNonNull(cursor, "cursor");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(recordedAt, "recordedAt");
    Objects.requireNonNull(projectedAt, "projectedAt");
  }

  /** Conservative JSON-size estimate (including worst-case escaping) used for bounded replay. */
  public long estimatedJsonBytes() {
    return 256L
        + 6L * utf8Length(eventType)
        + 6L * utf8Length(resourceType)
        + 6L * utf8Length(resourceId.toString())
        + 6L * utf8Length(cursor)
        + 6L * utf8Length(correlationId)
        + utf8Length(occurredAt.toString())
        + utf8Length(recordedAt.toString())
        + utf8Length(projectedAt.toString());
  }

  private static int utf8Length(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }
}
