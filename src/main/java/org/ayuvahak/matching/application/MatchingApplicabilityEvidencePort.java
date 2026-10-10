package org.ayuvahak.matching.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.capacity.application.CapacityReadinessBatchEvaluation;
import org.ayuvahak.directory.application.DirectoryMatchingCandidatePage;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.referral.application.ReferralMatchingRequirement;

/** Matching's owner-read boundary for rechecking one recommendation candidate's applicability. */
public interface MatchingApplicabilityEvidencePort {

  ReferralMatchingRequirement readSubmittedRequirement(
      CorrelationId correlationId,
      ExternalReference originOrganization,
      Optional<ExternalReference> originFacility,
      InternalId referralId,
      long revisionNumber);

  Optional<DirectoryMatchingCandidatePage.Candidate> readCandidate(
      CorrelationId correlationId,
      ExternalReference organization,
      ExternalReference facility,
      List<String> requiredCapabilityCodes,
      Instant evaluationTime);

  CapacityReadinessBatchEvaluation evaluateCandidateCapacityReadiness(
      CorrelationId correlationId,
      ExternalReference organization,
      ExternalReference facility,
      List<String> requiredDimensionCodes,
      Instant evaluationTime);
}
