package org.ayuvahak.capacity.domain;

import java.util.Objects;

/** An immutable observation together with the correction-chain fact needed to resolve it. */
public record CapacityObservationFact(CapacityObservation observation, boolean superseded) {

  public CapacityObservationFact {
    Objects.requireNonNull(observation, "observation");
  }
}
