package org.ayuvahak.platform.notification.application;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Allowlisted SSE representation; timestamps, source payloads, and clinical fields are excluded.
 */
public record NotificationHintMessage(
    String notificationType,
    String resourceType,
    UUID resourceId,
    long ownerVersion,
    String cursor,
    String correlationId) {

  private static final Set<String> APPROVED_NOTIFICATION_TYPES =
      Set.of("referral.changed", "transfer.changed", "capacity.changed", "transport.changed");

  public NotificationHintMessage {
    Objects.requireNonNull(notificationType, "notificationType");
    if (!APPROVED_NOTIFICATION_TYPES.contains(notificationType)) {
      throw new IllegalArgumentException("notificationType is not an approved hint type");
    }
    Objects.requireNonNull(resourceType, "resourceType");
    Objects.requireNonNull(resourceId, "resourceId");
    if (ownerVersion < 1) {
      throw new IllegalArgumentException("ownerVersion must be positive");
    }
    Objects.requireNonNull(cursor, "cursor");
    Objects.requireNonNull(correlationId, "correlationId");
  }
}
