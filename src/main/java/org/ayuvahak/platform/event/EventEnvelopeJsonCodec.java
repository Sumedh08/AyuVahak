package org.ayuvahak.platform.event;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CausationId;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Explicit UTF-8 JSON encoding and compatibility checks for the internal event envelope. */
public final class EventEnvelopeJsonCodec {

  private final ObjectMapper mapper;

  public EventEnvelopeJsonCodec(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  /** Encode the known envelope fields in a stable order; payload object keys are canonicalized. */
  public byte[] encode(EventEnvelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    ObjectNode document = mapper.createObjectNode();
    document.put("eventId", envelope.eventId().toString());
    document.put("eventType", envelope.eventType().toString());
    document.put("producerContext", envelope.producerContext());
    document.put("occurredAt", envelope.occurredAt().value().toString());
    document.put("recordedAt", envelope.recordedAt().value().toString());
    document.put("correlationId", envelope.correlationId().value());
    envelope.causationId().ifPresent(value -> document.put("causationId", value.value()));
    document.put("actorKind", envelope.actor().kind().name());
    document.set("actorRef", reference(envelope.actor().identity()));
    document.set("executionScope", executionScope(envelope.executionScope()));
    envelope
        .aggregatePosition()
        .ifPresent(
            position -> {
              document.put("aggregateRef", position.aggregateRef());
              document.put("aggregateSequence", position.sequence());
            });
    document.set("payload", canonicalize(envelope.payload()));
    try {
      return mapper.writeValueAsBytes(document);
    } catch (JacksonException exception) {
      throw new IllegalStateException("event envelope could not be encoded as JSON", exception);
    }
  }

  /**
   * Validate required envelope structure and a consumer's explicitly supported event versions.
   * Unknown optional envelope fields are ignored for forward-compatible additive changes.
   */
  public EventType requireCompatible(byte[] utf8Json, Set<EventType> supportedEventTypes) {
    Objects.requireNonNull(utf8Json, "utf8Json");
    Objects.requireNonNull(supportedEventTypes, "supportedEventTypes");
    JsonNode document;
    try {
      document = mapper.readTree(decodeUtf8(utf8Json));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("event envelope is not valid JSON", exception);
    }
    if (document == null || !document.isObject()) {
      throw new IllegalArgumentException("event envelope must be a JSON object");
    }

    EventType eventType = EventType.parse(text(document, "eventType"));
    if (!supportedEventTypes.contains(eventType)) {
      throw new IllegalArgumentException("unsupported event type");
    }
    if (!eventType.context().equals(text(document, "producerContext"))) {
      throw new IllegalArgumentException("producer context must match the event type context");
    }
    InternalId.parse(text(document, "eventId"));
    Instant.parse(text(document, "occurredAt"));
    Instant.parse(text(document, "recordedAt"));
    new CorrelationId(text(document, "correlationId"));
    if (document.has("causationId")) {
      new CausationId(text(document, "causationId"));
    }
    ActorKind actorKind = ActorKind.valueOf(text(document, "actorKind"));
    JsonNode actorReference = object(document, "actorRef");
    new ActorReference(actorKind, reference(actorReference));
    validateExecutionScope(object(document, "executionScope"));
    validateAggregatePosition(document);
    object(document, "payload");
    return eventType;
  }

  private ObjectNode executionScope(ExecutionScope scope) {
    ObjectNode result = mapper.createObjectNode();
    result.put("kind", scope.kind().name());
    scope.organization().ifPresent(value -> result.set("organizationRef", reference(value)));
    scope.facility().ifPresent(value -> result.set("facilityRef", reference(value)));
    return result;
  }

  private ObjectNode reference(ExternalReference reference) {
    ObjectNode result = mapper.createObjectNode();
    result.put("issuer", reference.issuer().value());
    result.put("resourceType", reference.resourceType());
    result.put("value", reference.value());
    return result;
  }

  private ExternalReference reference(JsonNode document) {
    return new ExternalReference(
        new IssuerId(text(document, "issuer")),
        text(document, "resourceType"),
        text(document, "value"));
  }

  private void validateExecutionScope(JsonNode scope) {
    ExecutionScope.Kind kind = ExecutionScope.Kind.valueOf(text(scope, "kind"));
    switch (kind) {
      case PLATFORM -> {
        if (scope.has("organizationRef") || scope.has("facilityRef")) {
          throw new IllegalArgumentException(
              "platform scope cannot contain organization references");
        }
      }
      case ORGANIZATION -> {
        if (scope.has("facilityRef")) {
          throw new IllegalArgumentException(
              "organization scope cannot contain a facility reference");
        }
        requireReferenceType(object(scope, "organizationRef"), "Organization");
      }
      case FACILITY -> {
        requireReferenceType(object(scope, "organizationRef"), "Organization");
        requireReferenceType(object(scope, "facilityRef"), "Facility");
      }
    }
  }

  private void requireReferenceType(JsonNode referenceNode, String expectedType) {
    ExternalReference reference = reference(referenceNode);
    if (!expectedType.equals(reference.resourceType())) {
      throw new IllegalArgumentException("execution scope reference type is invalid");
    }
  }

  private void validateAggregatePosition(JsonNode document) {
    boolean hasReference = document.has("aggregateRef");
    boolean hasSequence = document.has("aggregateSequence");
    if (hasReference != hasSequence) {
      throw new IllegalArgumentException(
          "aggregate reference and sequence must be present together");
    }
    if (hasReference) {
      JsonNode sequence = document.get("aggregateSequence");
      if (sequence == null || !sequence.isIntegralNumber() || !sequence.canConvertToLong()) {
        throw new IllegalArgumentException("aggregate sequence must be a positive integer");
      }
      new AggregatePosition(text(document, "aggregateRef"), sequence.longValue());
    }
  }

  private static String text(JsonNode document, String field) {
    JsonNode value = document.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("event envelope field must be a string: " + field);
    }
    return value.textValue();
  }

  private static JsonNode object(JsonNode document, String field) {
    JsonNode value = document.get(field);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("event envelope field must be an object: " + field);
    }
    return value;
  }

  private String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("event envelope must use valid UTF-8", exception);
    }
  }

  private JsonNode canonicalize(JsonNode value) {
    if (value.isObject()) {
      ObjectNode sorted = mapper.createObjectNode();
      Map<String, JsonNode> properties = new TreeMap<>();
      for (Map.Entry<String, JsonNode> entry : value.properties()) {
        properties.put(entry.getKey(), entry.getValue());
      }
      properties.forEach((name, child) -> sorted.set(name, canonicalize(child)));
      return sorted;
    }
    if (value.isArray()) {
      ArrayNode array = mapper.createArrayNode();
      for (JsonNode child : value) {
        array.add(canonicalize(child));
      }
      return array;
    }
    return value.deepCopy();
  }
}
