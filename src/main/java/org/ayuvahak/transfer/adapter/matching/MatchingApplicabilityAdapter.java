package org.ayuvahak.transfer.adapter.matching;

import java.util.Objects;
import org.ayuvahak.matching.application.MatchingRecommendationQueries;
import org.ayuvahak.matching.application.RecommendationApplicabilityResult;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.transfer.application.MatchingApplicabilityPort;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Matching adapter for Transfer's owner-defined applicability port. */
@Component
@Lazy
public final class MatchingApplicabilityAdapter implements MatchingApplicabilityPort {

  private final MatchingRecommendationQueries matching;

  public MatchingApplicabilityAdapter(MatchingRecommendationQueries matching) {
    this.matching = Objects.requireNonNull(matching, "Matching queries");
  }

  @Override
  public RecommendationApplicabilityResult revalidate(
      ExecutionContext authorizedCaller,
      InternalId referralId,
      InternalId recommendationId,
      ExternalReference candidateFacility,
      long exactReferralRevision) {
    return matching.revalidateRecommendationApplicability(
        authorizedCaller, recommendationId, referralId, candidateFacility, exactReferralRevision);
  }
}
