package org.ayuvahak.matching.application;

import java.util.Objects;
import org.ayuvahak.matching.domain.RecommendationSnapshot;

/** Safe result of generating or replaying one idempotent recommendation command. */
public record MatchingRecommendationCommandResult(
    RecommendationSnapshot snapshot, boolean commandReplayed, int responseStatus) {

  public MatchingRecommendationCommandResult {
    Objects.requireNonNull(snapshot, "recommendation snapshot");
    if (responseStatus < 200 || responseStatus > 299) {
      throw new IllegalArgumentException("recommendation response status must be successful");
    }
  }
}
