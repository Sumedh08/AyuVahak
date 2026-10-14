package org.ayuvahak.matching.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.ayuvahak.capacity.application.CapacityReadinessBatchEvaluation;
import org.ayuvahak.capacity.domain.CapacityReadinessEvaluation;
import org.ayuvahak.directory.application.DirectoryMatchingCandidatePage;
import org.ayuvahak.matching.domain.MatchingRecommendationPolicy;
import org.ayuvahak.matching.domain.RecommendationRanker.CandidateStatus;
import org.ayuvahak.matching.domain.RecommendationSnapshot;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.audit.application.AuditPersistenceUnavailableException;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.audit.domain.AuditTargetContext;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.referral.application.ReferralMatchingRequirement;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Authorized reads over Matching-owned immutable Recommendation snapshots. */
@Service
@Lazy
public final class MatchingRecommendationQueries {

  private final MatchingStore store;
  private final MatchingApplicabilityEvidencePort ownerEvidence;
  private final MatchingPolicyCatalog policies;
  private final AuthorizationService authorization;
  private final AuditWriter auditWriter;
  private final TransactionTemplate ownerRead;
  private final Clock clock;

  public MatchingRecommendationQueries(
      MatchingStore store,
      MatchingApplicabilityEvidencePort ownerEvidence,
      MatchingPolicyCatalog policies,
      AuthorizationService authorization,
      AuditWriter auditWriter,
      PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "Matching store");
    this.ownerEvidence = Objects.requireNonNull(ownerEvidence, "Matching owner evidence reader");
    this.policies = Objects.requireNonNull(policies, "Matching policy catalog");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.ownerRead = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.ownerRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerRead.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerRead.setReadOnly(true);
    this.ownerRead.setTimeout(5);
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public RecommendationSnapshot getSnapshot(ExecutionContext requester, InternalId snapshotId) {
    requireReadAuthority(requester);
    Optional<RecommendationSnapshot> result;
    try {
      result = findSnapshotInOwnerTransaction(Objects.requireNonNull(snapshotId, "snapshot id"));
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
    if (result.isEmpty()) {
      recordRead(
          requester,
          "MATCHING_RECOMMENDATION_READ",
          snapshotId,
          AuditOutcome.FAILED,
          Optional.of("MATCHING_SNAPSHOT_NOT_FOUND"));
      throw notFound();
    }
    RecommendationSnapshot snapshot = result.orElseThrow();
    if (!scopeCanRead(requester.scope(), snapshot)) {
      recordRead(
          requester,
          "MATCHING_RECOMMENDATION_SCOPE_REJECTED",
          snapshot.id(),
          AuditOutcome.DENIED,
          Optional.of("MATCHING_SCOPE_MISMATCH"));
      throw notFound();
    }
    recordRead(
        requester,
        "MATCHING_RECOMMENDATION_READ",
        snapshot.id(),
        AuditOutcome.SUCCEEDED,
        Optional.empty());
    return snapshot;
  }

  public RecommendationHistoryResult getHistory(
      ExecutionContext requester,
      InternalId referralId,
      Optional<ExternalReference> requestedCandidate,
      int limit) {
    requireReadAuthority(requester);
    Objects.requireNonNull(referralId, "Referral id");
    Objects.requireNonNull(requestedCandidate, "candidate Facility");
    requestedCandidate.ifPresent(MatchingRecommendationQueries::requireDirectoryFacility);
    if (limit < 1 || limit > 100) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MATCHING_REQUEST_INVALID,
          "Recommendation history limit must be between 1 and 100.");
    }
    InternalId organizationId =
        InternalId.parse(requester.scope().organization().orElseThrow().value());
    Optional<InternalId> facilityId =
        requester.scope().kind() == ExecutionScope.Kind.FACILITY
            ? requester.scope().facility().map(reference -> InternalId.parse(reference.value()))
            : Optional.empty();
    List<RecommendationSnapshot> items;
    try {
      items =
          ownerRead.execute(
              status -> store.findHistory(referralId, organizationId, facilityId, limit));
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
    Optional<RecommendationApplicabilityResult> applicability = Optional.empty();
    if (!items.isEmpty()) {
      RecommendationSnapshot latest = items.getFirst();
      Optional<ExternalReference> candidate =
          requestedCandidate.isPresent()
              ? requestedCandidate
              : latest.candidates().stream()
                  .filter(item -> item.rank().isPresent())
                  .min(Comparator.comparingInt(item -> item.rank().orElseThrow()))
                  .map(RecommendationSnapshot.CandidateEvidence::facility);
      if (candidate.isPresent()) {
        applicability =
            Optional.of(
                revalidate(
                    latest,
                    requester,
                    referralId,
                    candidate.orElseThrow(),
                    latest.referralRevision()));
      }
    }
    recordTargetRead(
        requester,
        "MATCHING_RECOMMENDATION_HISTORY_READ",
        "Referral",
        referralId,
        AuditOutcome.SUCCEEDED,
        Optional.empty());
    applicability.ifPresent(
        current ->
            recordTargetRead(
                requester,
                "MATCHING_RECOMMENDATION_APPLICABILITY_CHECK",
                "Recommendation",
                current.recommendationId(),
                AuditOutcome.SUCCEEDED,
                current.reasonCodes().stream().findFirst()));
    return new RecommendationHistoryResult(
        referralId,
        items,
        items.stream().findFirst().map(RecommendationSnapshot::id),
        applicability);
  }

