package org.ayuvahak.platform.notification.application;

import java.util.Optional;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Query seam used by optional client delivery; the projection remains its durable source. */
public interface NotificationHintReader {

  String currentCursor(ExecutionScope authorizedScope);

  NotificationProjectionPage listAfter(
      ExecutionScope authorizedScope, Optional<String> cursor, int requestedLimit);
}
