package org.ayuvahak.transfer.adapter;

import org.ayuvahak.transfer.application.TransferRequestExpirySweep;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled invocation of Transfer's own bounded request-expiry command sweep. */
@Component
@ConditionalOnProperty(
    prefix = "ayuvahak.transfer",
    name = "expiry-polling-enabled",
    havingValue = "true",
    matchIfMissing = true)
public final class ExpiryPollingWorker {

  private final TransferRequestExpirySweep sweep;

  public ExpiryPollingWorker(TransferRequestExpirySweep sweep) {
    this.sweep = sweep;
  }

  @Scheduled(fixedDelayString = "${ayuvahak.transfer.expiry-poll-interval:PT15S}")
  public void poll() {
    sweep.expireDueRequests();
  }
}
