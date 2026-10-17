package org.ayuvahak.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventEnvelopeJsonCodec;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.InternalEvent;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.notification.adapter.persistence.JdbcNotificationProjectionStore;
import org.ayuvahak.platform.notification.application.NotificationEventProjectionHandler;
import org.ayuvahak.platform.notification.application.NotificationProjectionPage;
import org.ayuvahak.platform.notification.application.NotificationProjectionQuery;
import org.ayuvahak.platform.notification.configuration.NotificationProjectionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "ayuvahak.authentication.issuer=https://identity.example.test/realms/test",
      "ayuvahak.authentication.jwk-set-uri=http://127.0.0.1:9/jwks",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-worker",
      "ayuvahak.authentication.allow-http=true",
      "ayuvahak.eventing.outbox.dispatcher-enabled=false",
      "ayuvahak.eventing.outbox.delivered-event-retention=PT24H",
      "ayuvahak.notifications.projection.retention=PT24H",
      "ayuvahak.notifications.projection.deduplication-retention=P2D",
      "ayuvahak.notifications.projection.maximum-scopes=2",
      "ayuvahak.notifications.projection.maximum-entries-per-scope=100",
      "ayuvahak.notifications.projection.maximum-replay-count=20",
      "ayuvahak.notifications.projection.maximum-replay-bytes=16384"
    })
