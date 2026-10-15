package org.ayuvahak.transfer.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;

/** Transfer-owned, bounded result for the Transport request-eligibility contract. */
public record TransferTransportEligibilityResult(
    Outcome outcome,
    Optional<ExternalReference> originFacility,
    Optional<ExternalReference> destinationFacility,
    Optional<String> reasonCode) {

  public TransferTransportEligibilityResult {
    Objects.requireNonNull(outcome, "outcome");
    originFacility = Objects.requireNonNull(originFacility, "origin Facility");
    destinationFacility = Objects.requireNonNull(destinationFacility, "destination Facility");
    reasonCode = Objects.requireNonNull(reasonCode, "reason code");
    if ((outcome == Outcome.ELIGIBLE)
        != (originFacility.isPresent() && destinationFacility.isPresent())) {
      throw new IllegalArgumentException("only an eligible result carries facility references");
    }
  }

  public enum Outcome {
    ELIGIBLE,
    INELIGIBLE,
    UNAVAILABLE,
    AMBIGUOUS
  }
}
