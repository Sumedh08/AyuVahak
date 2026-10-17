package org.ayuvahak.capacity.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** A request for a time-bounded coordination hold, not a physical resource assignment. */
public record CapacityReservationRequest(
    InternalId operationId,
    InternalId transferId,
    ExternalReference facility,
    String dimensionCode,
    String unit,
    BigDecimal quantity,
    Instant intervalStart,
    Instant intervalEnd,
    Instant expiresAt)
    implements CapacityReservationIntent {

  public CapacityReservationRequest {
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(transferId, "Transfer id");
    Objects.requireNonNull(facility, "facility");
    if (!"urn:ayuvahak:directory".equals(facility.issuer().value())
        || !"Facility".equals(facility.resourceType())) {
      throw new IllegalArgumentException("reservation requires a Directory Facility reference");
    }
    InternalId.parse(facility.value());
    if (dimensionCode == null || !dimensionCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("reservation dimension code is invalid");
    }
    if (unit == null || !unit.matches("[A-Za-z][A-Za-z0-9._-]{0,31}")) {
      throw new IllegalArgumentException("reservation unit is invalid");
    }
    Objects.requireNonNull(quantity, "quantity");
    if (quantity.signum() <= 0) {
      throw new IllegalArgumentException("reservation quantity must be positive");
    }
    Objects.requireNonNull(intervalStart, "interval start");
    Objects.requireNonNull(intervalEnd, "interval end");
    Objects.requireNonNull(expiresAt, "expiry");
    if (!intervalEnd.isAfter(intervalStart) || !expiresAt.equals(intervalEnd)) {
      throw new IllegalArgumentException("reservation interval and expiry are inconsistent");
    }
  }

  @Override
  public CapacityReservationSubject subject() {
    return CapacityReservationSubject.transfer(transferId);
  }
}
