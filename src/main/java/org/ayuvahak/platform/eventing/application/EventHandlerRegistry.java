package org.ayuvahak.platform.eventing.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.ayuvahak.platform.event.EventType;
import org.springframework.stereotype.Component;

/** Resolves the explicitly registered in-process handlers for an event type. */
@Component
public final class EventHandlerRegistry {

  private final Map<EventType, List<InternalEventHandler>> handlers;

  public EventHandlerRegistry(List<InternalEventHandler> configuredHandlers) {
    Objects.requireNonNull(configuredHandlers, "configuredHandlers");
    Map<EventType, List<InternalEventHandler>> resolved = new HashMap<>();
    for (InternalEventHandler handler : configuredHandlers) {
      Objects.requireNonNull(handler, "handler");
      for (EventType eventType : handler.supportedEventTypes()) {
        Objects.requireNonNull(eventType, "supported event type");
        resolved.computeIfAbsent(eventType, ignored -> new ArrayList<>()).add(handler);
      }
    }
    Map<EventType, List<InternalEventHandler>> immutable = new HashMap<>();
    resolved.forEach((type, eventHandlers) -> immutable.put(type, List.copyOf(eventHandlers)));
    handlers = Collections.unmodifiableMap(immutable);
  }

  public List<InternalEventHandler> handlersFor(EventType eventType) {
    return handlers.getOrDefault(Objects.requireNonNull(eventType, "eventType"), List.of());
  }
}
