package org.ayuvahak.platform.notification.application;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.notification.configuration.NotificationStreamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Optional, bounded delivery of non-authoritative resource/version hints. */
@Service
public final class NotificationStreamService implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(NotificationStreamService.class);

  private final NotificationHintReader reader;
  private final NotificationFeedAuthorizer authorizer;
  private final NotificationStreamProperties properties;
  private final Clock clock;
  private final ThreadPoolExecutor workers;
  private final ScheduledThreadPoolExecutor reauthorizationWorkers;
  private final Object registryLock = new Object();
  private final Map<UUID, StreamSession> sessions = new HashMap<>();
  private final Map<ActorReference, Integer> connectionsByActor = new HashMap<>();
  private final AtomicBoolean accepting = new AtomicBoolean(true);

  public NotificationStreamService(
      NotificationHintReader reader,
      NotificationFeedAuthorizer authorizer,
      NotificationStreamProperties properties,
      @Qualifier("notificationClock") Clock clock) {
    this.reader = Objects.requireNonNull(reader, "reader");
    this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
    AtomicInteger threadNumber = new AtomicInteger();
    ThreadFactory threadFactory =
        task -> {
          Thread thread =
              new Thread(task, "ayuvahak-notification-stream-" + threadNumber.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        };
    int workerLimit = properties.getMaximumConnections();
    this.workers =
        new ThreadPoolExecutor(
            workerLimit,
            workerLimit,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(workerLimit),
            threadFactory,
            new ThreadPoolExecutor.AbortPolicy());
    AtomicInteger monitorNumber = new AtomicInteger();
    this.reauthorizationWorkers =
        new ScheduledThreadPoolExecutor(
            workerLimit,
            task -> {
              Thread thread =
                  new Thread(
                      task,
                      "ayuvahak-notification-reauthorization-" + monitorNumber.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
    this.reauthorizationWorkers.setRemoveOnCancelPolicy(true);
  }

  /** Opens one exact organization/facility subscription; cursor possession grants no access. */
  public void subscribe(
      ActorReference actor,
      RequestedExecutionScope requestedScope,
      CorrelationId correlationId,
      Optional<String> lastEventId,
      NotificationFeedSink sink) {
    Objects.requireNonNull(requestedScope, "requestedScope");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(lastEventId, "lastEventId");
    Objects.requireNonNull(sink, "sink");
    if (!accepting.get() || !properties.isEnabled()) {
      throw unavailable("The notification stream is not available.");
    }
    ExecutionScope scope = authorizer.authorize(actor, requestedScope, correlationId);
    NotificationScope notificationScope;
    try {
      notificationScope = NotificationScope.from(scope);
      if (notificationScope.kind() == ExecutionScope.Kind.PLATFORM) {
        throw new IllegalArgumentException("platform scope cannot subscribe to notifications");
      }
    } catch (IllegalArgumentException invalidScope) {
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "A validated organization or facility scope is required.");
    }

    StreamSession session =
        new StreamSession(
            UUID.randomUUID(),
            actor,
            requestedScope,
            correlationId,
            scope,
            notificationScope,
            lastEventId,
            sink,
            clock.instant());
    reserve(session);
    try {
      sink.onClose(session::requestStop);
      long intervalMillis = properties.getReauthorizationInterval().toMillis();
      session.reauthorizationTask =
          reauthorizationWorkers.scheduleWithFixedDelay(
              () -> reauthorize(session), intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    } catch (RuntimeException callbackRegistrationFailure) {
      finish(session);
      completeSink(session);
      throw unavailable("The notification stream could not be initialized.");
    }
    try {
      workers.execute(() -> run(session));
    } catch (RuntimeException rejected) {
      finish(session);
      throw unavailable("The notification stream capacity is full.");
    }
  }

  private void reserve(StreamSession session) {
    synchronized (registryLock) {
      int actorConnections = connectionsByActor.getOrDefault(session.actor, 0);
      if (sessions.size() >= properties.getMaximumConnections()
          || actorConnections >= properties.getMaximumConnectionsPerActor()) {
        throw new PlatformProblemException(
            HttpStatus.TOO_MANY_REQUESTS,
            PlatformErrorCode.REQUEST_FAILED,
            "The notification stream connection limit has been reached.");
      }
      sessions.put(session.id, session);
      connectionsByActor.put(session.actor, actorConnections + 1);
    }
  }

  private void run(StreamSession session) {
    boolean complete = false;
    try {
      String cursor = initializeCursor(session);
      if (cursor == null) {
        complete = true;
        return;
      }
      if (session.stopped.get()) {
        return;
      }
      Instant nextHeartbeat = clock.instant().plus(properties.getHeartbeatInterval());
      Instant expiresAt = session.openedAt.plus(properties.getMaximumLifetime());

      while (!session.stopped.get()) {
        Instant now = clock.instant();
        if (!now.isBefore(expiresAt)) {
          sendRefreshRequired(session, reader.currentCursor(session.scope));
          complete = true;
          return;
        }
        NotificationProjectionPage page = reader.listAfter(session.scope, Optional.of(cursor), 0);
        if (page.refreshRequired()) {
          sendRefreshRequired(
              session, page.nextCursor().orElseGet(() -> reader.currentCursor(session.scope)));
          complete = true;
          return;
        }

        if (!page.hints().isEmpty()) {
          List<NotificationHint> batch = boundedCoalescedBatch(page.hints());
          if (batch == null) {
            sendRefreshRequired(session, reader.currentCursor(session.scope));
            complete = true;
            return;
          }
          for (NotificationHint hint : batch) {
            if (session.stopped.get()) {
              return;
            }
            session.sink.sendHint(toMessage(hint));
            cursor = hint.cursor();
          }
        } else if (!now.isBefore(nextHeartbeat)) {
          session.sink.sendHeartbeat();
          nextHeartbeat = now.plus(properties.getHeartbeatInterval());
        }
        pause(properties.getPollInterval());
      }
    } catch (IOException disconnected) {
      // Spring's Servlet async lifecycle owns cleanup after a failed response write. Calling
      // completeWithError here would race that lifecycle and is not needed for a closed client.
      LOGGER.debug("Notification stream response closed");
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException unavailable) {
      // A feed read or grant lookup failure ends this optional channel; owner reads remain valid.
      LOGGER.debug("Notification stream ended after a local service failure");
      complete = true;
    } finally {
      session.requestStop();
      if (complete) {
        completeSink(session);
      }
      finish(session);
    }
  }

  /** Null means refresh-required was sent and the connection should end. */
  private String initializeCursor(StreamSession session) throws IOException {
    if (session.lastEventId.isEmpty()) {
      return reader.currentCursor(session.scope);
    }
    NotificationProjectionPage replay = reader.listAfter(session.scope, session.lastEventId, 0);
    if (replay.refreshRequired()) {
      sendRefreshRequired(
          session, replay.nextCursor().orElseGet(() -> reader.currentCursor(session.scope)));
      return null;
    }
    if (!replay.hints().isEmpty()) {
      List<NotificationHint> batch = boundedCoalescedBatch(replay.hints());
      if (batch == null) {
        sendRefreshRequired(session, reader.currentCursor(session.scope));
        return null;
      }
      String cursor = session.lastEventId.orElseThrow();
      for (NotificationHint hint : batch) {
        if (session.stopped.get()) {
          return null;
        }
        session.sink.sendHint(toMessage(hint));
        cursor = hint.cursor();
      }
      return cursor;
    }
    return replay.nextCursor().orElseGet(() -> session.lastEventId.orElseThrow());
  }

  private boolean isStillAuthorized(StreamSession session) {
    try {
      ExecutionScope current =
          authorizer.authorize(session.actor, session.requestedScope, session.correlationId);
      return session.scope.equals(current)
          && session.notificationScope.equals(NotificationScope.from(current));
    } catch (RuntimeException deniedOrUnavailable) {
      return false;
    }
  }

  /** Reauthorization runs independently of blocking SSE writes so a slow client cannot defer it. */
  private void reauthorize(StreamSession session) {
    if (!session.stopped.get() && !isStillAuthorized(session)) {
      session.requestStop();
      completeSink(session);
    }
  }

  private void sendRefreshRequired(StreamSession session, String cursor) throws IOException {
    if (!session.stopped.get()) {
      session.sink.sendRefreshRequired(cursor);
    }
  }

  private List<NotificationHint> boundedCoalescedBatch(List<NotificationHint> hints) {
    Map<ResourceKey, NotificationHint> latestByResource = new LinkedHashMap<>();
    long estimatedBytes = 0;
    for (NotificationHint hint : hints) {
      ResourceKey key = new ResourceKey(hint.resourceType(), hint.resourceId());
      NotificationHint previous = latestByResource.get(key);
      if (previous != null) {
        if (hint.sequence() > previous.sequence()) {
          estimatedBytes += hint.estimatedJsonBytes() - previous.estimatedJsonBytes();
          latestByResource.put(key, hint);
        }
      } else {
        estimatedBytes += hint.estimatedJsonBytes();
        latestByResource.put(key, hint);
      }
      if (latestByResource.size() > properties.getMaximumQueueCount()
          || estimatedBytes > properties.getMaximumQueueBytes()) {
        return null;
      }
    }
    return latestByResource.values().stream()
        .sorted(Comparator.comparingLong(NotificationHint::sequence))
        .toList();
  }

  private static NotificationHintMessage toMessage(NotificationHint hint) {
    return new NotificationHintMessage(
        notificationType(hint.resourceType()),
        hint.resourceType(),
        hint.resourceId(),
        hint.ownerVersion(),
        hint.cursor(),
        hint.correlationId());
  }

  private static String notificationType(String resourceType) {
    return switch (resourceType) {
      case "referral" -> "referral.changed";
      case "transfer" -> "transfer.changed";
      case "federated-receiving-request" -> "transfer.changed";
      case "capacity-reservation" -> "capacity.changed";
      case "transport", "transport-cancellation", "transport-destination-change" ->
          "transport.changed";
      default -> throw new IllegalArgumentException("resource type has no public hint mapping");
    };
  }

  private static void pause(Duration duration) throws InterruptedException {
    long millis = Math.max(1, duration.toMillis());
    Thread.sleep(millis);
  }

  private void completeSink(StreamSession session) {
    try {
      session.sink.complete();
    } catch (RuntimeException ignored) {
      LOGGER.debug("Notification stream completion callback failed");
    }
  }

  private void finish(StreamSession session) {
    if (!session.finished.compareAndSet(false, true)) {
      return;
    }
    synchronized (registryLock) {
      sessions.remove(session.id);
      ScheduledFuture<?> reauthorizationTask = session.reauthorizationTask;
      if (reauthorizationTask != null) {
        reauthorizationTask.cancel(false);
      }
      int remaining = connectionsByActor.getOrDefault(session.actor, 0) - 1;
      if (remaining <= 0) {
        connectionsByActor.remove(session.actor);
      } else {
        connectionsByActor.put(session.actor, remaining);
      }
    }
  }

  private static PlatformProblemException unavailable(String detail) {
    return new PlatformProblemException(
        HttpStatus.SERVICE_UNAVAILABLE, PlatformErrorCode.EVENT_DELIVERY_UNAVAILABLE, detail);
  }

  @PreDestroy
  @Override
  public void close() {
    if (!accepting.compareAndSet(true, false)) {
      return;
    }
    List<StreamSession> active;
    synchronized (registryLock) {
      active = new ArrayList<>(sessions.values());
    }
    active.forEach(
        session -> {
          session.requestStop();
          completeSink(session);
        });
    workers.shutdownNow();
    reauthorizationWorkers.shutdownNow();
  }

  private record ResourceKey(String resourceType, UUID resourceId) {}

  private static final class StreamSession {

    private final UUID id;
    private final ActorReference actor;
    private final RequestedExecutionScope requestedScope;
    private final CorrelationId correlationId;
    private final ExecutionScope scope;
    private final NotificationScope notificationScope;
    private final Optional<String> lastEventId;
    private final NotificationFeedSink sink;
    private final Instant openedAt;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile ScheduledFuture<?> reauthorizationTask;

    private StreamSession(
        UUID id,
        ActorReference actor,
        RequestedExecutionScope requestedScope,
        CorrelationId correlationId,
        ExecutionScope scope,
        NotificationScope notificationScope,
        Optional<String> lastEventId,
        NotificationFeedSink sink,
        Instant openedAt) {
      this.id = id;
      this.actor = Objects.requireNonNull(actor, "actor");
      this.requestedScope = requestedScope;
      this.correlationId = correlationId;
      this.scope = scope;
      this.notificationScope = notificationScope;
      this.lastEventId = lastEventId;
      this.sink = sink;
      this.openedAt = openedAt;
    }

    private void requestStop() {
      stopped.set(true);
    }
  }
}
