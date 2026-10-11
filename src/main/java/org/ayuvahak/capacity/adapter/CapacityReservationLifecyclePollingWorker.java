package org.ayuvahak.capacity.adapter;

import org.ayuvahak.capacity.application.CapacityReservationCommands;
import org.ayuvahak.capacity.application.CapacityReservationLifecycleProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded worker that expires and risk-marks only Capacity-owned reservations. */
@Component
@Lazy
@ConditionalOnProperty(
    prefix = "ayuvahak.capacity.reservation-lifecycle",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public final class CapacityReservationLifecyclePollingWorker {

  private final CapacityReservationCommands reservations;
  private final CapacityReservationLifecycleProperties properties;

  public CapacityReservationLifecyclePollingWorker(
      CapacityReservationCommands reservations, CapacityReservationLifecycleProperties properties) {
    this.reservations = reservations;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${ayuvahak.capacity.reservation-lifecycle.poll-interval:PT15S}")
  public void reconcileLifecycle() {
    reservations.reconcileLifecycle();
  }
}
