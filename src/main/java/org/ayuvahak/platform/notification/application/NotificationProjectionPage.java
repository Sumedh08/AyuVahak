package org.ayuvahak.platform.notification.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded replay page; refresh-required pages contain no hints. */
public record NotificationProjectionPage(
    List<NotificationHint> hints, Optional<String> nextCursor, boolean refreshRequired) {

  public NotificationProjectionPage {
    hints = List.copyOf(Objects.requireNonNull(hints, "hints"));
    nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
    if (refreshRequired && !hints.isEmpty()) {
      throw new IllegalArgumentException("refresh-required page cannot contain hints");
    }
  }
}
