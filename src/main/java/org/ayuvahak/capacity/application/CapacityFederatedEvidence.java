package org.ayuvahak.capacity.application;

import java.util.Objects;
import org.ayuvahak.capacity.domain.CapacityDimensionReadiness;

/** One owner-authorized Capacity evidence projection and its monotonic source version. */
public record CapacityFederatedEvidence(
    CapacityDimensionReadiness readiness, long sourceSequence, long maximumObservationAgeSeconds) {

  public CapacityFederatedEvidence {
    Objects.requireNonNull(readiness, "readiness");
    if (sourceSequence < 1) {
      throw new IllegalArgumentException("source sequence must be positive");
    }
    if (maximumObservationAgeSeconds < 1) {
      throw new IllegalArgumentException("maximum observation age must be positive");
    }
  }
}
