package org.ayuvahak.transport.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;

/** Bounded Transfer-owner answer; never carries clinical or Referral data. */
public record TransportRequestEligibility(
    Outcome outcome,
    Optional<ExternalReference> originFacility,
    Optional<ExternalReference> destinationFacility,
    Optional<String> reasonCode) {

  public TransportRequestEligibility {
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
