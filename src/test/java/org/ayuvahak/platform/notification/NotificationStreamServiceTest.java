package org.ayuvahak.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.notification.application.NotificationFeedAuthorizer;
import org.ayuvahak.platform.notification.application.NotificationFeedSink;
import org.ayuvahak.platform.notification.application.NotificationHint;
import org.ayuvahak.platform.notification.application.NotificationHintMessage;
import org.ayuvahak.platform.notification.application.NotificationHintReader;
import org.ayuvahak.platform.notification.application.NotificationProjectionPage;
import org.ayuvahak.platform.notification.application.NotificationStreamService;
import org.ayuvahak.platform.notification.configuration.NotificationStreamProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NotificationStreamServiceTest {

  private NotificationStreamService service;

  @AfterEach
  void stopWorkers() {
    if (service != null) {
      service.close();
    }
  }

  @Test
  void authorizedStreamSendsOnlyHintFieldsUsingTheAuthorizedScope() throws Exception {
    ExecutionScope scope = organizationScope("SYN-ORG-001");
    String firstCursor = cursor(1);
    String secondCursor = cursor(2);
    FakeReader reader = new FakeReader(firstCursor);
    reader.pages.add(
        new NotificationProjectionPage(
            List.of(hint("SYN-PATIENT-FEDERATION-PRIVATE-4B71", 3, 3, secondCursor)),
            Optional.of(secondCursor),
            false));
    reader.pages.add(emptyPage(secondCursor));
    FakeAuthorizer authorizer = new FakeAuthorizer(scope);
    NotificationStreamProperties properties = properties();
    service = new NotificationStreamService(reader, authorizer, properties, Clock.systemUTC());
    RecordingSink sink = new RecordingSink();

    service.subscribe(
        actor(),
        RequestedExecutionScope.organization(scope.organization().orElseThrow()),
        new CorrelationId("sse-test-correlation"),
        Optional.empty(),
        sink);

    assertThat(sink.awaitHint()).isTrue();
    NotificationHintMessage message = sink.hints.getFirst();
    assertThat(message.notificationType()).isEqualTo("transfer.changed");
    assertThat(message.resourceType()).isEqualTo("transfer");
    assertThat(message.resourceId())
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000003"));
    assertThat(message.ownerVersion()).isEqualTo(3);
    assertThat(message.cursor()).isEqualTo(secondCursor);
    assertThat(message.correlationId()).isEqualTo("sse-test-correlation");
    assertThat(message.toString()).doesNotContain("SYN-PATIENT-FEDERATION-PRIVATE-4B71");
    assertThat(
            Arrays.stream(NotificationHintMessage.class.getRecordComponents())
                .map(component -> component.getName()))
        .containsExactly(
            "notificationType",
            "resourceType",
            "resourceId",
            "ownerVersion",
            "cursor",
            "correlationId");
    assertThat(reader.requestedScopes).containsOnly(scope, scope);
    sink.close();
  }

  @Test
  void deliversTheSameCommittedHintToTwoAuthorizedClients() throws Exception {
    ExecutionScope scope = organizationScope("SYN-ORG-001");
    String committedCursor = cursor(2);
    NotificationHint hint = hint("SYN-PRIVATE-MARKER", 9, 17, committedCursor);
    NotificationHintReader reader =
        new NotificationHintReader() {
          @Override
          public String currentCursor(ExecutionScope ignoredScope) {
            return cursor(1);
          }

          @Override
          public NotificationProjectionPage listAfter(
              ExecutionScope ignoredScope, Optional<String> after, int limit) {
            if (after.filter(committedCursor::equals).isPresent()) {
              return emptyPage(committedCursor);
            }
            return new NotificationProjectionPage(
                List.of(hint), Optional.of(committedCursor), false);
          }
        };
    service =
        new NotificationStreamService(
            reader, new FakeAuthorizer(scope), properties(), Clock.systemUTC());
    RecordingSink firstClient = new RecordingSink();
    RecordingSink secondClient = new RecordingSink();
    RequestedExecutionScope requestedScope =
        RequestedExecutionScope.organization(scope.organization().orElseThrow());

    service.subscribe(
        actor("SYN-USER-001"),
        requestedScope,
        new CorrelationId("sse-first-authorized-client"),
        Optional.empty(),
        firstClient);
    service.subscribe(
        actor("SYN-USER-002"),
        requestedScope,
        new CorrelationId("sse-second-authorized-client"),
        Optional.empty(),
        secondClient);

    assertThat(firstClient.awaitHint()).isTrue();
    assertThat(secondClient.awaitHint()).isTrue();
    assertThat(firstClient.hints).containsExactly(secondClient.hints.getFirst());
    assertThat(firstClient.hints.getFirst().resourceId())
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000009"));
    assertThat(firstClient.hints.getFirst().ownerVersion()).isEqualTo(17);
  }

  @Test
  void federatedReceivingNotificationIsAReadHintNotAReceivingPayload() throws Exception {
    ExecutionScope scope = organizationScope("SYN-ORG-001");
    String startCursor = cursor(1);
    String nextCursor = cursor(2);
    FakeReader reader = new FakeReader(startCursor);
    Instant now = Instant.now();
    reader.pages.add(
        new NotificationProjectionPage(
            List.of(
                new NotificationHint(
                    "transfer.federated-receiving-request-changed.v1",
                    "federated-receiving-request",
                    UUID.fromString("00000000-0000-0000-0000-000000000011"),
                    4,
                    2,
                    nextCursor,
                    "federated-receiving-correlation",
                    now,
                    now,
                    now)),
            Optional.of(nextCursor),
            false));
    reader.pages.add(emptyPage(nextCursor));
    FakeAuthorizer authorizer = new FakeAuthorizer(scope);
    service = new NotificationStreamService(reader, authorizer, properties(), Clock.systemUTC());
    RecordingSink sink = new RecordingSink();

    service.subscribe(
        actor(),
        RequestedExecutionScope.organization(scope.organization().orElseThrow()),
        new CorrelationId("federated-receiving-sse-subscription"),
        Optional.empty(),
        sink);

    assertThat(sink.awaitHint()).isTrue();
    NotificationHintMessage message = sink.hints.getFirst();
    assertThat(message.notificationType()).isEqualTo("transfer.changed");
    assertThat(message.resourceType()).isEqualTo("federated-receiving-request");
    assertThat(message.resourceId())
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000011"));
    assertThat(message.ownerVersion()).isEqualTo(4);
    assertThat(message.cursor()).isEqualTo(nextCursor);
    assertThat(message.correlationId()).isEqualTo("federated-receiving-correlation");
    assertThat(
            Arrays.stream(NotificationHintMessage.class.getRecordComponents())
                .map(component -> component.getName()))
        .containsExactly(
            "notificationType",
            "resourceType",
            "resourceId",
            "ownerVersion",
            "cursor",
            "correlationId");
    assertThat(reader.requestedScopes).containsOnly(scope, scope);
    sink.close();
  }

  @Test
  void reauthorizesAnOpenStreamAndClosesItAfterGrantRevocation() throws Exception {
    ExecutionScope scope = organizationScope("SYN-ORG-001");
    FakeReader reader = new FakeReader(cursor(1));
    reader.pages.add(emptyPage(cursor(1)));
    FakeAuthorizer authorizer = new FakeAuthorizer(scope);
    authorizer.revokeAfterFirstAuthorization.set(true);
    NotificationStreamProperties properties = properties();
    properties.setReauthorizationInterval(Duration.ofMillis(15));
    properties.setPollInterval(Duration.ofMillis(5));
    service = new NotificationStreamService(reader, authorizer, properties, Clock.systemUTC());
    RecordingSink sink = new RecordingSink();

    service.subscribe(
        actor(),
        RequestedExecutionScope.organization(scope.organization().orElseThrow()),
        new CorrelationId("sse-revocation-correlation"),
        Optional.empty(),
        sink);

    assertThat(sink.awaitClose()).isTrue();
    assertThat(authorizer.authorizations.get()).isGreaterThanOrEqualTo(2);
    assertThat(authorizer.requestedScopes)
        .containsOnly(RequestedExecutionScope.organization(scope.organization().orElseThrow()));
    assertThat(sink.hints).isEmpty();
  }

  @Test
  void reauthorizesAndClosesIndependentlyWhileAClientWriteIsBlocked() throws Exception {
    ExecutionScope scope = organizationScope("SYN-ORG-001");
    FakeReader reader = new FakeReader(cursor(1));
    reader.pages.add(
        new NotificationProjectionPage(
            List.of(hint("SYN-PRIVATE-MARKER", 1, 1, cursor(2))), Optional.of(cursor(2)), false));
    FakeAuthorizer authorizer = new FakeAuthorizer(scope);
    authorizer.revokeAfterFirstAuthorization.set(true);
    NotificationStreamProperties properties = properties();
    properties.setReauthorizationInterval(Duration.ofMillis(20));
    service = new NotificationStreamService(reader, authorizer, properties, Clock.systemUTC());
    BlockingSink sink = new BlockingSink();

    service.subscribe(
        actor(),
        RequestedExecutionScope.organization(scope.organization().orElseThrow()),
        new CorrelationId("sse-blocked-write-revocation"),
        Optional.empty(),
        sink);

    assertThat(sink.writeStarted.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(sink.closeLatch.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(authorizer.authorizations.get()).isGreaterThanOrEqualTo(2);
    sink.releaseWrite.countDown();
  }

  @Test
  void expiredCursorProducesRefreshRequiredAndDoesNotReplayHints() throws Exception {
    ExecutionScope scope = organizationScope("SYN-ORG-001");
    String current = cursor(9);
    FakeReader reader = new FakeReader(current);
    reader.pages.add(new NotificationProjectionPage(List.of(), Optional.of(current), true));
    NotificationStreamProperties properties = properties();
    service =
        new NotificationStreamService(
            reader, new FakeAuthorizer(scope), properties, Clock.systemUTC());
    RecordingSink sink = new RecordingSink();

    service.subscribe(
        actor(),
        RequestedExecutionScope.organization(scope.organization().orElseThrow()),
        new CorrelationId("sse-expired-cursor"),
        Optional.of(cursor(1)),
        sink);

    assertThat(sink.awaitRefreshRequired()).isTrue();
    assertThat(sink.hints).isEmpty();
    assertThat(sink.refreshCursors).containsExactly(current);
    assertThat(sink.awaitClose()).isTrue();
  }

  @Test
  void coalescesSameResourceHintsAndRefreshesWhenDistinctHintsOverflowQueue() throws Exception {
    ExecutionScope scope = organizationScope("SYN-ORG-001");
    String baseline = cursor(1);
    String first = cursor(2);
    String second = cursor(3);
    FakeReader coalescingReader = new FakeReader(baseline);
    coalescingReader.pages.add(
        new NotificationProjectionPage(
            List.of(
                hint("SYN-PRIVATE-MARKER", 1, 1, first), hint("SYN-PRIVATE-MARKER", 1, 2, second)),
            Optional.of(second),
            false));
    NotificationStreamProperties coalescingProperties = properties();
    coalescingProperties.setMaximumQueueCount(1);
    service =
        new NotificationStreamService(
            coalescingReader, new FakeAuthorizer(scope), coalescingProperties, Clock.systemUTC());
    RecordingSink coalesced = new RecordingSink();
    service.subscribe(
        actor(),
        RequestedExecutionScope.organization(scope.organization().orElseThrow()),
        new CorrelationId("sse-coalesce"),
        Optional.empty(),
        coalesced);
    assertThat(coalesced.awaitHint()).isTrue();
    assertThat(coalesced.hints)
        .singleElement()
        .extracting(NotificationHintMessage::ownerVersion)
        .isEqualTo(2L);
    assertThat(coalesced.hints.getFirst().cursor()).isEqualTo(second);
    coalesced.close();
    service.close();

    String third = cursor(4);
    FakeReader overflowingReader = new FakeReader(baseline);
    overflowingReader.pages.add(
        new NotificationProjectionPage(
            List.of(
                hint("SYN-PRIVATE-MARKER", 1, 1, first), hint("SYN-PRIVATE-MARKER", 2, 1, third)),
            Optional.of(third),
            false));
    service =
        new NotificationStreamService(
            overflowingReader, new FakeAuthorizer(scope), coalescingProperties, Clock.systemUTC());
    RecordingSink overflow = new RecordingSink();
    service.subscribe(
        actor(),
        RequestedExecutionScope.organization(scope.organization().orElseThrow()),
        new CorrelationId("sse-overflow"),
        Optional.empty(),
        overflow);
    assertThat(overflow.awaitRefreshRequired()).isTrue();
    assertThat(overflow.hints).isEmpty();
    assertThat(overflow.awaitClose()).isTrue();
  }

  @Test
  void rejectsAnAdditionalConnectionWhenTheConfiguredBoundIsReached() throws Exception {
    ExecutionScope scope = organizationScope("SYN-ORG-001");
    FakeReader reader = new FakeReader(cursor(1));
    reader.pages.add(emptyPage(cursor(1)));
    NotificationStreamProperties properties = properties();
    properties.setMaximumConnections(1);
    properties.setMaximumConnectionsPerActor(1);
    service =
        new NotificationStreamService(
            reader, new FakeAuthorizer(scope), properties, Clock.systemUTC());
    RecordingSink first = new RecordingSink();
    RecordingSink second = new RecordingSink();
    var actor = actor();

    service.subscribe(
        actor,
        RequestedExecutionScope.organization(scope.organization().orElseThrow()),
        new CorrelationId("sse-first"),
        Optional.empty(),
        first);

    assertThatThrownBy(
            () ->
                service.subscribe(
                    actor,
                    RequestedExecutionScope.organization(scope.organization().orElseThrow()),
                    new CorrelationId("sse-second"),
                    Optional.empty(),
                    second))
        .isInstanceOf(org.ayuvahak.platform.api.PlatformProblemException.class)
        .satisfies(
            failure ->
                assertThat(((org.ayuvahak.platform.api.PlatformProblemException) failure).status())
                    .isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
    first.close();
  }

  private static NotificationStreamProperties properties() {
    NotificationStreamProperties properties = new NotificationStreamProperties();
    properties.setMaximumLifetime(Duration.ofSeconds(5));
    properties.setReauthorizationInterval(Duration.ofSeconds(1));
    properties.setPollInterval(Duration.ofMillis(20));
    properties.setHeartbeatInterval(Duration.ofSeconds(2));
    properties.setMaximumConnections(4);
    properties.setMaximumConnectionsPerActor(2);
    properties.setMaximumQueueCount(10);
    properties.setMaximumQueueBytes(65_536);
    return properties;
  }

  private static NotificationProjectionPage emptyPage(String cursor) {
    return new NotificationProjectionPage(List.of(), Optional.of(cursor), false);
  }

  private static NotificationHint hint(
      String ignoredPrivateMarker, long id, long version, String cursor) {
    Instant now = Instant.now();
    return new org.ayuvahak.platform.notification.application.NotificationHint(
        "transfer.completed.v1",
        "transfer",
        UUID.fromString(String.format("00000000-0000-0000-0000-%012d", id)),
        version,
        version,
        cursor,
        "sse-test-correlation",
        now,
        now,
        now);
  }

  private static String cursor(long sequence) {
    return String.format("00000000-0000-0000-0000-%012d", sequence);
  }

  private static ActorReference actor() {
    return actor("SYN-USER-001");
  }

  private static ActorReference actor(String id) {
    return ActorReference.human(new IssuerId("https://identity.example.test/realms/test"), id);
  }

  private static ExecutionScope organizationScope(String id) {
    ExternalReference organization =
        new ExternalReference(new IssuerId("urn:ayuvahak:directory"), "Organization", id);
    return new ExecutionContextFactory((ignoredActor, ignoredScope) -> true)
        .forInteractiveRequest(
            actor(), RequestedExecutionScope.organization(organization), CorrelationId.generate())
        .scope();
  }

  private static final class FakeReader implements NotificationHintReader {
    private final String currentCursor;
    private final List<NotificationProjectionPage> pages = new CopyOnWriteArrayList<>();
    private final List<ExecutionScope> requestedScopes = new CopyOnWriteArrayList<>();

    private FakeReader(String currentCursor) {
      this.currentCursor = currentCursor;
    }

    @Override
    public String currentCursor(ExecutionScope scope) {
      requestedScopes.add(scope);
      return currentCursor;
    }

    @Override
    public NotificationProjectionPage listAfter(
        ExecutionScope scope, Optional<String> cursor, int requestedLimit) {
      requestedScopes.add(scope);
      return pages.isEmpty() ? emptyPage(currentCursor) : pages.removeFirst();
    }
  }

  private static final class FakeAuthorizer implements NotificationFeedAuthorizer {
    private final ExecutionScope scope;
    private final AtomicInteger authorizations = new AtomicInteger();
    private final List<RequestedExecutionScope> requestedScopes = new CopyOnWriteArrayList<>();
    private final AtomicReference<Boolean> revokeAfterFirstAuthorization =
        new AtomicReference<>(false);

    private FakeAuthorizer(ExecutionScope scope) {
      this.scope = scope;
    }

    @Override
    public ExecutionScope authorize(
        ActorReference actor, RequestedExecutionScope requestedScope, CorrelationId correlationId) {
      int count = authorizations.incrementAndGet();
      requestedScopes.add(requestedScope);
      if (revokeAfterFirstAuthorization.get() && count > 1) {
        throw new SecurityException("grant revoked");
      }
      return scope;
    }
  }

  private static class RecordingSink implements NotificationFeedSink {
    private final List<NotificationHintMessage> hints = new CopyOnWriteArrayList<>();
    private final List<String> refreshCursors = new CopyOnWriteArrayList<>();
    private final CountDownLatch hintLatch = new CountDownLatch(1);
    private final CountDownLatch refreshLatch = new CountDownLatch(1);
    final CountDownLatch closeLatch = new CountDownLatch(1);
    private volatile Runnable closeHandler = () -> {};

    @Override
    public void sendHint(NotificationHintMessage hint) throws IOException {
      hints.add(hint);
      hintLatch.countDown();
    }

    @Override
    public void sendRefreshRequired(String cursor) {
      refreshCursors.add(cursor);
      refreshLatch.countDown();
    }

    @Override
    public void sendHeartbeat() {}

    @Override
    public void complete() {
      closeLatch.countDown();
    }

    @Override
    public void onClose(Runnable callback) {
      closeHandler = callback;
    }

    private boolean awaitHint() throws InterruptedException {
      return hintLatch.await(2, TimeUnit.SECONDS);
    }

    private boolean awaitRefreshRequired() throws InterruptedException {
      return refreshLatch.await(2, TimeUnit.SECONDS);
    }

    private boolean awaitClose() throws InterruptedException {
      return closeLatch.await(2, TimeUnit.SECONDS);
    }

    private void close() {
      closeHandler.run();
      closeLatch.countDown();
    }
  }

  private static final class BlockingSink extends RecordingSink {
    private final CountDownLatch writeStarted = new CountDownLatch(1);
    private final CountDownLatch releaseWrite = new CountDownLatch(1);

    @Override
    public void sendHint(NotificationHintMessage hint) throws IOException {
      writeStarted.countDown();
      try {
        if (!releaseWrite.await(2, TimeUnit.SECONDS)) {
          throw new IOException("synthetic blocked write timed out");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("synthetic blocked write interrupted", interrupted);
      }
      super.sendHint(hint);
    }
  }
}
