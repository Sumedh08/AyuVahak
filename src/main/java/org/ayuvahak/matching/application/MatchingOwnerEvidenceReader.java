package org.ayuvahak.matching.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import org.ayuvahak.capacity.application.CapacityReadiness;
import org.ayuvahak.capacity.application.CapacityReadinessBatchEvaluation;
import org.ayuvahak.capacity.application.CapacityReadinessBatchTarget;
import org.ayuvahak.capacity.domain.CapacityReadinessEvaluation;
import org.ayuvahak.directory.application.DirectoryApplicationService;
import org.ayuvahak.directory.application.DirectoryMatchingCandidatePage;
import org.ayuvahak.directory.application.DirectoryMatchingOriginLocation;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.audit.application.AuditPersistenceUnavailableException;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.SourceKind;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.referral.application.ReferralMatchingRequirement;
import org.ayuvahak.referral.application.ReferralQueries;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Matching's narrow application boundary for minimized, independently authorized owner reads. */
@Service
@Lazy
public final class MatchingOwnerEvidenceReader implements MatchingApplicabilityEvidencePort {

  private static final int MAX_CAPACITY_BATCH_SIZE = 100;

  private final MatchingOwnerReadContexts contexts;
  private final ReferralQueries referrals;
  private final DirectoryApplicationService directory;
  private final CapacityReadiness capacity;
  private final AuthorizationService authorization;
  private final AuditWriter auditWriter;
  private final java.time.Clock clock;

