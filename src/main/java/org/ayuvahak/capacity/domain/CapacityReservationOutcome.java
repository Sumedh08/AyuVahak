package org.ayuvahak.capacity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;

/**
 * Capacity owner's read-only view of the current lifecycle for one stable reservation operation.
 */
public record CapacityReservationOutcome(
    InternalId operationId,
    InternalId transferId,
    Status status,
    Optional<InternalId> reservationId,
    Optional<String> reasonCode,
    Optional<Instant> expiresAt,
    long lifecycleSequence) {

  public CapacityReservationOutcome {
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(transferId, "Transfer id");
    Objects.requireNonNull(status, "reservation status");
    reservationId = Objects.requireNonNull(reservationId, "reservation id");
    reasonCode = Objects.requireNonNull(reasonCode, "reason code");
    expiresAt = Objects.requireNonNull(expiresAt, "expiry");
    if (lifecycleSequence < 0) {
      throw new IllegalArgumentException("lifecycle sequence cannot be negative");
    }
    boolean hasReservation = status != Status.UNKNOWN;
    if (hasReservation != reservationId.isPresent()
        || hasReservation != expiresAt.isPresent()
        || (hasReservation && lifecycleSequence < 1)) {
      throw new IllegalArgumentException("reservation lifecycle result is inconsistent");
    }
  }

  public enum Status {
    RESERVED,
    AT_RISK,
    RELEASED,
    EXPIRED,
    UNKNOWN
  }
}
