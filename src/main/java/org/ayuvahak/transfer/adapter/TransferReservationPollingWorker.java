package org.ayuvahak.transfer.adapter;

import org.ayuvahak.transfer.application.TransferCapacityReservationRecovery;
import org.ayuvahak.transfer.application.TransferDestinationChangeCoordinator;
import org.ayuvahak.transfer.application.TransferDestinationChangeEventHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded local scheduler for recovering Transfer-to-Capacity reservation responses. */
@Component
@Lazy
@ConditionalOnProperty(
    prefix = "ayuvahak.transfer",
    name = "reservation-polling-enabled",
    havingValue = "true",
    matchIfMissing = true)
public final class TransferReservationPollingWorker {

  private final TransferCapacityReservationRecovery recovery;
  private final TransferDestinationChangeCoordinator destinationChangeCoordinator;
  private final TransferDestinationChangeEventHandler destinationChangeRecovery;

  public TransferReservationPollingWorker(
      TransferCapacityReservationRecovery recovery,
      TransferDestinationChangeCoordinator destinationChangeCoordinator,
      TransferDestinationChangeEventHandler destinationChangeRecovery) {
    this.recovery = recovery;
    this.destinationChangeCoordinator = destinationChangeCoordinator;
    this.destinationChangeRecovery = destinationChangeRecovery;
  }

  @Scheduled(fixedDelayString = "${ayuvahak.transfer.reservation-poll-interval:PT15S}")
  public void recoverDueReservations() {
    recovery.reconcileDue();
    destinationChangeCoordinator.reconcilePendingTransportOutcomes(50);
    destinationChangeRecovery.recoverPendingCapacityReleases(50);
  }
}
