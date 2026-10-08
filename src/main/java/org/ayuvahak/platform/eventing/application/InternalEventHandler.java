package org.ayuvahak.platform.eventing.application;

import java.util.Set;
import org.ayuvahak.platform.event.EventType;

/** An in-process consumer boundary. Implementations must make their effects safe for redelivery. */
public interface InternalEventHandler {

  Set<EventType> supportedEventTypes();

  void handle(InternalEvent event) throws Exception;
}
