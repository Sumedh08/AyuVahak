package org.ayuvahak.matching.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Fresh result for whether one historical candidate remains usable for a new Transfer Request. */
public record RecommendationApplicabilityResult(
    InternalId recommendationId,
    ExternalReference candidateFacility,
    Status status,
    List<String> reasonCodes,
    Instant checkedAt) {

  public RecommendationApplicabilityResult {
    Objects.requireNonNull(recommendationId, "recommendation id");
    Objects.requireNonNull(candidateFacility, "candidate Facility");
    if (!"Facility".equals(candidateFacility.resourceType())) {
      throw new IllegalArgumentException("applicability candidate must be a Facility");
    }
    Objects.requireNonNull(status, "applicability status");
    reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "applicability reasons"));
    if (reasonCodes.stream()
        .anyMatch(code -> code == null || !code.matches("[A-Z][A-Z0-9_]{0,79}"))) {
      throw new IllegalArgumentException("applicability reasons must be stable uppercase codes");
    }
    Objects.requireNonNull(checkedAt, "check time");
  }

  public enum Status {
    APPLICABLE,
    NOT_APPLICABLE,
    INDETERMINATE
  }
}
