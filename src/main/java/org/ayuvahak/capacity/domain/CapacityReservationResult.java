package org.ayuvahak.capacity.domain;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;

/** Current Capacity-owner outcome for a stable Transfer reservation operation. */
public record CapacityReservationResult(
    InternalId operationId,
    Outcome outcome,
    Optional<InternalId> reservationId,
    Optional<String> reasonCode,
    boolean replayed) {

  public CapacityReservationResult {
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(outcome, "outcome");
    reservationId = Objects.requireNonNull(reservationId, "reservation id");
    reasonCode = Objects.requireNonNull(reasonCode, "reason code");
    if ((outcome == Outcome.RESERVED) != reservationId.isPresent()) {
      throw new IllegalArgumentException("only a reserved result has a reservation identifier");
    }
  }

  public enum Outcome {
    RESERVED,
    CONFLICT,
    UNKNOWN,
    INVALID
  }
}
