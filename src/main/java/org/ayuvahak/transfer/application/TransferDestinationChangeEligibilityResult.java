package org.ayuvahak.transfer.application;

import java.util.Objects;

/** Transfer's minimal current-state answer to Transport's destination-change request. */
public record TransferDestinationChangeEligibilityResult(Outcome outcome, String reasonCode) {

  public TransferDestinationChangeEligibilityResult {
    Objects.requireNonNull(outcome, "eligibility outcome");
    Objects.requireNonNull(reasonCode, "reason code");
  }

  public enum Outcome {
    ELIGIBLE,
    INELIGIBLE,
    UNAVAILABLE
  }
}
