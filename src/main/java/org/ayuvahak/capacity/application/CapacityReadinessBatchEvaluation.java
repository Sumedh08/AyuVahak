package org.ayuvahak.capacity.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.ayuvahak.capacity.domain.CapacityReadinessEvaluation;

/** Complete set of owner-produced readiness outcomes captured at one evaluation instant. */
public record CapacityReadinessBatchEvaluation(
    Instant evaluationTime, List<CapacityReadinessEvaluation> facilities) {

  public CapacityReadinessBatchEvaluation {
    Objects.requireNonNull(evaluationTime, "evaluation time");
    facilities = List.copyOf(Objects.requireNonNull(facilities, "facility results"));
    if (facilities.isEmpty()
        || facilities.stream().anyMatch(result -> !result.evaluationTime().equals(evaluationTime))
        || facilities.stream().map(result -> result.facility()).distinct().count()
            != facilities.size()) {
      throw new IllegalArgumentException(
          "a readiness batch must contain distinct Facilities evaluated at one instant");
    }
  }
}
