package org.ayuvahak.capacity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;

/** Typed current Capacity lifecycle for a receiver-local federated request. */
public record FederatedReceivingCapacityReservationOutcome(
    InternalId operationId,
    InternalId receivingRequestId,
    CapacityReservationOutcome.Status status,
    Optional<InternalId> reservationId,
    Optional<String> reasonCode,
    Optional<Instant> expiresAt,
    long sequence) {

  public FederatedReceivingCapacityReservationOutcome {
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(receivingRequestId, "receiving request id");
    Objects.requireNonNull(status, "reservation status");
    reservationId = Objects.requireNonNull(reservationId, "reservation id");
    reasonCode = Objects.requireNonNull(reasonCode, "reason code");
    expiresAt = Objects.requireNonNull(expiresAt, "expiry");
    if (sequence < 0) {
      throw new IllegalArgumentException("reservation sequence cannot be negative");
    }
  }
}
