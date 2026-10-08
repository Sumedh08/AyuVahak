package org.ayuvahak.platform.event;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CausationId;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import tools.jackson.databind.node.ObjectNode;

/** A minimized, producer-owned notification of a committed fact for internal consumers. */
public record EventEnvelope(
    InternalId eventId,
    EventType eventType,
    String producerContext,
    OccurredAt occurredAt,
    RecordedAt recordedAt,
    ExecutionContext executionContext,
    Optional<AggregatePosition> aggregatePosition,
    ObjectNode payload) {

  public EventEnvelope {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(eventType, "eventType");
    if (producerContext == null || !producerContext.equals(eventType.context())) {
      throw new IllegalArgumentException("producer context must match the event type context");
    }
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(recordedAt, "recordedAt");
    Objects.requireNonNull(executionContext, "executionContext");
    Objects.requireNonNull(aggregatePosition, "aggregatePosition");
    if (payload == null || !payload.isObject()) {
      throw new IllegalArgumentException("event payload must be an explicit JSON object");
    }
    requireJsonValues(payload);
    payload = payload.deepCopy();
  }

  /** The actor is already verified and the execution scope has already passed server validation. */
  public ActorReference actor() {
    return executionContext.actor();
  }

  public ExecutionScope executionScope() {
    return executionContext.scope();
  }

  public CorrelationId correlationId() {
    return executionContext.correlationId();
  }

  public Optional<CausationId> causationId() {
    return executionContext.causationId();
  }

  /**
   * Return a defensive copy so callers cannot change the fact after construction. Payloads are
   * explicit JSON values owned and minimized by the producer; this envelope does not define
   * business event meaning or provide an external-system event gateway.
   */
  @Override
  public ObjectNode payload() {
    return payload.deepCopy();
  }

  @Override
  public String toString() {
    return "EventEnvelope[eventId="
        + eventId
        + ", eventType="
        + eventType
        + ", producerContext="
        + producerContext
        + ", payload=redacted]";
  }

  private static void requireJsonValues(tools.jackson.databind.JsonNode value) {
    if (value.isObject()) {
      for (var field : value.properties()) {
        requireJsonValues(field.getValue());
      }
      return;
    }
    if (value.isArray()) {
      for (tools.jackson.databind.JsonNode item : value) {
        requireJsonValues(item);
      }
      return;
    }
    if (value.isTextual() || value.isBoolean() || value.isNull()) {
      return;
    }
    if (value.isNumber()
        && (!value.isFloatingPointNumber() || Double.isFinite(value.doubleValue()))) {
      return;
    }
    throw new IllegalArgumentException("event payload must contain only standard JSON values");
  }
}
