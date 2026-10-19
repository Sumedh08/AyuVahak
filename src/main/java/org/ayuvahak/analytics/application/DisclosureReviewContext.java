package org.ayuvahak.analytics.application;

import java.util.List;
import java.util.Objects;
import org.ayuvahak.analytics.domain.DisclosureCandidate;
import org.ayuvahak.analytics.domain.DisclosureOutput;

/** Locked candidate and prior disclosed outputs used to reassess a pending privacy review. */
public record DisclosureReviewContext(
    DisclosureCandidate candidate, List<DisclosureOutput> priorPotentiallyDisclosedOutputs) {

  public DisclosureReviewContext {
    Objects.requireNonNull(candidate, "review candidate");
    priorPotentiallyDisclosedOutputs = List.copyOf(priorPotentiallyDisclosedOutputs);
  }
}
