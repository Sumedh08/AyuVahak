package org.ayuvahak.transfer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Immutable Capacity request inputs captured for safe replay of one destination change. */
public record DestinationChangeReservationPlan(
    String dimensionCode,
    String unit,
    BigDecimal quantity,
    Instant intervalStart,
    Instant intervalEnd) {

  public DestinationChangeReservationPlan {
    if (dimensionCode == null || !dimensionCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("reservation dimension code is invalid");
    }
    if (unit == null || !unit.matches("[A-Za-z][A-Za-z0-9._-]{0,31}")) {
      throw new IllegalArgumentException("reservation unit is invalid");
    }
    Objects.requireNonNull(quantity, "reservation quantity");
    if (quantity.signum() <= 0) {
      throw new IllegalArgumentException("reservation quantity must be positive");
    }
    Objects.requireNonNull(intervalStart, "reservation interval start");
    Objects.requireNonNull(intervalEnd, "reservation interval end");
    if (!intervalEnd.isAfter(intervalStart)) {
      throw new IllegalArgumentException("reservation interval end must follow its start");
    }
  }
}
