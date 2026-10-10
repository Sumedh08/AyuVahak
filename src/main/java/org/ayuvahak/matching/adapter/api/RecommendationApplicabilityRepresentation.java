package org.ayuvahak.matching.adapter.api;

import java.time.Instant;
import java.util.List;
import org.ayuvahak.matching.application.RecommendationApplicabilityResult;

/** Safe API projection of a fresh applicability check. */
public record RecommendationApplicabilityRepresentation(
    RecommendationSnapshotRepresentation.ReferenceRepresentation candidateFacility,
    String status,
    List<String> reasonCodes,
    Instant checkedAt) {

  static RecommendationApplicabilityRepresentation from(RecommendationApplicabilityResult result) {
    return new RecommendationApplicabilityRepresentation(
        RecommendationSnapshotRepresentation.ReferenceRepresentation.from(
            result.candidateFacility()),
        result.status().name(),
        result.reasonCodes(),
        result.checkedAt());
  }
}
