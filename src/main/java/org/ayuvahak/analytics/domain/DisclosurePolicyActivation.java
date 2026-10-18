package org.ayuvahak.analytics.domain;

import java.util.Objects;

/** Result of activation including whether the immutable version was an exact replay. */
public record DisclosurePolicyActivation(DisclosurePolicyVersion policy, boolean replayed) {
  public DisclosurePolicyActivation {
    Objects.requireNonNull(policy, "policy");
  }
}
