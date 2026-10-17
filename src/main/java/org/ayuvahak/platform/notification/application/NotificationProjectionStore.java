package org.ayuvahak.platform.notification.application;

import java.time.Instant;
import java.util.Optional;

/** Durable projection boundary. It owns cursor sequencing, deduplication, retention and reset. */
public interface NotificationProjectionStore {

  void append(NotificationProjectionEvent event, Instant projectedAt);

  String currentCursor(NotificationScope scope, Instant now);

  NotificationProjectionPage listAfter(
      NotificationScope scope,
      Optional<String> cursor,
      int maximumReplayCount,
      int maximumReplayBytes,
      Instant now);

  void reset(Instant now);
}
