package org.ayuvahak.capacity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.ayuvahak.platform.core.ExternalReference;

/** Explainable evaluation over a non-empty, caller-supplied dimension set. */
public record CapacityReadinessEvaluation(
    ExternalReference facility,
    Instant evaluationTime,
    CapacityReadinessOutcome outcome,
    List<CapacityDimensionReadiness> dimensions) {

  public CapacityReadinessEvaluation {
    Objects.requireNonNull(facility, "facility");
    if (!"Facility".equals(facility.resourceType())) {
      throw new IllegalArgumentException("readiness requires a Facility reference");
    }
    Objects.requireNonNull(evaluationTime, "evaluation time");
    Objects.requireNonNull(outcome, "outcome");
    dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
    if (dimensions.isEmpty()
        || dimensions.stream().map(CapacityDimensionReadiness::dimensionCode).distinct().count()
            != dimensions.size()) {
      throw new IllegalArgumentException("readiness requires distinct requested dimensions");
    }
    if (outcome != aggregate(dimensions)) {
      throw new IllegalArgumentException("overall outcome must follow dimension precedence");
    }
  }

  public static CapacityReadinessOutcome aggregate(List<CapacityDimensionReadiness> dimensions) {
    Objects.requireNonNull(dimensions, "dimensions");
    if (dimensions.isEmpty()) {
      throw new IllegalArgumentException("readiness requires at least one dimension");
    }
    if (dimensions.stream()
        .anyMatch(result -> result.outcome() == CapacityReadinessOutcome.NOT_READY)) {
      return CapacityReadinessOutcome.NOT_READY;
    }
    if (dimensions.stream()
        .anyMatch(result -> result.outcome() == CapacityReadinessOutcome.CONFLICTED)) {
      return CapacityReadinessOutcome.CONFLICTED;
    }
    if (dimensions.stream()
        .anyMatch(result -> result.outcome() == CapacityReadinessOutcome.STALE)) {
      return CapacityReadinessOutcome.STALE;
    }
    if (dimensions.stream()
        .anyMatch(result -> result.outcome() == CapacityReadinessOutcome.UNKNOWN)) {
      return CapacityReadinessOutcome.UNKNOWN;
    }
    return CapacityReadinessOutcome.READY;
  }
}