@Testcontainers
@ActiveProfiles("notification-projection-postgres")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationProjectionPostgresIT {

  private static final String TEST_PASSWORD = "local-test-only";
  private static final Instant BASE_TIME = Instant.parse("2026-10-17T00:00:00Z");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(TEST_PASSWORD);

  @DynamicPropertySource
  static void configureDatabase(DynamicPropertyRegistry registry) {
    registry.add("AYUVAHAK_DATABASE_URL", POSTGRES::getJdbcUrl);
    registry.add("AYUVAHAK_DATABASE_USERNAME", POSTGRES::getUsername);
    registry.add("AYUVAHAK_DATABASE_PASSWORD", POSTGRES::getPassword);
  }

  @Autowired private NotificationEventProjectionHandler handler;
  @Autowired private NotificationProjectionQuery query;
  @Autowired private EventEnvelopeJsonCodec codec;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private NotificationProjectionProperties properties;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private TestClock clock;

  @BeforeEach
  void resetProjectionAndClock() {
    clock.set(BASE_TIME);
    jdbcTemplate.update("DELETE FROM platform_notification.event_receipt");
    jdbcTemplate.update("DELETE FROM platform_notification.scope_cursor");
  }

  @Test
  void projectsOnlyAnAllowlistedHintAndKeepsItsCursorScopeBound() throws Exception {
    ExecutionScope organizationScope = organizationScope("SYN-ORG-001");
    ExecutionScope otherOrganizationScope = organizationScope("SYN-ORG-002");
    String startingCursor = query.currentCursor(organizationScope);
    String privateMarker = "SYN-PATIENT-FEDERATION-PRIVATE-4B71";
    EventEnvelope event =
        event(
            "transfer.completed.v1",
            "transfer-journey/00000000-0000-0000-0000-000000000001",
            7,
            organizationScope,
            privateMarker);

    handler.handle(internalEvent(event));

    NotificationProjectionPage replay =
        query.listAfter(organizationScope, Optional.of(startingCursor), 20);
    assertThat(replay.refreshRequired()).isFalse();
    assertThat(replay.hints())
        .singleElement()
        .satisfies(
            hint -> {
              assertThat(hint.eventType()).isEqualTo("transfer.completed.v1");
              assertThat(hint.resourceType()).isEqualTo("transfer");
              assertThat(hint.resourceId())
                  .isEqualTo(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
              assertThat(hint.ownerVersion()).isEqualTo(7);
              assertThat(hint.sequence()).isEqualTo(1);
              assertThat(hint.cursor()).isNotEqualTo(startingCursor);
              assertThat(hint.correlationId()).isEqualTo("notification-test-correlation");
            });
    assertThat(objectMapper.writeValueAsString(replay)).doesNotContain(privateMarker);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_notification.notification_projection "
                    + "WHERE CAST(resource_id AS TEXT) LIKE ? OR correlation_id LIKE ?",
                Integer.class,
                "%" + privateMarker + "%",
                "%" + privateMarker + "%"))
        .isZero();

    NotificationProjectionPage crossScope =
        query.listAfter(otherOrganizationScope, Optional.of(startingCursor), 20);
    assertThat(crossScope.refreshRequired()).isTrue();
    assertThat(crossScope.hints()).isEmpty();
    assertThat(crossScope.nextCursor()).isPresent();
    assertThat(query.listAfter(organizationScope, Optional.of("malformed"), 20).refreshRequired())
        .isTrue();
  }

  @Test
  void deduplicatesRedeliveryAndSequencesConcurrentEventsInPostgres() throws Exception {
    ExecutionScope scope = organizationScope("SYN-ORG-001");
    String start = query.currentCursor(scope);
    EventEnvelope duplicate =
        event(
            "transfer.completed.v1",
            "transfer-journey/00000000-0000-0000-0000-000000000001",
            1,
            scope,
            "SYN-PATIENT-FEDERATION-PRIVATE-4B71");
    handler.handle(internalEvent(duplicate));
    handler.handle(internalEvent(duplicate));

    try (var executor = Executors.newFixedThreadPool(8)) {
      List<java.util.concurrent.Future<?>> pending = new ArrayList<>();
      for (int index = 2; index <= 9; index++) {
        int version = index;
        String id = String.format("00000000-0000-0000-0000-%012d", index);
        EventEnvelope event =
            event(
                "transfer.completed.v1",
                "transfer-journey/" + id,
                version,
                scope,
                "SYN-PATIENT-FEDERATION-PRIVATE-4B71");
        pending.add(executor.submit(() -> handler.handle(internalEvent(event))));
      }
      for (var future : pending) {
        future.get();
      }
    }

    NotificationProjectionPage replay = query.listAfter(scope, Optional.of(start), 20);
    assertThat(replay.refreshRequired()).isFalse();
    assertThat(replay.hints()).hasSize(9);
    assertThat(replay.hints())
        .extracting("sequence")
        .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_notification.event_receipt", Integer.class))
        .isEqualTo(9);
  }

  @Test
  void expiresOldCursorAndInvalidatesItAgainAfterReset() {
    ExecutionScope scope = organizationScope("SYN-ORG-001");
    String originalCursor = query.currentCursor(scope);
    handler.handle(
        internalEvent(
            event(
                "transfer.completed.v1",
                "transfer-journey/00000000-0000-0000-0000-000000000001",
                1,
                scope,
                "SYN-PATIENT-FEDERATION-PRIVATE-4B71")));

    clock.advance(Duration.ofHours(25));
    handler.handle(
        internalEvent(
            event(
                "transfer.completed.v1",
                "transfer-journey/00000000-0000-0000-0000-000000000002",
                2,
                scope,
                "SYN-PATIENT-FEDERATION-PRIVATE-4B71")));
    assertThat(query.listAfter(scope, Optional.of(originalCursor), 20).refreshRequired()).isTrue();

    String beforeReset = query.currentCursor(scope);
    query.resetProjection();
    assertThat(query.currentCursor(scope)).isNotEqualTo(beforeReset);
    assertThat(query.listAfter(scope, Optional.of(beforeReset), 20).refreshRequired()).isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_notification.notification_projection",
                Integer.class))
        .isZero();
  }

  @Test
  void recoversPersistedHintsAfterCreatingANewQueryAndEnforcesConfiguredScopeBound() {
    ExecutionScope scope = organizationScope("SYN-ORG-001");
    String start = query.currentCursor(scope);
    handler.handle(
        internalEvent(
            event(
                "transfer.completed.v1",
                "transfer-journey/00000000-0000-0000-0000-000000000001",
                1,
                scope,
                "SYN-PATIENT-FEDERATION-PRIVATE-4B71")));

    NotificationProjectionQuery restarted =
        new NotificationProjectionQuery(
            new JdbcNotificationProjectionStore(jdbcTemplate, properties, transactionManager),
            properties,
            clock);
    assertThat(restarted.listAfter(scope, Optional.of(start), 20).hints()).hasSize(1);

    query.currentCursor(organizationScope("SYN-ORG-002"));
    assertThatThrownBy(() -> query.currentCursor(organizationScope("SYN-ORG-003")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("notification projection scope capacity is exhausted");
  }

  private InternalEvent internalEvent(EventEnvelope event) {
    return new InternalEvent(
        event.eventId(),
        event.eventType(),
        event.producerContext(),
        event.aggregatePosition(),
        codec.encode(event));
  }

  private EventEnvelope event(
      String eventType,
      String resourceReference,
      long version,
      ExecutionScope scope,
      String marker) {
    EventType type = EventType.parse(eventType);
    var payload = objectMapper.createObjectNode();
    payload.put("coordinationReference", resourceReference);
    payload.put("privateSyntheticMarker", marker);
    payload.put("clinicalSummary", "Fictional text that must never be projected");
    var actor =
        ActorReference.human(
            new IssuerId("https://identity.example.test/realms/test"), "SYN-USER-001");
    var context =
        new ExecutionContextFactory((ignoredActor, ignoredScope) -> true)
            .forInteractiveRequest(
                actor, requestedScope(scope), new CorrelationId("notification-test-correlation"));
    return new EventEnvelope(
        InternalId.generate(),
        type,
        type.context(),
        new OccurredAt(BASE_TIME.minusSeconds(2)),
        new RecordedAt(BASE_TIME.minusSeconds(1)),
        context,
        Optional.of(new AggregatePosition(resourceReference, version)),
        payload);
  }

  private static ExecutionScope organizationScope(String value) {
    var organization =
        new ExternalReference(new IssuerId("urn:ayuvahak:directory"), "Organization", value);
    var context =
        new ExecutionContextFactory((ignoredActor, ignoredScope) -> true)
            .forInteractiveRequest(
                ActorReference.human(
                    new IssuerId("https://identity.example.test/realms/test"), "SYN-USER-001"),
                RequestedExecutionScope.organization(organization),
                CorrelationId.generate());
    return context.scope();
  }

  private static RequestedExecutionScope requestedScope(ExecutionScope scope) {
    return switch (scope.kind()) {
      case ORGANIZATION -> RequestedExecutionScope.organization(scope.organization().orElseThrow());
      case FACILITY ->
          RequestedExecutionScope.facility(
              scope.organization().orElseThrow(), scope.facility().orElseThrow());
      case PLATFORM -> RequestedExecutionScope.platform();
    };
  }

  @TestConfiguration(proxyBeanMethods = false)
  @Profile("notification-projection-postgres")
  static class TestBeans {

    @Bean
    @Primary
    @org.springframework.beans.factory.annotation.Qualifier("notificationClock")
    TestClock notificationTestClock() {
      return new TestClock(BASE_TIME);
    }
  }

  static final class TestClock extends Clock {

    private final AtomicReference<Instant> instant;

    TestClock(Instant initial) {
      instant = new AtomicReference<>(initial);
    }

    void set(Instant value) {
      instant.set(value);
    }

    void advance(Duration duration) {
      instant.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant.get();
    }
  }
}
