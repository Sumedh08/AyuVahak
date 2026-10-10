package org.ayuvahak.matching.adapter.api;

import java.util.List;
import java.util.Optional;
import org.ayuvahak.matching.application.RecommendationHistoryResult;

/** Safe API projection of a bounded immutable Recommendation history. */
public record RecommendationHistoryRepresentation(
    String referralId,
    List<RecommendationSnapshotRepresentation> items,
    Optional<String> latestRecommendationId,
    Optional<RecommendationApplicabilityRepresentation> currentApplicability) {

  static RecommendationHistoryRepresentation from(RecommendationHistoryResult result) {
    return new RecommendationHistoryRepresentation(
        result.referralId().toString(),
        result.items().stream().map(RecommendationSnapshotRepresentation::from).toList(),
        result.latestRecommendationId().map(Object::toString),
        result.currentApplicability().map(RecommendationApplicabilityRepresentation::from));
  }
}
