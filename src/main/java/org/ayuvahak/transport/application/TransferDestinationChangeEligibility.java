package org.ayuvahak.transport.application;

import java.util.Objects;

/** Transfer owner's bounded answer before Transport accepts a reroute operation. */
public record TransferDestinationChangeEligibility(Outcome outcome, String reasonCode) {

  public TransferDestinationChangeEligibility {
    Objects.requireNonNull(outcome, "eligibility outcome");
    Objects.requireNonNull(reasonCode, "reason code");
  }

  public enum Outcome {
    ELIGIBLE,
    INELIGIBLE,
    UNAVAILABLE
  }
}
