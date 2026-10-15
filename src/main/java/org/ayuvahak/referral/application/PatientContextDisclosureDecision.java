package org.ayuvahak.referral.application;

import java.util.Objects;
import java.util.Set;

/** Deployment policy outcome and the maximum coded categories permitted for this disclosure. */
public record PatientContextDisclosureDecision(
    Outcome outcome, Set<PatientContextCategory> permittedCategories) {

  public PatientContextDisclosureDecision {
    Objects.requireNonNull(outcome, "disclosure outcome");
    permittedCategories = Set.copyOf(Objects.requireNonNull(permittedCategories, "categories"));
    if (outcome != Outcome.ALLOWED && !permittedCategories.isEmpty()) {
      throw new IllegalArgumentException("denied or indeterminate policy cannot permit categories");
    }
  }

  public enum Outcome {
    ALLOWED,
    DENIED,
    INDETERMINATE
  }
}
