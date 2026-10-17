package org.ayuvahak.transfer.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/**
 * Allowlisted coordination facts accepted from a trusted peer; patient context is not
 * representable.
 */
public record FederatedReceivingRequestInput(
    InternalId originJourneyId,
    String originFacilityId,
    ExternalReference destinationOrganization,
    ExternalReference destinationFacility,
    String capacityDimensionCode,
    String capacityUnit,
    BigDecimal capacityQuantity,
    Instant capacityIntervalStart,
    Instant capacityIntervalEnd) {

  public FederatedReceivingRequestInput {
    Objects.requireNonNull(originJourneyId, "origin TransferJourney id");
    Objects.requireNonNull(destinationOrganization, "destination Organization");
    Objects.requireNonNull(destinationFacility, "destination Facility");
    Objects.requireNonNull(capacityDimensionCode, "capacity dimension");
    Objects.requireNonNull(capacityUnit, "capacity unit");
    Objects.requireNonNull(capacityQuantity, "capacity quantity");
    Objects.requireNonNull(capacityIntervalStart, "capacity interval start");
    Objects.requireNonNull(capacityIntervalEnd, "capacity interval end");
    if (originFacilityId == null
        || !originFacilityId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("origin Facility reference is invalid");
    }
    if (!capacityDimensionCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("capacity dimension code is invalid");
    }
    if (!capacityUnit.matches("[A-Za-z][A-Za-z0-9._-]{0,31}")) {
      throw new IllegalArgumentException("capacity unit is invalid");
    }
    if (capacityQuantity.signum() <= 0) {
      throw new IllegalArgumentException("capacity quantity must be positive");
    }
    if (!capacityIntervalEnd.isAfter(capacityIntervalStart)) {
      throw new IllegalArgumentException("capacity interval end must be after its start");
    }
  }
}