  public MatchingOwnerEvidenceReader(
      MatchingOwnerReadContexts contexts,
      ReferralQueries referrals,
      DirectoryApplicationService directory,
      CapacityReadiness capacity,
      AuthorizationService authorization,
      AuditWriter auditWriter,
      @Qualifier("auditClock") java.time.Clock clock) {
    this.contexts = Objects.requireNonNull(contexts, "Matching owner-read contexts");
    this.referrals = Objects.requireNonNull(referrals, "Referral owner contract");
    this.directory = Objects.requireNonNull(directory, "Directory owner contract");
    this.capacity = Objects.requireNonNull(capacity, "Capacity owner contract");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public ReferralMatchingRequirement readSubmittedRequirement(
      ExecutionContext requester, InternalId referralId, long revisionNumber) {
    requireRecommendationRequest(requester);
    return referrals.getSubmittedRequirement(
        contexts.referralRead(requester), referralId, revisionNumber);
  }

  /** Reads the exact current submitted requirement under the Matching service identity. */
  @Override
  public ReferralMatchingRequirement readSubmittedRequirement(
      CorrelationId correlationId,
      ExternalReference originOrganization,
      Optional<ExternalReference> originFacility,
      InternalId referralId,
      long revisionNumber) {
    Objects.requireNonNull(originFacility, "origin Facility");
    return referrals.getSubmittedRequirement(
        contexts.referralRead(correlationId, originOrganization, originFacility),
        referralId,
        revisionNumber);
  }

  public List<DirectoryMatchingCandidatePage> findCandidatePages(
      ExecutionContext requester,
      ReferralMatchingRequirement requirement,
      ExternalReference organization,
      int size,
      Instant evaluationTime) {
    requireRecommendationRequest(requester);
    Objects.requireNonNull(requirement, "submitted Referral requirement");
    Objects.requireNonNull(organization, "candidate organization");
    requireReferralOriginMatches(requester, requirement);
    return directory.findMatchingCandidatePages(
        contexts.directoryRead(requester, organization),
        InternalId.parse(organization.value()),
        requirement.requiredCareCapabilityCodes(),
        size,
        evaluationTime);
  }

  /** Reads current Directory facts for one candidate only; no candidate universe is enumerated. */
  @Override
  public Optional<DirectoryMatchingCandidatePage.Candidate> readCandidate(
      CorrelationId correlationId,
      ExternalReference organization,
      ExternalReference facility,
      List<String> requiredCapabilityCodes,
      Instant evaluationTime) {
    Objects.requireNonNull(facility, "candidate Facility");
    return directory.getMatchingCandidate(
        contexts.directoryRead(correlationId, organization),
        InternalId.parse(facility.value()),
        requiredCapabilityCodes,
        evaluationTime);
  }

  /** Reads only the Referral's exact origin Facility and primary Location, if one is known. */
  public Optional<DirectoryMatchingOriginLocation> readOriginLocation(
      ExecutionContext requester, ReferralMatchingRequirement requirement, Instant evaluationTime) {
    requireRecommendationRequest(requester);
    Objects.requireNonNull(requirement, "submitted Referral requirement");
    Objects.requireNonNull(evaluationTime, "evaluation time");
    requireReferralOriginMatches(requester, requirement);
    return requirement
        .originFacility()
        .map(
            facility ->
                directory.getMatchingOriginLocation(
                    contexts.directoryRead(requester, requirement.originOrganization(), facility),
                    InternalId.parse(facility.value()),
                    evaluationTime));
  }

  /**
   * Reads readiness only for active candidate Facilities. Inactive candidates remain in Directory
   * evidence and are not silently removed from the overall evaluation.
   */
  public Optional<CapacityReadinessBatchEvaluation> evaluateCapacityReadiness(
      ExecutionContext requester,
      List<DirectoryMatchingCandidatePage.Candidate> candidates,
      List<String> requiredDimensionCodes,
      Instant evaluationTime) {
    requireRecommendationRequest(requester);
    Objects.requireNonNull(candidates, "Directory candidates");
    Objects.requireNonNull(requiredDimensionCodes, "required CapacityDimensions");
    Objects.requireNonNull(evaluationTime, "evaluation time");
    List<String> dimensions = List.copyOf(new TreeSet<>(requiredDimensionCodes));
    if (dimensions.isEmpty()) {
      throw new IllegalArgumentException("Matching must resolve CapacityDimensions before reading");
    }
    if (candidates.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("Directory candidate evidence cannot contain null");
    }
    List<DirectoryMatchingCandidatePage.Candidate> activeCandidates =
        candidates.stream()
            .filter(candidate -> "ACTIVE".equals(candidate.lifecycleStatus()))
            .toList();
    if (activeCandidates.isEmpty()) {
      return Optional.empty();
    }
    if (activeCandidates.stream()
            .map(DirectoryMatchingCandidatePage.Candidate::facility)
            .distinct()
            .count()
        != activeCandidates.size()) {
      throw new IllegalArgumentException("a Capacity read cannot repeat a candidate Facility");
    }

    List<CapacityReadinessEvaluation> evaluations = new ArrayList<>();
    for (int start = 0; start < activeCandidates.size(); start += MAX_CAPACITY_BATCH_SIZE) {
      List<CapacityReadinessBatchTarget> targets =
          activeCandidates
              .subList(start, Math.min(start + MAX_CAPACITY_BATCH_SIZE, activeCandidates.size()))
              .stream()
              .map(
                  candidate ->
                      new CapacityReadinessBatchTarget(
                          contexts.capacityRead(
                              requester, candidate.organization(), candidate.facility()),
                          candidate.facility()))
              .toList();
      evaluations.addAll(
          capacity
              .evaluateOperationalReadinessBatch(targets, dimensions, evaluationTime)
              .facilities());
    }
    return Optional.of(new CapacityReadinessBatchEvaluation(evaluationTime, evaluations));
  }

  /** Evaluates one candidate Facility and the exact mapped dimensions under its owner grant. */
  @Override
  public CapacityReadinessBatchEvaluation evaluateCandidateCapacityReadiness(
      CorrelationId correlationId,
      ExternalReference organization,
      ExternalReference facility,
      List<String> requiredDimensionCodes,
      Instant evaluationTime) {
    Objects.requireNonNull(requiredDimensionCodes, "required CapacityDimensions");
    List<String> dimensions = List.copyOf(new TreeSet<>(requiredDimensionCodes));
    if (dimensions.isEmpty()) {
      throw new IllegalArgumentException("Matching must resolve CapacityDimensions before reading");
    }
    CapacityReadinessBatchTarget target =
        new CapacityReadinessBatchTarget(
            contexts.capacityRead(correlationId, organization, facility), facility);
    return capacity.evaluateOperationalReadinessBatch(List.of(target), dimensions, evaluationTime);
  }

  private void requireRecommendationRequest(ExecutionContext requester) {
    Objects.requireNonNull(requester, "recommendation requester context");
    if (requester.actor().kind() != ActorKind.HUMAN
        || requester.authenticationMechanism() != AuthenticationMechanism.HUMAN_ACCESS_TOKEN
        || requester.sourceKind() != SourceKind.INTERACTIVE_REQUEST
        || (requester.scope().kind() != ExecutionScope.Kind.ORGANIZATION
            && requester.scope().kind() != ExecutionScope.Kind.FACILITY)) {
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.AUTHORIZATION_DENIED,
          "Recommendation evidence requires an origin-scoped human request.");
    }
    AuthorizationDecision decision = authorization.evaluate(requester, "RecommendationRequest");
    if (decision.isAllowed()) {
      return;
    }
    recordAuthorizationDenial(requester, decision);
    if (decision.denialReason().orElse(null)
        == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
          "Recommendation authority could not be verified safely.");
    }
    throw new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated actor is not authorized to request a recommendation.");
  }

  /** Authorizes the human command before Matching consults its active server policy. */
  public void authorizeRecommendationRequest(ExecutionContext requester) {
    requireRecommendationRequest(requester);
  }

  private void recordAuthorizationDenial(
      ExecutionContext requester, AuthorizationDecision decision) {
    String reasonCode =
        decision.denialReason().orElse(null)
                == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE
            ? "AUTHORIZATION_STORE_UNAVAILABLE"
            : "AUTHORIZATION_DENIED";
    try {
      auditWriter.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.of(requester.actor()),
              Optional.of(requester.scope()),
              "MATCHING_RECOMMENDATION_REQUEST_DENIED",
              "Recommendation",
              Optional.empty(),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of(reasonCode),
              AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN,
              AuditAuthorizationOutcome.DENIED,
              requester.correlationId()));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The denied recommendation request could not be recorded safely.");
    }
  }

  private static void requireReferralOriginMatches(
      ExecutionContext requester, ReferralMatchingRequirement requirement) {
    if (!requester.scope().organization().equals(Optional.of(requirement.originOrganization()))
        || (requester.scope().kind() == ExecutionScope.Kind.FACILITY
            && !requester.scope().facility().equals(requirement.originFacility()))) {
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.AUTHORIZATION_DENIED,
          "The submitted Referral requirement is outside the requester origin scope.");
    }
  }
}
