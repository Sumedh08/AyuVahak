package org.ayuvahak.matching.application;

import java.util.Optional;
import org.ayuvahak.matching.domain.MatchingRecommendationPolicy;

/** Server-selected Matching policy; callers cannot supply or override this value. */
@FunctionalInterface
public interface MatchingPolicyCatalog {

  Optional<MatchingRecommendationPolicy> activePolicy();
}
