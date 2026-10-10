package org.ayuvahak.transfer.application;

import org.ayuvahak.matching.application.RecommendationApplicabilityResult;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Transfer-owned port for the synchronous Matching check required before a request. */
public interface MatchingApplicabilityPort {

  RecommendationApplicabilityResult revalidate(
      ExecutionContext authorizedCaller,
      InternalId recommendationId,
      ExternalReference candidateFacility,
      long exactReferralRevision);
}
