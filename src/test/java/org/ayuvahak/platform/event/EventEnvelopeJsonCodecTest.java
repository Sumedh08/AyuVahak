package org.ayuvahak.platform.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CausationId;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class EventEnvelopeJsonCodecTest {

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final EventEnvelopeJsonCodec codec = new EventEnvelopeJsonCodec(mapper);

  @Test
  void encodesStableVersionedEnvelopeAsDeterministicUtf8Json() {
    EventEnvelope envelope = envelope(eventType(), platformContext(), payloadInOrder("z", "a"));

    byte[] encoded = codec.encode(envelope);
    String json = new String(encoded, StandardCharsets.UTF_8);
    JsonNode document = mapper.readTree(encoded);

    assertThat(json).contains("café");
    assertThat(document.path("eventId").asString())
        .isEqualTo("a2d9a4cf-9f5c-4eee-8d2b-a20925000001");
    assertThat(document.path("eventType").asString()).isEqualTo("identity-access.grant-created.v1");
    assertThat(document.path("producerContext").asString()).isEqualTo("identity-access");
    assertThat(document.path("occurredAt").asString()).isEqualTo("2026-10-08T01:02:03Z");
    assertThat(document.path("recordedAt").asString()).isEqualTo("2026-10-08T01:02:04Z");
    assertThat(document.path("correlationId").asString()).isEqualTo("synthetic-correlation");
    assertThat(document.path("actorKind").asString()).isEqualTo("HUMAN");
    assertThat(document.path("actorRef").path("resourceType").asString()).isEqualTo("subject");
    assertThat(document.path("executionScope").path("kind").asString()).isEqualTo("PLATFORM");
    assertThat(document.path("aggregateRef").asString()).isEqualTo("grant/grant-7");
    assertThat(document.path("aggregateSequence").asLong()).isEqualTo(3);
    assertThat(document.path("payload").path("a").asString()).isEqualTo("café");
    assertThat(document.has("causationId")).isFalse();
    assertThat(codec.encode(envelope)).isEqualTo(encoded);
    assertThat(codec.encode(envelope(eventType(), platformContext(), payloadInOrder("a", "z"))))
        .isEqualTo(encoded);
  }

  @Test
  void emitsOrganizationAndFacilityAttributionWithoutChangingAuthority() {
    ExternalReference organization =
        new ExternalReference(new IssuerId("local-directory"), "Organization", "org-7");
    ExternalReference facility =
        new ExternalReference(new IssuerId("local-directory"), "Facility", "site-9");
    ExecutionContext context = context(RequestedExecutionScope.facility(organization, facility));

    JsonNode scope =
        mapper
            .readTree(codec.encode(envelope(eventType(), context, payloadInOrder("a"))))
            .path("executionScope");

    assertThat(scope.path("kind").asString()).isEqualTo("FACILITY");
    assertThat(scope.path("organizationRef").path("value").asString()).isEqualTo("org-7");
    assertThat(scope.path("facilityRef").path("value").asString()).isEqualTo("site-9");
  }

  @Test
  void includesCausationWhenTheFactWasTriggeredByAnIdentifiedCommandOrEvent() {
    ExecutionContext context =
        new ExecutionContextFactory((actor, scope) -> true)
            .forBackgroundWork(
                ActorReference.service(new IssuerId("synthetic-issuer"), "local-worker"),
                RequestedExecutionScope.organization(
                    new ExternalReference(
                        new IssuerId("local-directory"), "Organization", "org-7")),
                new CorrelationId("synthetic-correlation"),
                Optional.of(new CausationId("synthetic-command-3")));

    JsonNode document =
        mapper.readTree(codec.encode(envelope(eventType(), context, payloadInOrder("a"))));

    assertThat(document.path("causationId").asString()).isEqualTo("synthetic-command-3");
    assertThat(document.path("actorKind").asString()).isEqualTo("SERVICE");
  }

  @Test
  void acceptsUnknownAdditiveMetadataButRejectsUnsupportedMajorEventType() {
    EventType supported = eventType();
    ObjectNode additiveDocument =
        (ObjectNode)
            mapper.readTree(
                codec.encode(envelope(supported, platformContext(), payloadInOrder("a"))));
    additiveDocument.put("futureOptionalField", "ignored-by-v1");

    assertThat(
            codec.requireCompatible(mapper.writeValueAsBytes(additiveDocument), Set.of(supported)))
        .isEqualTo(supported);

    EventType unsupported = EventType.parse("identity-access.grant-created.v2");
    byte[] newer = codec.encode(envelope(unsupported, platformContext(), payloadInOrder("a")));
    assertThatThrownBy(() -> codec.requireCompatible(newer, Set.of(supported)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported event type");
  }

  @Test
  void rejectsProducerMismatchAndIncompleteAggregateOrderingMetadata() {
    assertThatThrownBy(
            () ->
                new EventEnvelope(
                    InternalId.parse("a2d9a4cf-9f5c-4eee-8d2b-a20925000001"),
                    eventType(),
                    "directory",
                    new OccurredAt(Instant.parse("2026-10-08T01:02:03Z")),
                    new RecordedAt(Instant.parse("2026-10-08T01:02:04Z")),
                    platformContext(),
                    Optional.empty(),
                    payloadInOrder("a")))
        .isInstanceOf(IllegalArgumentException.class);

    ObjectNode document =
        (ObjectNode)
            mapper.readTree(
                codec.encode(envelope(eventType(), platformContext(), payloadInOrder("a"))));
    document.remove("aggregateSequence");
    assertThatThrownBy(
            () -> codec.requireCompatible(mapper.writeValueAsBytes(document), Set.of(eventType())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void payloadIsCopiedAndJavaObjectsCannotEnterTheJsonContract() {
    ObjectNode original = payloadInOrder("a");
    EventEnvelope envelope = envelope(eventType(), platformContext(), original);
    original.put("later", "mutation");

    assertThat(envelope.payload().has("later")).isFalse();
    assertThat(envelope.toString()).doesNotContain("café");

    ObjectNode unsupported = mapper.createObjectNode();
    unsupported.putPOJO("value", new Object());
    assertThatThrownBy(() -> envelope(eventType(), platformContext(), unsupported))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("standard JSON values");
  }

  private EventEnvelope envelope(
      EventType eventType, ExecutionContext context, ObjectNode payload) {
    return new EventEnvelope(
        InternalId.parse("a2d9a4cf-9f5c-4eee-8d2b-a20925000001"),
        eventType,
        eventType.context(),
        new OccurredAt(Instant.parse("2026-10-08T01:02:03Z")),
        new RecordedAt(Instant.parse("2026-10-08T01:02:04Z")),
        context,
        Optional.of(new AggregatePosition("grant/grant-7", 3)),
        payload);
  }

  private EventType eventType() {
    return EventType.parse("identity-access.grant-created.v1");
  }

  private ExecutionContext platformContext() {
    return context(RequestedExecutionScope.platform());
  }

  private ExecutionContext context(RequestedExecutionScope requestedScope) {
    return new ExecutionContextFactory((actor, scope) -> true)
        .forInteractiveRequest(
            ActorReference.human(new IssuerId("synthetic-issuer"), "synthetic-actor"),
            requestedScope,
            new CorrelationId("synthetic-correlation"));
  }

  private ObjectNode payloadInOrder(String... keys) {
    ObjectNode payload = mapper.createObjectNode();
    for (String key : keys) {
      payload.put(key, key.equals("a") ? "café" : "synthetic");
    }
    return payload;
  }
}
