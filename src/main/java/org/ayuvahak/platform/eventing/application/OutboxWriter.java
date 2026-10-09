package org.ayuvahak.platform.eventing.application;

import java.util.List;
import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.event.EventEnvelope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** Records immutable event publication intent inside the producer's active transaction. */
@Service
public final class OutboxWriter {

  private final OutboxStore store;
  private final EventHandlerRegistry handlers;

  @Autowired
  public OutboxWriter(@Lazy OutboxStore store, EventHandlerRegistry handlers) {
    this.store = Objects.requireNonNull(store, "store");
    this.handlers = Objects.requireNonNull(handlers, "handlers");
  }

  /** Convenience constructor for test-scoped writers with no registered consumers. */
  public OutboxWriter(@Lazy OutboxStore store) {
    this(store, new EventHandlerRegistry(List.of()));
  }

  public InternalId record(EventEnvelope event) {
    Objects.requireNonNull(event, "event");
    store.record(event);
    return event.eventId();
  }

  /**
   * Records the one approved event whose consumer is introduced later in this Volume. New events
   * become ordinary pending delivery work once a compatible consumer is registered.
   */
  public InternalId recordForDeclaredFutureConsumer(EventEnvelope event) {
    Objects.requireNonNull(event, "event");
    if (!DeclaredFutureConsumerEvents.permitsWaiting(event)) {
      throw new IllegalArgumentException("event is not an approved declared-future-consumer event");
    }
    if (handlers.handlersFor(event.eventType()).isEmpty()) {
      store.recordWaitingForConsumer(event);
    } else {
      store.record(event);
    }
    return event.eventId();
  }
}
