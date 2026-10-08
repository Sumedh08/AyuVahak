package org.ayuvahak.platform.eventing.application;

import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.event.EventEnvelope;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** Records immutable event publication intent inside the producer's active transaction. */
@Service
public final class OutboxWriter {

  private final OutboxStore store;

  public OutboxWriter(@Lazy OutboxStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public InternalId record(EventEnvelope event) {
    Objects.requireNonNull(event, "event");
    store.record(event);
    return event.eventId();
  }
}
