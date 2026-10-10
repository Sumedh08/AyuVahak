package org.ayuvahak.matching.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.matching.domain.RecommendationSnapshot;
import org.ayuvahak.platform.core.InternalId;

/** One scope-filtered, bounded immutable Recommendation history result. */
public record RecommendationHistoryResult(
    InternalId referralId,
    List<RecommendationSnapshot> items,
    Optional<InternalId> latestRecommendationId,
    Optional<RecommendationApplicabilityResult> currentApplicability) {

  public RecommendationHistoryResult {
    Objects.requireNonNull(referralId, "Referral id");
    items = List.copyOf(Objects.requireNonNull(items, "recommendation history"));
    latestRecommendationId = Objects.requireNonNull(latestRecommendationId, "latest id");
    currentApplicability = Objects.requireNonNull(currentApplicability, "applicability");
    if (items.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("recommendation history cannot contain null");
    }
    if (items.isEmpty() != latestRecommendationId.isEmpty()
        || (!items.isEmpty()
            && !latestRecommendationId.orElseThrow().equals(items.getFirst().id()))) {
      throw new IllegalArgumentException("latest recommendation must be the first history item");
    }
  }
}
