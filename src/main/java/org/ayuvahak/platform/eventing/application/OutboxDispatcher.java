package org.ayuvahak.platform.eventing.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.ayuvahak.platform.event.EventEnvelopeJsonCodec;
import org.ayuvahak.platform.eventing.configuration.OutboxProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** Delivers due internal events with at-least-once semantics and fenced PostgreSQL leases. */
@Service
public final class OutboxDispatcher {

  private final OutboxStore store;
  private final EventHandlerRegistry handlers;
  private final EventEnvelopeJsonCodec codec;
  private final OutboxProperties properties;
  private final Clock clock;
  private final ScheduledExecutorService leaseRenewalExecutor;

  public OutboxDispatcher(
      @Lazy OutboxStore store,
      EventHandlerRegistry handlers,
      EventEnvelopeJsonCodec codec,
      OutboxProperties properties,
      Clock outboxClock,
      ScheduledExecutorService outboxLeaseRenewalExecutor) {
    this.store = Objects.requireNonNull(store, "store");
    this.handlers = Objects.requireNonNull(handlers, "handlers");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(outboxClock, "outboxClock");
    this.leaseRenewalExecutor =
        Objects.requireNonNull(outboxLeaseRenewalExecutor, "outboxLeaseRenewalExecutor");
  }

  public DispatchReport dispatchAvailable() {
    return dispatchAvailable(clock.instant());
  }

  /** Exposed to the local recovery harness so due work can be driven with a controlled instant. */
  public DispatchReport dispatchAvailable(Instant now) {
    Objects.requireNonNull(now, "now");
    store.purgeDeliveredBefore(
        now.minus(properties.getDeliveredEventRetention()), properties.getBatchSize());
    DispatchReport report = new DispatchReport(0, 0, 0, 0, 0);
    for (int index = 0; index < properties.getBatchSize(); index++) {
      Instant claimTime = index == 0 ? now : clock.instant();
      List<LeasedEvent> due =
          store.claimDue(
              claimTime, properties.getLeaseDuration(), properties.getMaximumAttempts(), 1);
      if (due.isEmpty()) {
        break;
      }
      report = report.plus(deliver(due.getFirst()));
    }
    return report;
  }

  private DispatchReport deliver(LeasedEvent leasedEvent) {
    var token = leasedEvent.leaseToken();
    var event = leasedEvent.event();
    List<InternalEventHandler> eventHandlers = handlers.handlersFor(event.eventType());
    if (eventHandlers.isEmpty()) {
      return store.markUnhandled(token, clock.instant())
          ? new DispatchReport(0, 0, 0, 1, 0)
          : leaseLost();
    }

    int attempt = store.beginAttempt(token, clock.instant(), properties.getMaximumAttempts());
    if (attempt == 0) {
      return leaseLost();
    }

    AtomicBoolean leaseLost = new AtomicBoolean();
    ScheduledFuture<?> heartbeat = scheduleRenewal(token, leaseLost);
    try {
      for (InternalEventHandler handler : eventHandlers) {
        codec.requireCompatible(event.envelopeJson(), handler.supportedEventTypes());
        handler.handle(event);
      }
      if (leaseLost.get() || !store.markDelivered(token, clock.instant())) {
        return leaseLost();
      }
      return new DispatchReport(1, 0, 0, 0, 0);
    } catch (Exception failure) {
      if (leaseLost.get()) {
        return leaseLost();
      }
      Duration delay = retryDelay(attempt);
      DeliveryFailureResult result =
          store.markFailed(
              token, clock.instant(), clock.instant().plus(delay), properties.getMaximumAttempts());
      return switch (result) {
        case RETRY_SCHEDULED -> new DispatchReport(0, 1, 0, 0, 0);
        case STALLED -> new DispatchReport(0, 0, 1, 0, 0);
        case LEASE_LOST -> leaseLost();
      };
    } finally {
      heartbeat.cancel(false);
    }
  }

  private ScheduledFuture<?> scheduleRenewal(LeaseToken token, AtomicBoolean leaseLost) {
    Duration interval = properties.getLeaseRenewalInterval();
    long intervalNanos = interval.toNanos();
    return leaseRenewalExecutor.scheduleAtFixedRate(
        () -> {
          if (leaseLost.get()) {
            return;
          }
          try {
            if (!store.renewLease(token, clock.instant(), properties.getLeaseDuration())) {
              leaseLost.set(true);
            }
          } catch (RuntimeException unavailable) {
            leaseLost.set(true);
          }
        },
        intervalNanos,
        intervalNanos,
        TimeUnit.NANOSECONDS);
  }

  private Duration retryDelay(int attempt) {
    Duration delay = properties.getRetryBaseDelay();
    Duration maximum = properties.getRetryMaximumDelay();
    for (int index = 1; index < attempt && delay.compareTo(maximum) < 0; index++) {
      if (delay.compareTo(maximum.dividedBy(2)) > 0) {
        return maximum;
      }
      delay = delay.multipliedBy(2);
    }
    return delay.compareTo(maximum) > 0 ? maximum : delay;
  }

  private DispatchReport leaseLost() {
    return new DispatchReport(0, 0, 0, 0, 1);
  }
}