  /**
   * Contract for a previously authorized originating-domain action such as a later Transfer
   * Request. That caller must authorize its own operation before invoking this query; the method
   * rechecks origin scope and always performs separately authorized owner reads.
   */
  public RecommendationApplicabilityResult revalidateRecommendationApplicability(
      ExecutionContext authorizedCaller,
      InternalId snapshotId,
      InternalId expectedReferralId,
      ExternalReference candidateFacility,
      long exactReferralRevision) {
    requireTrustedCaller(authorizedCaller);
    Objects.requireNonNull(expectedReferralId, "expected Referral id");
    RecommendationSnapshot snapshot;
    try {
      snapshot =
          findSnapshotInOwnerTransaction(Objects.requireNonNull(snapshotId, "snapshot id"))
              .orElseThrow(MatchingRecommendationQueries::notFound);
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
    if (!scopeCanRead(authorizedCaller.scope(), snapshot)) {
      recordTargetRead(
          authorizedCaller,
          "MATCHING_RECOMMENDATION_SCOPE_REJECTED",
          "Recommendation",
          snapshot.id(),
          AuditOutcome.DENIED,
          Optional.of("MATCHING_SCOPE_MISMATCH"));
      throw notFound();
    }
    requireDirectoryFacility(candidateFacility);
    RecommendationApplicabilityResult result =
        revalidate(
            snapshot,
            authorizedCaller,
            expectedReferralId,
            candidateFacility,
            exactReferralRevision);
    recordTargetRead(
        authorizedCaller,
        "MATCHING_RECOMMENDATION_APPLICABILITY_CHECK",
        "Recommendation",
        snapshot.id(),
        AuditOutcome.SUCCEEDED,
        result.reasonCodes().stream().findFirst());
    return result;
  }

  private Optional<RecommendationSnapshot> findSnapshotInOwnerTransaction(InternalId snapshotId) {
    return ownerRead.execute(status -> store.findSnapshot(snapshotId));
  }

  private RecommendationApplicabilityResult revalidate(
      RecommendationSnapshot snapshot,
      ExecutionContext caller,
      InternalId expectedReferralId,
      ExternalReference candidateFacility,
      long exactReferralRevision) {
    Instant checkedAt = clock.instant();
    if (!expectedReferralId.equals(snapshot.referralId())) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.NOT_APPLICABLE,
          "REFERRAL_ID_MISMATCH",
          checkedAt);
    }
    if (exactReferralRevision != snapshot.referralRevision()) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.NOT_APPLICABLE,
          "REFERRAL_REVISION_MISMATCH",
          checkedAt);
    }
    RecommendationSnapshot.CandidateEvidence priorCandidate =
        snapshot.candidates().stream()
            .filter(candidate -> candidate.facility().equals(candidateFacility))
            .findFirst()
            .orElse(null);
    if (priorCandidate == null || priorCandidate.status() != CandidateStatus.RANKED_ELIGIBLE) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.NOT_APPLICABLE,
          "CANDIDATE_NOT_RANKED",
          checkedAt);
    }
    if (!snapshot.applicableUntil().isAfter(checkedAt)) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.NOT_APPLICABLE,
          "RECOMMENDATION_EXPIRED",
          checkedAt);
    }

    Optional<MatchingRecommendationPolicy> selectedPolicy;
    try {
      selectedPolicy = policies.activePolicy();
    } catch (RuntimeException unavailable) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.INDETERMINATE,
          "MATCHING_POLICY_UNAVAILABLE",
          checkedAt);
    }
    if (selectedPolicy.isEmpty()) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.INDETERMINATE,
          "MATCHING_POLICY_UNAVAILABLE",
          checkedAt);
    }
    MatchingRecommendationPolicy policy = selectedPolicy.orElseThrow();
    if (!snapshot.generatedAt().plus(policy.maximumRecommendationAge()).isAfter(checkedAt)) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.NOT_APPLICABLE,
          "RECOMMENDATION_EXPIRED",
          checkedAt);
    }
    if (!snapshot.matchingPolicy().equals(policy.matchingPolicy())
        || !snapshot.mappingPolicy().equals(policy.mappingPolicy())) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.NOT_APPLICABLE,
          "POLICY_VERSION_NOT_PERMITTED",
          checkedAt);
    }
    if (!policy.candidateOrganizations().contains(priorCandidate.organization())
        || snapshot.attribution().facility().filter(candidateFacility::equals).isPresent()) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.NOT_APPLICABLE,
          "CANDIDATE_OUT_OF_POLICY_SCOPE",
          checkedAt);
    }

    ReferralMatchingRequirement requirement;
    try {
      requirement =
          ownerEvidence.readSubmittedRequirement(
              caller.correlationId(),
              snapshot.attribution().organization(),
              snapshot.attribution().facility(),
              snapshot.referralId(),
              snapshot.referralRevision());
    } catch (PlatformProblemException unavailable) {
      return ownerReadFailure(
          snapshot,
          candidateFacility,
          checkedAt,
          unavailable,
          Set.of(
              PlatformErrorCode.REFERRAL_VERSION_CONFLICT, PlatformErrorCode.REFERRAL_NOT_FOUND));
    } catch (DataAccessException unavailable) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.INDETERMINATE,
          "REFERRAL_EVIDENCE_UNAVAILABLE",
          checkedAt);
    }
    if (!requirement.referralId().equals(snapshot.referralId())
        || requirement.revisionNumber() != snapshot.referralRevision()
        || !requirement.originOrganization().equals(snapshot.attribution().organization())
        || !requirement.originFacility().equals(snapshot.attribution().facility())) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.NOT_APPLICABLE,
          "REFERRAL_REVISION_MISMATCH",
          checkedAt);
    }

    var operationalRequirements =
        policy.capabilityMapping().resolve(requirement.requiredCareCapabilityCodes());
    if (!operationalRequirements.complete()
        || operationalRequirements.uniqueDimensionCodes().isEmpty()) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.NOT_APPLICABLE,
          "OPERATIONAL_MAPPING_INCOMPLETE",
          checkedAt);
    }
    if (!snapshotMappingMatches(snapshot, operationalRequirements.dimensionsByCapability())) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.INDETERMINATE,
          "POLICY_EVIDENCE_INCONSISTENT",
          checkedAt);
    }
    if (requirement
        .selectedDestinationOrganization()
        .filter(organization -> !organization.equals(priorCandidate.organization()))
        .isPresent()) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.NOT_APPLICABLE,
          "DESTINATION_ORGANIZATION_MISMATCH",
          checkedAt);
    }

    DirectoryMatchingCandidatePage.Candidate candidate;
    try {
      Optional<DirectoryMatchingCandidatePage.Candidate> current =
          ownerEvidence.readCandidate(
              caller.correlationId(),
              priorCandidate.organization(),
              candidateFacility,
              requirement.requiredCareCapabilityCodes(),
              checkedAt);
      if (current.isEmpty()) {
        return result(
            snapshot,
            candidateFacility,
            RecommendationApplicabilityResult.Status.NOT_APPLICABLE,
            "CANDIDATE_FACILITY_NOT_FOUND",
            checkedAt);
      }
      candidate = current.orElseThrow();
    } catch (PlatformProblemException unavailable) {
      return ownerReadFailure(
          snapshot,
          candidateFacility,
          checkedAt,
          unavailable,
          Set.of(PlatformErrorCode.DIRECTORY_NOT_FOUND, PlatformErrorCode.DIRECTORY_INACTIVE));
    } catch (DataAccessException unavailable) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.INDETERMINATE,
          "DIRECTORY_EVIDENCE_UNAVAILABLE",
          checkedAt);
    }
    if (!candidate.organization().equals(priorCandidate.organization())
        || !"ACTIVE".equals(candidate.lifecycleStatus())) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.NOT_APPLICABLE,
          "CANDIDATE_FACILITY_INACTIVE_OR_CHANGED",
          checkedAt);
    }
    if (policy.destinationLocationRequired()
        && candidate
            .primaryLocation()
            .flatMap(DirectoryMatchingCandidatePage.Location::coordinate)
            .isEmpty()) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.NOT_APPLICABLE,
          "CANDIDATE_LOCATION_UNAVAILABLE",
          checkedAt);
    }
    RecommendationApplicabilityResult capabilityResult =
        evaluateCapabilities(snapshot, candidateFacility, candidate, checkedAt);
    if (capabilityResult != null) {
      return capabilityResult;
    }

    CapacityReadinessBatchEvaluation readiness;
    try {
      readiness =
          ownerEvidence.evaluateCandidateCapacityReadiness(
              caller.correlationId(),
              priorCandidate.organization(),
              candidateFacility,
              operationalRequirements.uniqueDimensionCodes(),
              checkedAt);
    } catch (PlatformProblemException | DataAccessException unavailable) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.INDETERMINATE,
          "CAPACITY_EVIDENCE_UNAVAILABLE",
          checkedAt);
    }
    if (readiness.facilities().size() != 1) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.INDETERMINATE,
          "CAPACITY_EVIDENCE_INCOMPLETE",
          checkedAt);
    }
    CapacityReadinessEvaluation capacity = readiness.facilities().getFirst();
    if (!capacity.facility().equals(candidateFacility)
        || !capacity.evaluationTime().equals(checkedAt)
        || !capacity.dimensions().stream()
            .map(org.ayuvahak.capacity.domain.CapacityDimensionReadiness::dimensionCode)
            .collect(Collectors.toSet())
            .equals(Set.copyOf(operationalRequirements.uniqueDimensionCodes()))) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.INDETERMINATE,
          "CAPACITY_EVIDENCE_INCOMPLETE",
          checkedAt);
    }
    if (capacity.outcome() == org.ayuvahak.capacity.domain.CapacityReadinessOutcome.READY) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.APPLICABLE,
          "ALL_APPLICABILITY_FACTS_CURRENT",
          checkedAt);
    }
    if (capacity.outcome() == org.ayuvahak.capacity.domain.CapacityReadinessOutcome.NOT_READY
        || capacity.outcome() == org.ayuvahak.capacity.domain.CapacityReadinessOutcome.STALE) {
      return result(
          snapshot,
          candidateFacility,
          RecommendationApplicabilityResult.Status.NOT_APPLICABLE,
          capacity.outcome().name(),
          checkedAt);
    }
    return result(
        snapshot,
        candidateFacility,
        RecommendationApplicabilityResult.Status.INDETERMINATE,
        capacity.outcome().name(),
        checkedAt);
  }

  private RecommendationApplicabilityResult evaluateCapabilities(
      RecommendationSnapshot snapshot,
      ExternalReference facility,
      DirectoryMatchingCandidatePage.Candidate candidate,
      Instant checkedAt) {
    Map<String, DirectoryMatchingCandidatePage.Capability> currentByCode =
        candidate.capabilities().stream()
            .collect(
                Collectors.toMap(
                    DirectoryMatchingCandidatePage.Capability::code, Function.identity()));
    for (String required :
        snapshot.resolvedMappings().stream()
            .map(RecommendationSnapshot.CapabilityDimensionMapping::capabilityCode)
            .toList()) {
      DirectoryMatchingCandidatePage.Capability evidence = currentByCode.get(required);
      if (evidence == null) {
        return result(
            snapshot,
            facility,
            RecommendationApplicabilityResult.Status.INDETERMINATE,
            "CAPABILITY_EVIDENCE_INCOMPLETE",
            checkedAt);
      }
      if ("SATISFIED".equals(evidence.outcome())
          && evidence.capabilityStatus().filter("ACTIVE"::equals).isPresent()
          && evidence.assertions().stream()
              .anyMatch(
                  assertion ->
                      "VERIFIED".equals(assertion.verificationStatus())
                          && !assertion.effectiveFrom().isAfter(checkedAt)
                          && assertion
                              .effectiveUntil()
                              .map(end -> end.isAfter(checkedAt))
                              .orElse(true)
                          && assertion.healthcareServiceActive())) {
        continue;
      }
      if (Set.of(
              "NO_ASSERTION",
              "INACTIVE_CAPABILITY",
              "NOT_EFFECTIVE",
              "INACTIVE_OFFERING",
              "NO_CURRENT_ASSERTION")
          .contains(evidence.outcome())) {
        return result(
            snapshot,
            facility,
            RecommendationApplicabilityResult.Status.NOT_APPLICABLE,
            "REQUIRED_CAPABILITY_NOT_CURRENT",
            checkedAt);
      }
      return result(
          snapshot,
          facility,
          RecommendationApplicabilityResult.Status.INDETERMINATE,
          "CAPABILITY_EVIDENCE_UNVERIFIED",
          checkedAt);
    }
    return null;
  }

  private static boolean snapshotMappingMatches(
      RecommendationSnapshot snapshot, Map<String, List<String>> activeMapping) {
    Map<String, List<String>> recorded =
        snapshot.resolvedMappings().stream()
            .collect(
                Collectors.toMap(
                    RecommendationSnapshot.CapabilityDimensionMapping::capabilityCode,
                    RecommendationSnapshot.CapabilityDimensionMapping::dimensionCodes));
    return recorded.equals(activeMapping);
  }

  private RecommendationApplicabilityResult ownerReadFailure(
      RecommendationSnapshot snapshot,
      ExternalReference candidateFacility,
      Instant checkedAt,
      PlatformProblemException failure,
      Set<PlatformErrorCode> notApplicableCodes) {
    return notApplicableCodes.contains(failure.code())
        ? result(
            snapshot,
            candidateFacility,
            RecommendationApplicabilityResult.Status.NOT_APPLICABLE,
            failure.code().name(),
            checkedAt)
        : result(
            snapshot,
            candidateFacility,
            RecommendationApplicabilityResult.Status.INDETERMINATE,
            "OWNER_EVIDENCE_UNAVAILABLE",
            checkedAt);
  }

  private static RecommendationApplicabilityResult result(
      RecommendationSnapshot snapshot,
      ExternalReference candidateFacility,
      RecommendationApplicabilityResult.Status status,
      String reasonCode,
      Instant checkedAt) {
    return new RecommendationApplicabilityResult(
        snapshot.id(), candidateFacility, status, List.of(reasonCode), checkedAt);
  }

  private static void requireDirectoryFacility(ExternalReference facility) {
    Objects.requireNonNull(facility, "candidate Facility");
    if (!"urn:ayuvahak:directory".equals(facility.issuer().value())
        || !"Facility".equals(facility.resourceType())) {
      throw new IllegalArgumentException("candidate Facility reference is invalid");
    }
    InternalId.parse(facility.value());
  }

  private void requireTrustedCaller(ExecutionContext caller) {
    Objects.requireNonNull(caller, "authorized Matching caller context");
    boolean human =
        caller.actor().kind() == ActorKind.HUMAN
            && caller.authenticationMechanism() == AuthenticationMechanism.HUMAN_ACCESS_TOKEN
            && caller.sourceKind() == org.ayuvahak.platform.core.SourceKind.INTERACTIVE_REQUEST;
    boolean service =
        caller.actor().kind() == ActorKind.SERVICE
            && ((caller.authenticationMechanism()
                        == AuthenticationMechanism.CONFIGURED_SERVICE_IDENTITY
                    && caller.sourceKind() == org.ayuvahak.platform.core.SourceKind.BACKGROUND_WORK)
                || (caller.authenticationMechanism()
                        == AuthenticationMechanism.SERVICE_CLIENT_CREDENTIALS
                    && caller.sourceKind() == org.ayuvahak.platform.core.SourceKind.INTEGRATION));
    if (!human && !service) {
      throw denied();
    }
    if (caller.scope().kind() != ExecutionScope.Kind.ORGANIZATION
        && caller.scope().kind() != ExecutionScope.Kind.FACILITY) {
      throw denied();
    }
  }

  private void requireReadAuthority(ExecutionContext requester) {
    Objects.requireNonNull(requester, "recommendation reader context");
    if (requester.actor().kind() != ActorKind.HUMAN
        || requester.authenticationMechanism() != AuthenticationMechanism.HUMAN_ACCESS_TOKEN
        || requester.sourceKind() != org.ayuvahak.platform.core.SourceKind.INTERACTIVE_REQUEST
        || (requester.scope().kind() != ExecutionScope.Kind.ORGANIZATION
            && requester.scope().kind() != ExecutionScope.Kind.FACILITY)) {
      throw denied();
    }
    AuthorizationDecision decision = authorization.evaluate(requester, "RecommendationRead");
    if (decision.isAllowed()) {
      return;
    }
    String reasonCode =
        decision.denialReason().orElse(null)
                == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE
            ? "AUTHORIZATION_STORE_UNAVAILABLE"
            : "AUTHORIZATION_DENIED";
    recordSecurityOutcome(requester, AuditOutcome.DENIED, reasonCode);
    if (reasonCode.equals("AUTHORIZATION_STORE_UNAVAILABLE")) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
          "Recommendation authority could not be verified safely.");
    }
    throw denied();
  }

  private static boolean scopeCanRead(ExecutionScope scope, RecommendationSnapshot snapshot) {
    if (!scope.organization().equals(Optional.of(snapshot.attribution().organization()))) {
      return false;
    }
    return scope.kind() != ExecutionScope.Kind.FACILITY
        || scope.facility().equals(snapshot.attribution().facility());
  }

  private void recordRead(
      ExecutionContext requester,
      String actionCode,
      InternalId targetId,
      AuditOutcome outcome,
      Optional<String> reasonCode) {
    recordTargetRead(requester, actionCode, "Recommendation", targetId, outcome, reasonCode);
  }

  private void recordTargetRead(
      ExecutionContext requester,
      String actionCode,
      String targetType,
      InternalId targetId,
      AuditOutcome outcome,
      Optional<String> reasonCode) {
    try {
      auditWriter.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SENSITIVE_READ,
              Optional.of(requester.actor()),
              Optional.of(requester.scope()),
              actionCode,
              targetType,
              Optional.of(targetId),
              clock.instant(),
              outcome,
              reasonCode,
              authenticationEvidence(requester),
              outcome == AuditOutcome.DENIED
                  ? AuditAuthorizationOutcome.DENIED
                  : AuditAuthorizationOutcome.ALLOWED,
              requester.correlationId(),
              Optional.of(targetContext(requester.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw auditUnavailable();
    }
  }

  private static AuditAuthenticationEvidence authenticationEvidence(ExecutionContext context) {
    return switch (context.authenticationMechanism()) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      default -> throw new IllegalArgumentException("verified actor authentication is required");
    };
  }

  private void recordSecurityOutcome(
      ExecutionContext requester, AuditOutcome outcome, String reasonCode) {
    try {
      auditWriter.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.of(requester.actor()),
              Optional.of(requester.scope()),
              "MATCHING_RECOMMENDATION_READ_DENIED",
              "Recommendation",
              Optional.empty(),
              clock.instant(),
              outcome,
              Optional.of(reasonCode),
              AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN,
              AuditAuthorizationOutcome.DENIED,
              requester.correlationId()));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw auditUnavailable();
    }
  }

  private static AuditTargetContext targetContext(ExecutionScope scope) {
    return new AuditTargetContext(scope.kind(), scope.organization(), scope.facility());
  }

  private static PlatformProblemException denied() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated actor is not authorized to read Recommendations.");
  }

  private static PlatformProblemException notFound() {
    return new PlatformProblemException(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.MATCHING_SNAPSHOT_NOT_FOUND,
        "The Recommendation is not available in the authorized scope.");
  }

  private static PlatformProblemException unavailable() {
    return new PlatformProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.MATCHING_UNAVAILABLE,
        "Recommendation history could not be read safely.");
  }

  private static PlatformProblemException auditUnavailable() {
    return new PlatformProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.AUDIT_UNAVAILABLE,
        "Recommendation evidence could not be disclosed because access evidence is unavailable.");
  }
}
