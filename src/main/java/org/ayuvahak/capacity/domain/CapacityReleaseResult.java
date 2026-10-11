package org.ayuvahak.capacity.domain;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;

/** Capacity-owner confirmation for the release of one Transfer reservation. */
public record CapacityReleaseResult(
    Outcome outcome, Optional<InternalId> reservationId, long sequence) {

  public CapacityReleaseResult {
    Objects.requireNonNull(outcome, "release outcome");
    reservationId = Objects.requireNonNull(reservationId, "reservation id");
    if ((outcome == Outcome.RELEASED || outcome == Outcome.EXPIRED) != reservationId.isPresent()) {
      throw new IllegalArgumentException(
          "release outcome and reservation reference are inconsistent");
    }
    if (sequence < 0) {
      throw new IllegalArgumentException("reservation sequence cannot be negative");
    }
  }

  public enum Outcome {
    RELEASED,
    EXPIRED,
    NOT_RESERVED
  }
}
