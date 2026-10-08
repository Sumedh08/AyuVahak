package org.ayuvahak.platform.eventing.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;

/** Payload-free operational metadata for one current internal delivery generation. */
public record EventDeliverySummary(
    InternalId eventId,
    String eventType,
    String producerContext,
    Optional<String> aggregateRef,
    Optional<Long> aggregateSequence,
    int deliveryGeneration,
    String state,
    int attemptCount,
    Instant eventRecordedAt,
    Instant deliveryCreatedAt,
    Instant deliveryUpdatedAt) {

  public EventDeliverySummary {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(producerContext, "producerContext");
    aggregateRef = Objects.requireNonNull(aggregateRef, "aggregateRef");
    aggregateSequence = Objects.requireNonNull(aggregateSequence, "aggregateSequence");
    if (aggregateRef.isPresent() != aggregateSequence.isPresent()) {
      throw new IllegalArgumentException("aggregate reference and sequence must be paired");
    }
    if (deliveryGeneration < 1 || attemptCount < 0) {
      throw new IllegalArgumentException("delivery generation and attempts must be nonnegative");
    }
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(eventRecordedAt, "eventRecordedAt");
    Objects.requireNonNull(deliveryCreatedAt, "deliveryCreatedAt");
    Objects.requireNonNull(deliveryUpdatedAt, "deliveryUpdatedAt");
  }
}
