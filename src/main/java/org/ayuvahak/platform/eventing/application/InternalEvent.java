package org.ayuvahak.platform.eventing.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventType;

/**
 * A persisted event contract presented to one in-process handler. The envelope bytes are immutable;
 * their actor and scope metadata describe the producer and do not grant the handler authority.
 */
public record InternalEvent(
    InternalId eventId,
    EventType eventType,
    String producerContext,
    Optional<AggregatePosition> aggregatePosition,
    byte[] envelopeJson) {

  public InternalEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(eventType, "eventType");
    if (producerContext == null || !producerContext.equals(eventType.context())) {
      throw new IllegalArgumentException("producer context must match the event type context");
    }
    Objects.requireNonNull(aggregatePosition, "aggregatePosition");
    Objects.requireNonNull(envelopeJson, "envelopeJson");
    envelopeJson = envelopeJson.clone();
  }

  @Override
  public byte[] envelopeJson() {
    return envelopeJson.clone();
  }

  @Override
  public String toString() {
    return "InternalEvent[eventId=" + eventId + ", eventType=" + eventType + ", payload=redacted]";
  }
}
