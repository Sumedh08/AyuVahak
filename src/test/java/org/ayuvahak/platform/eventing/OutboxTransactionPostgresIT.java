package org.ayuvahak.platform.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventEnvelopeJsonCodec;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.DeliveryFailureResult;
import org.ayuvahak.platform.eventing.application.DispatchReport;
import org.ayuvahak.platform.eventing.application.EventDeliveryOperationsStore;
import org.ayuvahak.platform.eventing.application.EventHandlerRegistry;
import org.ayuvahak.platform.eventing.application.InternalEvent;
import org.ayuvahak.platform.eventing.application.InternalEventHandler;
import org.ayuvahak.platform.eventing.application.LeasedEvent;
import org.ayuvahak.platform.eventing.application.OutboxDispatcher;
import org.ayuvahak.platform.eventing.application.OutboxStore;
import org.ayuvahak.platform.eventing.application.OutboxWriter;
import org.ayuvahak.platform.eventing.configuration.OutboxProperties;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
      "ayuvahak.eventing.outbox.batch-size=1",
      "ayuvahak.eventing.outbox.delivered-event-retention=PT24H"
    })
@Import(OutboxTransactionPostgresIT.TestBeans.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxTransactionPostgresIT {

  private static final String TEST_PASSWORD = "local-test-only";

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

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OutboxWriter outboxWriter;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private Flyway flyway;
  @Autowired private OutboxStore outboxStore;
  @Autowired private EventDeliveryOperationsStore deliveryOperationsStore;
  @Autowired private OutboxDispatcher dispatcher;
  @Autowired private EventHandlerRegistry handlerRegistry;
  @Autowired private OutboxProperties outboxProperties;
  @Autowired private EventEnvelopeJsonCodec eventEnvelopeJsonCodec;
  @Autowired private ScheduledExecutorService outboxLeaseRenewalExecutor;
  @Autowired private MutableClock clock;
  @Autowired private DeterministicHandler handler;

  @BeforeEach
  void createTestOnlyOwnerFixture() {
    jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS test_outbox_owner");
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS test_outbox_owner.synthetic_fact "
            + "(fact_id uuid PRIMARY KEY)");
    jdbcTemplate.update("DELETE FROM test_outbox_owner.synthetic_fact");
    jdbcTemplate.update("DELETE FROM platform_eventing.delivery_command_receipt");
    jdbcTemplate.update("DELETE FROM platform_eventing.outbox_delivery");
    jdbcTemplate.update("DELETE FROM platform_eventing.internal_event");
    clock.set(Instant.parse("2026-10-08T00:00:00Z"));
    handler.reset();
  }

  @Test
  void createsOnlyEventingOwnedTablesInItsMigrations() {
    assertThat(flyway.info().applied())
        .extracting(migration -> migration.getVersion().getVersion())
        .contains("4");
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = 'platform_eventing' AND table_type = 'BASE TABLE'",
                String.class))
        .containsExactlyInAnyOrder("internal_event", "outbox_delivery", "delivery_command_receipt");
  }

  @Test
  void owningFactAndPublicationIntentCommitOrRollbackTogether() {
    InternalId committedId = InternalId.generate();
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        status -> {
          jdbcTemplate.update(
              "INSERT INTO test_outbox_owner.synthetic_fact (fact_id) VALUES (?)",
              committedId.value());
          outboxWriter.record(event(committedId, 1));
        });

    assertThat(factCount(committedId)).isEqualTo(1);
    assertThat(eventCount(committedId)).isEqualTo(1);
    assertThat(deliveryState(committedId)).isEqualTo("PENDING");

    InternalId rolledBackId = InternalId.generate();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      jdbcTemplate.update(
                          "INSERT INTO test_outbox_owner.synthetic_fact (fact_id) VALUES (?)",
                          rolledBackId.value());
                      outboxWriter.record(event(rolledBackId, 2));
                      throw new DeliberateRollback();
                    }))
        .isInstanceOf(DeliberateRollback.class);

    assertThat(factCount(rolledBackId)).isZero();
    assertThat(eventCount(rolledBackId)).isZero();
  }

  @Test
  void deliversAnEventAndMarksOnlyInternalAcceptanceAsDelivered() {
    EventEnvelope event = event(InternalId.generate(), 1, "grant/deliver-1", createdEventType());
    record(event);

    DispatchReport result = dispatcher.dispatchAvailable(clock.instant());

    assertThat(result.delivered()).isEqualTo(1);
    assertThat(deliveryState(event.eventId())).isEqualTo("DELIVERED");
    assertThat(handler.handled())
        .singleElement()
        .satisfies(
            delivered -> {
              assertThat(delivered.eventId()).isEqualTo(event.eventId());
              assertThat(delivered.eventType()).isEqualTo(createdEventType());
            });
  }

  @Test
  void purgesOnlyDeliveredEventsAfterExplicitRetention() {
    EventEnvelope stalled =
        event(InternalId.generate(), 1, "grant/retention-stalled", createdEventType());
    record(stalled);
    handler.failNext(10);
    for (int attempt = 1; attempt <= 10; attempt++) {
      if (attempt > 1) {
        clock.advance(Duration.ofMinutes(5));
      }
      dispatcher.dispatchAvailable(clock.instant());
    }
    assertThat(deliveryState(stalled.eventId())).isEqualTo("STALLED");

    EventEnvelope delivered =
        event(InternalId.generate(), 1, "grant/retention-delivered", createdEventType());
    EventEnvelope unhandled =
        event(InternalId.generate(), 1, "grant/retention-unhandled", revokedEventType());
    record(delivered);
    record(unhandled);
    dispatcher.dispatchAvailable(clock.instant());
    dispatcher.dispatchAvailable(clock.instant());
    dispatcher.dispatchAvailable(clock.instant());
    assertThat(deliveryState(delivered.eventId())).isEqualTo("DELIVERED");
    assertThat(deliveryState(unhandled.eventId())).isEqualTo("UNHANDLED");

    jdbcTemplate.update(
        "INSERT INTO platform_eventing.delivery_command_receipt "
            + "(scope_digest, idempotency_key_digest, request_fingerprint, receipt_state, "
            + "response_status, event_id, delivery_generation, recorded_at) "
            + "VALUES (?, ?, ?, 'COMPLETED', 202, ?, 1, ?)",
        "a".repeat(64),
        "b".repeat(64),
        "c".repeat(64),
        delivered.eventId().value(),
        java.sql.Timestamp.from(clock.instant()));

    clock.advance(Duration.ofHours(48));
    dispatcher.dispatchAvailable(clock.instant());

    assertThat(eventCount(delivered.eventId())).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_eventing.delivery_command_receipt WHERE event_id = ?",
                Integer.class,
                delivered.eventId().value()))
        .isZero();
    assertThat(eventCount(unhandled.eventId())).isEqualTo(1);
    assertThat(deliveryState(unhandled.eventId())).isEqualTo("UNHANDLED");
    assertThat(eventCount(stalled.eventId())).isEqualTo(1);
    assertThat(deliveryState(stalled.eventId())).isEqualTo("STALLED");
  }

  @Test
  void unhandledEarlierEventBlocksLaterEventsForItsAggregate() {
    EventEnvelope first = event(InternalId.generate(), 1, "grant/ordered-1", revokedEventType());
    EventEnvelope second = event(InternalId.generate(), 2, "grant/ordered-1", createdEventType());
    record(first);
    record(second);

    DispatchReport firstPoll = dispatcher.dispatchAvailable(clock.instant());
    DispatchReport nextPoll = dispatcher.dispatchAvailable(clock.instant());

    assertThat(firstPoll.unhandled()).isEqualTo(1);
    assertThat(nextPoll).isEqualTo(new DispatchReport(0, 0, 0, 0, 0));
    assertThat(deliveryState(first.eventId())).isEqualTo("UNHANDLED");
    assertThat(deliveryState(second.eventId())).isEqualTo("PENDING");
    assertThat(attemptCount(first.eventId())).isZero();
  }

  @Test
  void declaredFutureConsumerWaitsWithoutAttemptsAndBlocksOnlyItsAggregate() {
    EventEnvelope waiting =
        event(
            InternalId.generate(),
            3,
            "referral/synthetic-referral",
            EventType.parse("referral.cancelled.v1"));
    EventEnvelope orderedAfter =
        event(
            InternalId.generate(),
            4,
            "referral/synthetic-referral",
            EventType.parse("referral.submitted.v1"));
    EventEnvelope independent =
        event(InternalId.generate(), 1, "grant/waiting-independent", createdEventType());
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> outboxWriter.recordForDeclaredFutureConsumer(waiting));
    record(orderedAfter);
    record(independent);

    DispatchReport firstPoll = dispatcher.dispatchAvailable(clock.instant());
    DispatchReport secondPoll = dispatcher.dispatchAvailable(clock.instant());

    assertThat(firstPoll.delivered()).isEqualTo(1);
    assertThat(secondPoll).isEqualTo(new DispatchReport(0, 0, 0, 0, 0));
    assertThat(deliveryState(waiting.eventId())).isEqualTo("WAITING_FOR_CONSUMER");
    assertThat(attemptCount(waiting.eventId())).isZero();
    assertThat(lastFailureCategory(waiting.eventId())).isNull();
    assertThat(deliveryState(orderedAfter.eventId())).isEqualTo("PENDING");
    assertThat(deliveryState(independent.eventId())).isEqualTo("DELIVERED");
    assertThat(deliveryOperationsStore.replay(waiting.eventId(), 1, clock.instant())).isEmpty();
    assertThat(deliveryOperationsStore.listCurrent(Optional.empty(), 100))
        .anySatisfy(summary -> assertThat(summary.state()).isEqualTo("WAITING_FOR_CONSUMER"));
  }

  @Test
  void retriesWithBoundedBackoffAndStallsAtConfiguredAttemptLimit() {
    EventEnvelope event = event(InternalId.generate(), 1, "grant/retry-1", createdEventType());
    EventEnvelope laterEvent = event(InternalId.generate(), 2, "grant/retry-1", createdEventType());
    record(event);
    record(laterEvent);
    handler.failNext(10);

    for (int attempt = 1; attempt <= 10; attempt++) {
      if (attempt > 1) {
        clock.advance(Duration.ofMinutes(5));
      }
      DispatchReport result = dispatcher.dispatchAvailable(clock.instant());
      if (attempt < 10) {
        assertThat(result.retryScheduled()).isEqualTo(1);
      } else {
        assertThat(result.stalled()).isEqualTo(1);
      }
      if (attempt == 1) {
        assertThat(dispatcher.dispatchAvailable(clock.instant()))
            .isEqualTo(new DispatchReport(0, 0, 0, 0, 0));
        assertThat(deliveryState(laterEvent.eventId())).isEqualTo("PENDING");
      }
    }

    assertThat(deliveryState(event.eventId())).isEqualTo("STALLED");
    assertThat(dispatcher.dispatchAvailable(clock.instant()))
        .isEqualTo(new DispatchReport(0, 0, 0, 0, 0));
    assertThat(deliveryState(laterEvent.eventId())).isEqualTo("PENDING");
    assertThat(attemptCount(event.eventId())).isEqualTo(10);
    assertThat(lastFailureCategory(event.eventId())).isEqualTo("HANDLER_FAILURE");
    assertThat(handler.calls()).isEqualTo(10);
  }

  @Test
  void expiredLeaseCanBeReclaimedAndCannotBeFinalizedByItsOldHolder() {
    EventEnvelope event = event(InternalId.generate(), 1, "grant/lease-1", createdEventType());
    record(event);
    Instant firstClaimAt = clock.instant();
    LeasedEvent firstClaim =
        outboxStore.claimDue(firstClaimAt, Duration.ofSeconds(60), 10, 1).getFirst();
    assertThat(outboxStore.beginAttempt(firstClaim.leaseToken(), firstClaimAt, 10)).isEqualTo(1);

    Instant afterExpiration = firstClaimAt.plusSeconds(61);
    clock.set(afterExpiration);
    LeasedEvent recovered =
        outboxStore.claimDue(afterExpiration, Duration.ofSeconds(60), 10, 1).getFirst();

    assertThat(recovered.leaseToken().leaseGeneration())
        .isEqualTo(firstClaim.leaseToken().leaseGeneration() + 1);
    assertThat(outboxStore.markDelivered(firstClaim.leaseToken(), afterExpiration)).isFalse();
    assertThat(
            outboxStore.renewLease(
                firstClaim.leaseToken(), afterExpiration, Duration.ofSeconds(60)))
        .isFalse();
    assertThat(
            outboxStore.markFailed(
                firstClaim.leaseToken(), afterExpiration, afterExpiration.plusSeconds(1), 10))
        .isEqualTo(DeliveryFailureResult.LEASE_LOST);
    assertThat(outboxStore.beginAttempt(recovered.leaseToken(), afterExpiration, 10)).isEqualTo(2);
    assertThat(outboxStore.markDelivered(recovered.leaseToken(), afterExpiration)).isTrue();
  }

  @Test
  void renewalExtendsTheLeaseAndPreventsPrematureReclaim() {
    EventEnvelope event = event(InternalId.generate(), 1, "grant/renew-1", createdEventType());
    record(event);
    Instant claimedAt = clock.instant();
    LeasedEvent lease = outboxStore.claimDue(claimedAt, Duration.ofSeconds(60), 10, 1).getFirst();
    assertThat(outboxStore.beginAttempt(lease.leaseToken(), claimedAt, 10)).isEqualTo(1);

    Instant renewalAt = claimedAt.plusSeconds(30);
    assertThat(outboxStore.renewLease(lease.leaseToken(), renewalAt, Duration.ofSeconds(60)))
        .isTrue();
    assertThat(outboxStore.claimDue(claimedAt.plusSeconds(61), Duration.ofSeconds(60), 10, 1))
        .isEmpty();
    assertThat(outboxStore.claimDue(claimedAt.plusSeconds(91), Duration.ofSeconds(60), 10, 1))
        .hasSize(1);
  }

  @Test
  void lostAcknowledgementAfterHandlerEffectCanRedeliverAtLeastOnce() throws Exception {
    EventEnvelope event = event(InternalId.generate(), 1, "grant/redelivery-1", createdEventType());
    record(event);
    Instant firstClaimAt = clock.instant();
    LeasedEvent firstClaim =
        outboxStore.claimDue(firstClaimAt, Duration.ofSeconds(60), 10, 1).getFirst();
    assertThat(outboxStore.beginAttempt(firstClaim.leaseToken(), firstClaimAt, 10)).isEqualTo(1);
    handler.handle(firstClaim.event());
    assertThat(handler.handled()).hasSize(1);
    // Simulate a process exit after the consumer effect but before the durable delivery ack.

    Instant afterExpiration = firstClaimAt.plusSeconds(61);
    clock.set(afterExpiration);
    DispatchReport recovered = dispatcher.dispatchAvailable(afterExpiration);

    assertThat(recovered.delivered()).isEqualTo(1);
    assertThat(handler.handled()).hasSize(2);
    assertThat(deliveryState(event.eventId())).isEqualTo("DELIVERED");
  }

  @Test
  void separateAggregatesProgressConcurrentlyWhileSameAggregateOrderIsPreserved() throws Exception {
    EventEnvelope orderedFirst =
        event(InternalId.generate(), 1, "grant/concurrent-1", createdEventType());
    EventEnvelope orderedSecond =
        event(InternalId.generate(), 2, "grant/concurrent-1", createdEventType());
    EventEnvelope independent =
        event(InternalId.generate(), 1, "grant/concurrent-2", createdEventType());
    record(orderedFirst);
    record(orderedSecond);
    record(independent);
    handler.blockNextCall();

    try (var workers = Executors.newFixedThreadPool(2)) {
      var firstWorker = workers.submit(() -> dispatcher.dispatchAvailable(clock.instant()));
      assertThat(handler.awaitBlocked()).isTrue();
      DispatchReport secondWorker = dispatcher.dispatchAvailable(clock.instant());
      assertThat(secondWorker.delivered()).isEqualTo(1);
      assertThat(deliveryState(orderedSecond.eventId())).isEqualTo("PENDING");
      handler.releaseBlockedCall();
      assertThat(firstWorker.get(5, TimeUnit.SECONDS).delivered()).isEqualTo(1);
    }
  }

  @Test
  void duplicateReplicaWorkersCannotClaimTheSameLiveOutboxLease() throws Exception {
    EventEnvelope event = event(InternalId.generate(), 1, "grant/two-replicas", createdEventType());
    record(event);
    handler.blockNextCall();
    OutboxDispatcher secondReplicaDispatcher =
        new OutboxDispatcher(
            outboxStore,
            handlerRegistry,
            eventEnvelopeJsonCodec,
            outboxProperties,
            clock,
            outboxLeaseRenewalExecutor);

    try (var workers = Executors.newFixedThreadPool(2)) {
      var firstReplica = workers.submit(() -> dispatcher.dispatchAvailable(clock.instant()));
      assertThat(handler.awaitBlocked()).isTrue();

      DispatchReport secondReplica = secondReplicaDispatcher.dispatchAvailable(clock.instant());

      assertThat(secondReplica).isEqualTo(new DispatchReport(0, 0, 0, 0, 0));
      assertThat(deliveryState(event.eventId())).isEqualTo("LEASED");
      handler.releaseBlockedCall();

      assertThat(firstReplica.get(5, TimeUnit.SECONDS).delivered()).isEqualTo(1);
    } finally {
      handler.releaseBlockedCall();
    }

    assertThat(handler.calls()).isEqualTo(1);
    assertThat(deliveryState(event.eventId())).isEqualTo("DELIVERED");
  }

  private EventEnvelope event(InternalId id, long sequence) {
    return event(id, sequence, "grant/synthetic-" + sequence, createdEventType());
  }

  private EventEnvelope event(InternalId id, long sequence, String aggregateRef, EventType type) {
    ExecutionContext context =
        new ExecutionContextFactory((actor, scope) -> true)
            .forInteractiveRequest(
                ActorReference.human(new IssuerId("synthetic-issuer"), "synthetic-operator"),
                RequestedExecutionScope.platform(),
                new CorrelationId("synthetic-correlation"));
    var payload = objectMapper.createObjectNode();
    payload.put("grantReference", "synthetic-grant-" + sequence);
    return new EventEnvelope(
        id,
        type,
        type.context(),
        new OccurredAt(Instant.parse("2026-10-08T00:00:00Z")),
        new RecordedAt(Instant.parse("2026-10-08T00:00:01Z")),
        context,
        Optional.of(new AggregatePosition(aggregateRef, sequence)),
        payload);
  }

  private void record(EventEnvelope event) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> outboxWriter.record(event));
  }

  private int factCount(InternalId id) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM test_outbox_owner.synthetic_fact WHERE fact_id = ?",
        Integer.class,
        id.value());
  }

  private int eventCount(InternalId id) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM platform_eventing.internal_event WHERE event_id = ?",
        Integer.class,
        id.value());
  }

  private String deliveryState(InternalId id) {
    return jdbcTemplate.queryForObject(
        "SELECT state FROM platform_eventing.outbox_delivery WHERE event_id = ?",
        String.class,
        id.value());
  }

  private int attemptCount(InternalId id) {
    return jdbcTemplate.queryForObject(
        "SELECT attempt_count FROM platform_eventing.outbox_delivery WHERE event_id = ?",
        Integer.class,
        id.value());
  }

  private String lastFailureCategory(InternalId id) {
    return jdbcTemplate.queryForObject(
        "SELECT last_failure_category FROM platform_eventing.outbox_delivery WHERE event_id = ?",
        String.class,
        id.value());
  }

  private EventType createdEventType() {
    return EventType.parse("identity-access.grant-created.v1");
  }

  private EventType revokedEventType() {
    return EventType.parse("identity-access.grant-revoked.v1");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestBeans {

    @Bean
    @Primary
    MutableClock eventingTestClock() {
      return new MutableClock(Instant.parse("2026-10-08T00:00:00Z"));
    }

    @Bean
    DeterministicHandler deterministicOutboxHandler() {
      return new DeterministicHandler();
    }

    @Bean
    @Primary
    EventHandlerRegistry outboxTestHandlerRegistry(DeterministicHandler handler) {
      // This Phase 2 test models the original declared-future-consumer state before Transfer's
      // Phase 7 handler is installed; keep its handler catalog explicit and deterministic.
      return new EventHandlerRegistry(List.of(handler));
    }
  }

  static final class MutableClock extends Clock {

    private final AtomicReference<Instant> instant;

    MutableClock(Instant initial) {
      instant = new AtomicReference<>(initial);
    }

    void set(Instant value) {
      instant.set(value);
    }

    void advance(Duration amount) {
      instant.updateAndGet(current -> current.plus(amount));
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

  static final class DeterministicHandler implements InternalEventHandler {

    private final AtomicInteger failuresRemaining = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    private final java.util.concurrent.ConcurrentLinkedQueue<InternalEvent> handled =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final AtomicBoolean blockNext = new AtomicBoolean();
    private volatile CountDownLatch blocked = new CountDownLatch(0);
    private volatile CountDownLatch release = new CountDownLatch(0);

    @Override
    public Set<EventType> supportedEventTypes() {
      return Set.of(EventType.parse("identity-access.grant-created.v1"));
    }

    @Override
    public void handle(InternalEvent event) throws Exception {
      calls.incrementAndGet();
      if (blockNext.compareAndSet(true, false)) {
        blocked.countDown();
        release.await(5, TimeUnit.SECONDS);
      }
      if (failuresRemaining.getAndUpdate(current -> Math.max(current - 1, 0)) > 0) {
        throw new IllegalStateException("synthetic handler failure details are not persisted");
      }
      handled.add(event);
    }

    void failNext(int count) {
      failuresRemaining.set(count);
    }

    int calls() {
      return calls.get();
    }

    java.util.List<InternalEvent> handled() {
      return java.util.List.copyOf(handled);
    }

    void blockNextCall() {
      blocked = new CountDownLatch(1);
      release = new CountDownLatch(1);
      blockNext.set(true);
    }

    boolean awaitBlocked() throws InterruptedException {
      return blocked.await(5, TimeUnit.SECONDS);
    }

    void releaseBlockedCall() {
      release.countDown();
    }

    void reset() {
      failuresRemaining.set(0);
      calls.set(0);
      handled.clear();
      blockNext.set(false);
      blocked = new CountDownLatch(0);
      release = new CountDownLatch(0);
    }
  }

  private static final class DeliberateRollback extends RuntimeException {}
}
