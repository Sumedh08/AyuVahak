package org.ayuvahak.transfer.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;

/** Transfer-owned participant references required to authorize and report progress. */
public record TransferProgressTarget(
    CoordinatedTransfer transfer,
    ExternalReference originOrganization,
    Optional<ExternalReference> originFacility,
    ExternalReference receivingOrganization,
    ExternalReference receivingFacility,
    long journeyVersion) {

  public TransferProgressTarget {
    Objects.requireNonNull(transfer, "Transfer");
    Objects.requireNonNull(originOrganization, "origin Organization");
    originFacility = Objects.requireNonNull(originFacility, "origin Facility");
    Objects.requireNonNull(receivingOrganization, "receiving Organization");
    Objects.requireNonNull(receivingFacility, "receiving Facility");
    if (journeyVersion < 1) {
      throw new IllegalArgumentException("TransferJourney version must be positive");
    }
  }
}
