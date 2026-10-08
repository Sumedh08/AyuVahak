package org.ayuvahak.platform.eventing.adapter;

import org.ayuvahak.platform.eventing.application.OutboxDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One local polling worker; multiple application instances coordinate through PostgreSQL leases.
 */
@Component
@ConditionalOnProperty(
    prefix = "ayuvahak.eventing.outbox",
    name = "dispatcher-enabled",
    havingValue = "true",
    matchIfMissing = true)
public final class OutboxPollingWorker {

  private final OutboxDispatcher dispatcher;

  public OutboxPollingWorker(OutboxDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @Scheduled(fixedDelayString = "${ayuvahak.eventing.outbox.poll-interval:PT1S}")
  public void poll() {
    dispatcher.dispatchAvailable();
  }
}
