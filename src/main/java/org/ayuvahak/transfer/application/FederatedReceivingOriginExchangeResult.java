package org.ayuvahak.transfer.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt.RemoteOutcome;

/** Bounded, validated business result from the receiving Transfer owner. */
public record FederatedReceivingOriginExchangeResult(
    RemoteOutcome outcome,
    InternalId receivingRequestId,
    Optional<InternalId> capacityReservationId,
    long receivingVersion) {

  public FederatedReceivingOriginExchangeResult {
    Objects.requireNonNull(outcome, "remote outcome");
    Objects.requireNonNull(receivingRequestId, "receiving request reference");
    capacityReservationId = Objects.requireNonNull(capacityReservationId, "reservation reference");
    if (receivingVersion < 1) {
      throw new IllegalArgumentException("receiving version must be positive");
    }
    if ((outcome == RemoteOutcome.ACCEPTED_RESERVED) != capacityReservationId.isPresent()) {
      throw new IllegalArgumentException("reservation reference does not match remote outcome");
    }
  }
}
