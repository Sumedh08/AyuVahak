package org.ayuvahak.transfer.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import org.ayuvahak.matching.domain.RemoteFacilityReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Allowlisted, non-clinical input for an origin-owned remote receiving attempt. */
public record FederatedReceivingOriginCommand(
    long expectedJourneyVersion,
    long referralRevision,
    InternalId recommendationId,
    ExternalReference destinationOrganization,
    RemoteFacilityReference destinationFacility,
    String capacityDimensionCode,
    String capacityUnit,
    BigDecimal capacityQuantity,
    Instant capacityIntervalStart,
    Instant capacityIntervalEnd) {

  public FederatedReceivingOriginCommand {
    if (expectedJourneyVersion < 1 || referralRevision < 1) {
      throw new IllegalArgumentException("journey version and Referral revision must be positive");
    }
    Objects.requireNonNull(recommendationId, "recommendation id");
    Objects.requireNonNull(destinationOrganization, "destination Organization");
    Objects.requireNonNull(destinationFacility, "destination Facility");
    if (!"Organization".equals(destinationOrganization.resourceType())
        || !destinationOrganization
            .issuer()
            .value()
            .equals(destinationFacility.toExternalReference().issuer().value())) {
      throw new IllegalArgumentException(
          "destination Organization and Facility must share the remote Directory authority");
    }
    InternalId.parse(destinationOrganization.value());
    if (capacityDimensionCode == null || !capacityDimensionCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("capacity dimension code is invalid");
    }
    if (capacityUnit == null || !capacityUnit.matches("[A-Za-z][A-Za-z0-9._-]{0,31}")) {
      throw new IllegalArgumentException("capacity unit is invalid");
    }
    Objects.requireNonNull(capacityQuantity, "capacity quantity");
    if (capacityQuantity.signum() <= 0) {
      throw new IllegalArgumentException("capacity quantity must be positive");
    }
    Objects.requireNonNull(capacityIntervalStart, "capacity interval start");
    Objects.requireNonNull(capacityIntervalEnd, "capacity interval end");
    if (!capacityIntervalEnd.isAfter(capacityIntervalStart)) {
      throw new IllegalArgumentException("capacity interval end must follow its start");
    }
  }
}
