package org.ayuvahak.platform.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.event.EventEnvelopeJsonCodec;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.InternalEvent;
import org.ayuvahak.platform.eventing.application.InternalEventHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Projects only explicitly approved owner events; the original event payload remains unretained.
 */
@Component
public class NotificationEventProjectionHandler implements InternalEventHandler {

  private static final EventType REFERRAL_CANCELLED = EventType.parse("referral.cancelled.v1");
  private static final EventType TRANSFER_COMPLETED = EventType.parse("transfer.completed.v1");
  private static final EventType TRANSFER_UNFULFILLED = EventType.parse("transfer.unfulfilled.v1");
  private static final EventType CAPACITY_EXPIRED =
      EventType.parse("capacity.reservation-expired.v1");
  private static final EventType CAPACITY_AT_RISK =
      EventType.parse("capacity.reservation-at-risk.v1");
  private static final EventType CAPACITY_RECOVERED =
      EventType.parse("capacity.reservation-recovered.v1");
  private static final EventType TRANSPORT_DEPARTURE =
      EventType.parse("transport.departure-confirmed.v1");
  private static final EventType TRANSPORT_ARRIVAL =
      EventType.parse("transport.arrival-reported.v1");
  private static final EventType TRANSPORT_INTERRUPTED =
      EventType.parse("transport.interrupted.v1");
  private static final EventType TRANSPORT_UNFULFILLED =
      EventType.parse("transport.unfulfilled.v1");
  private static final EventType TRANSPORT_CANCELLATION =
      EventType.parse("transport.cancellation-resolved.v1");
  private static final EventType TRANSPORT_DESTINATION_CHANGE =
      EventType.parse("transport.destination-change-resolved.v1");

  private static final Map<EventType, ResourceMapping> MAPPINGS =
      Map.ofEntries(
          Map.entry(REFERRAL_CANCELLED, new ResourceMapping("referral", "referral")),
          Map.entry(TRANSFER_COMPLETED, new ResourceMapping("transfer", "transfer-journey")),
          Map.entry(TRANSFER_UNFULFILLED, new ResourceMapping("transfer", "transfer-journey")),
          Map.entry(
              CAPACITY_EXPIRED,
              new ResourceMapping("capacity-reservation", "capacity-reservation")),
          Map.entry(
              CAPACITY_AT_RISK,
              new ResourceMapping("capacity-reservation", "capacity-reservation")),
          Map.entry(
              CAPACITY_RECOVERED,
              new ResourceMapping("capacity-reservation", "capacity-reservation")),
          Map.entry(TRANSPORT_DEPARTURE, new ResourceMapping("transport", "transport-request")),
          Map.entry(TRANSPORT_ARRIVAL, new ResourceMapping("transport", "transport-request")),
          Map.entry(TRANSPORT_INTERRUPTED, new ResourceMapping("transport", "transport-request")),
          Map.entry(TRANSPORT_UNFULFILLED, new ResourceMapping("transport", "transport-request")),
          Map.entry(
              TRANSPORT_CANCELLATION,
              new ResourceMapping("transport-cancellation", "transport-cancellation")),
          Map.entry(
              TRANSPORT_DESTINATION_CHANGE,
              new ResourceMapping("transport-destination-change", "transport-destination-change")));

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";

  private final NotificationProjectionStore store;
  private final EventEnvelopeJsonCodec envelopeCodec;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public NotificationEventProjectionHandler(
      NotificationProjectionStore store,
      EventEnvelopeJsonCodec envelopeCodec,
      ObjectMapper objectMapper,
      @Qualifier("notificationClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.envelopeCodec = Objects.requireNonNull(envelopeCodec, "envelopeCodec");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Set<EventType> supportedEventTypes() {
    return MAPPINGS.keySet();
  }

  @Override
  @Transactional
  public void handle(InternalEvent event) {
    Objects.requireNonNull(event, "event");
    ResourceMapping mapping = MAPPINGS.get(event.eventType());
    if (mapping == null) {
      return;
    }
    envelopeCodec.requireCompatible(event.envelopeJson(), supportedEventTypes());
    JsonNode envelope = readEnvelope(event.envelopeJson());
    var position =
        event
            .aggregatePosition()
            .orElseThrow(
                () ->
                    new IllegalArgumentException("notification event requires an owner position"));
    UUID resourceId = resourceId(position.aggregateRef(), mapping.aggregatePrefix());
    NotificationScope scope = scope(envelope.get("executionScope"));
    if (scope.kind() == org.ayuvahak.platform.executioncontext.ExecutionScope.Kind.PLATFORM) {
      return;
    }
    String correlationId = text(envelope, "correlationId");
    Instant occurredAt = Instant.parse(text(envelope, "occurredAt"));
    Instant recordedAt = Instant.parse(text(envelope, "recordedAt"));
    store.append(
        new NotificationProjectionEvent(
            event.eventId(),
            scope,
            event.eventType().toString(),
            mapping.resourceType(),
            resourceId,
            position.sequence(),
            correlationId,
            occurredAt,
            recordedAt),
        clock.instant());
  }

  private JsonNode readEnvelope(byte[] bytes) {
    try {
      JsonNode envelope =
          objectMapper.readTree(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
      if (envelope == null || !envelope.isObject()) {
        throw new IllegalArgumentException("event envelope must be an object");
      }
      return envelope;
    } catch (JacksonException failure) {
      throw new IllegalArgumentException("event envelope is invalid", failure);
    }
  }

  private static NotificationScope scope(JsonNode value) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("event execution scope is invalid");
    }
    var kind =
        org.ayuvahak.platform.executioncontext.ExecutionScope.Kind.valueOf(text(value, "kind"));
    return switch (kind) {
      case PLATFORM ->
          new NotificationScope(kind, java.util.Optional.empty(), java.util.Optional.empty());
      case ORGANIZATION ->
          new NotificationScope(
              kind,
              java.util.Optional.of(reference(value.get("organizationRef"))),
              java.util.Optional.empty());
      case FACILITY ->
          new NotificationScope(
              kind,
              java.util.Optional.of(reference(value.get("organizationRef"))),
              java.util.Optional.of(reference(value.get("facilityRef"))));
    };
  }

  private static ExternalReference reference(JsonNode value) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("event scope reference is invalid");
    }
    return new ExternalReference(
        new IssuerId(text(value, "issuer")), text(value, "resourceType"), text(value, "value"));
  }

  private static String text(JsonNode value, String field) {
    JsonNode child = value.get(field);
    if (child == null || !child.isTextual()) {
      throw new IllegalArgumentException("event envelope field is invalid: " + field);
    }
    return child.textValue();
  }

  private static UUID resourceId(String aggregateRef, String expectedPrefix) {
    String prefix = expectedPrefix + "/";
    if (!aggregateRef.startsWith(prefix) || aggregateRef.length() != prefix.length() + 36) {
      throw new IllegalArgumentException(
          "notification aggregate reference is not an internal resource ID");
    }
    String value = aggregateRef.substring(prefix.length());
    try {
      UUID resourceId = UUID.fromString(value);
      if (!resourceId.toString().equals(value)) {
        throw new IllegalArgumentException("notification resource ID is not canonical");
      }
      return resourceId;
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("notification aggregate reference is invalid", failure);
    }
  }

  private record ResourceMapping(String resourceType, String aggregatePrefix) {}
}
