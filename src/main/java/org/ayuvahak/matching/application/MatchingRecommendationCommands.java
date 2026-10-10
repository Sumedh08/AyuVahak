package org.ayuvahak.matching.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.ayuvahak.capacity.application.CapacityReadinessBatchEvaluation;
import org.ayuvahak.directory.application.DirectoryMatchingCandidatePage;
import org.ayuvahak.directory.application.DirectoryMatchingOriginLocation;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator;
import org.ayuvahak.matching.domain.MatchingRecommendationPolicy;
import org.ayuvahak.matching.domain.RecommendationRanker;
import org.ayuvahak.matching.domain.RecommendationRanker.TravelTimeEstimate;
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
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.referral.application.ReferralMatchingRequirement;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit, authorized Matching command for one current submitted Referral revision. */
@Service
@Lazy
public final class MatchingRecommendationCommands {

  private static final int CREATED_STATUS = HttpStatus.CREATED.value();
  private static final String COMMAND_BUSY_DETAIL =
      "A matching Recommendation command is still being processed; retry with the same key.";

  private final MatchingOwnerEvidenceReader ownerEvidence;
  private final MatchingPolicyCatalog policies;
  private final MatchingStore store;
  private final MatchingEligibilityApplicationService eligibility;
  private final ObjectProvider<TravelTimePort> travelTimePort;
  private final RecommendationRanker ranker = new RecommendationRanker();
  private final RecommendationSnapshotFactory snapshots = new RecommendationSnapshotFactory();
  private final AuditWriter auditWriter;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public MatchingRecommendationCommands(
      MatchingOwnerEvidenceReader ownerEvidence,
      MatchingPolicyCatalog policies,
      MatchingStore store,
      MatchingEligibilityApplicationService eligibility,
      ObjectProvider<TravelTimePort> travelTimePort,
      AuditWriter auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock) {
    this.ownerEvidence = Objects.requireNonNull(ownerEvidence, "Matching owner evidence reader");
    this.policies = Objects.requireNonNull(policies, "active Matching policy catalog");
    this.store = Objects.requireNonNull(store, "Matching store");
    this.eligibility = Objects.requireNonNull(eligibility, "eligibility service");
    this.travelTimePort = Objects.requireNonNull(travelTimePort, "travel-time port provider");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public MatchingRecommendationCommandResult generate(
      ExecutionContext requester,
      InternalId referralId,
      long revisionNumber,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(requester, "recommendation requester context");
    Objects.requireNonNull(referralId, "Referral id");
    Objects.requireNonNull(fingerprint, "request fingerprint");
    if (revisionNumber < 1) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MATCHING_REQUEST_INVALID,
          "A positive submitted Referral revision is required.");
    }

    ownerEvidence.authorizeRecommendationRequest(requester);
    CommandReceiptKey receiptKey = commandKey(requester, referralId, rawIdempotencyKey);
    MatchingRecommendationCommandResult replayed =
        execute(() -> store.findReceipt(receiptKey, fingerprint).map(this::replay).orElse(null));
    if (replayed != null) {
      return replayed;
    }

    MatchingRecommendationPolicy policy =
        policies
            .activePolicy()
            .orElseThrow(
                () ->
                    problem(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        PlatformErrorCode.MATCHING_POLICY_UNCONFIGURED,
                        "No active Matching recommendation policy is configured."));
    Instant evaluationTime = clock.instant();
    ReferralMatchingRequirement requirement =
        readSubmittedRequirement(requester, referralId, revisionNumber);
    if (!requirement.referralId().equals(referralId)
        || requirement.revisionNumber() != revisionNumber) {
      throw staleReferral();
    }

    var operationalRequirements =
        policy.capabilityMapping().resolve(requirement.requiredCareCapabilityCodes());
    List<DirectoryMatchingCandidatePage> directoryPages =
        readCandidatePages(requester, requirement, policy, evaluationTime);
    List<DirectoryMatchingCandidatePage.Candidate> directoryCandidates =
        directoryPages.stream().flatMap(page -> page.candidates().stream()).toList();
    Optional<CapacityReadinessBatchEvaluation> capacityBatch =
        operationalRequirements.complete()
                && !operationalRequirements.uniqueDimensionCodes().isEmpty()
            ? readCapacity(
                requester,
                directoryCandidates,
                operationalRequirements.uniqueDimensionCodes(),
                evaluationTime)
            : Optional.empty();
    Optional<DirectoryMatchingOriginLocation> originLocation =
        readOriginLocation(requester, requirement, evaluationTime);

    CandidateEligibilityEvaluator.Evaluation evaluationResult =
        eligibility.evaluate(
            requirement,
            operationalRequirements,
            directoryPages,
            capacityBatch,
            policy.candidateOrganizations(),
            evaluationTime,
            policy.destinationLocationRequired());
    List<TravelTimeEstimate> travelTimeEstimates =
        readTravelTimeEstimates(
            policy,
            requirement,
            originLocation,
            directoryCandidates,
            evaluationResult,
            evaluationTime);
    RecommendationRanker.Ranking ranking =
        ranker.rank(
            new RecommendationRanker.Request(
                evaluationResult,
                requirement.originFacility(),
                originCoordinate(originLocation),
                destinationCoordinates(directoryCandidates),
                evaluationTime,
                policy.rankingPolicy(),
                travelTimeEstimates));

    ReferralMatchingRequirement finalRequirement =
        readSubmittedRequirement(requester, referralId, revisionNumber);
    if (!sameSubmittedRevision(requirement, finalRequirement)) {
      throw staleReferral();
    }

    RecommendationSnapshot snapshot =
        snapshots.create(
            InternalId.generate(),
            requirement,
            policy,
            evaluationResult,
            ranking,
            directoryPages,
            capacityBatch.map(CapacityReadinessBatchEvaluation::facilities).orElse(List.of()),
            originLocation,
            evaluationTime,
            requester);
    return execute(
        () -> {
          store.configureBoundedReceiptWait();
          MatchingStore.ReceiptClaim claim =
              store.claimReceipt(receiptKey, fingerprint, clock.instant());
          MatchingRecommendationCommandResult completedByConcurrentRequest = replay(claim);
          if (completedByConcurrentRequest != null) {
            return completedByConcurrentRequest;
          }
          store.insertSnapshot(snapshot);
          recordMutation(requester, snapshot);
          store.completeReceipt(receiptKey, CREATED_STATUS, snapshot.id(), clock.instant());
          return new MatchingRecommendationCommandResult(snapshot, false, CREATED_STATUS);
        });
  }

  private List<DirectoryMatchingCandidatePage> readCandidatePages(
      ExecutionContext requester,
      ReferralMatchingRequirement requirement,
      MatchingRecommendationPolicy policy,
      Instant evaluationTime) {
    List<DirectoryMatchingCandidatePage> pages = new ArrayList<>();
    List<ExternalReference> organizations =
        policy.candidateOrganizations().stream()
            .sorted(Comparator.comparing(MatchingRecommendationCommands::referenceKey))
            .toList();
    for (ExternalReference organization : organizations) {
      pages.addAll(
          readCandidates(
              requester, requirement, organization, policy.directoryPageSize(), evaluationTime));
    }
    return List.copyOf(pages);
  }

  private List<DirectoryMatchingCandidatePage> readCandidates(
      ExecutionContext requester,
      ReferralMatchingRequirement requirement,
      ExternalReference organization,
      int size,
      Instant evaluationTime) {
    try {
      return ownerEvidence.findCandidatePages(
          requester, requirement, organization, size, evaluationTime);
    } catch (PlatformProblemException failure) {
      throw mapOwnerFailure(failure);
    }
  }

  private Optional<CapacityReadinessBatchEvaluation> readCapacity(
      ExecutionContext requester,
      List<DirectoryMatchingCandidatePage.Candidate> candidates,
      List<String> dimensions,
      Instant evaluationTime) {
    try {
      return ownerEvidence.evaluateCapacityReadiness(
          requester, candidates, dimensions, evaluationTime);
    } catch (PlatformProblemException failure) {
      throw mapOwnerFailure(failure);
    }
  }

  private Optional<DirectoryMatchingOriginLocation> readOriginLocation(
      ExecutionContext requester, ReferralMatchingRequirement requirement, Instant evaluationTime) {
    try {
      return ownerEvidence.readOriginLocation(requester, requirement, evaluationTime);
    } catch (PlatformProblemException failure) {
      throw mapOwnerFailure(failure);
    }
  }

  private List<TravelTimeEstimate> readTravelTimeEstimates(
      MatchingRecommendationPolicy policy,
      ReferralMatchingRequirement requirement,
      Optional<DirectoryMatchingOriginLocation> originLocation,
      List<DirectoryMatchingCandidatePage.Candidate> candidates,
      CandidateEligibilityEvaluator.Evaluation evaluation,
      Instant evaluationTime) {
    if (policy.rankingPolicy().primaryMetric() != RecommendationRanker.MetricKind.TRAVEL_TIME) {
      return List.of();
    }
    TravelTimePort provider = travelTimePort.getIfAvailable();
    Optional<ExternalReference> originFacility = requirement.originFacility();
    Optional<ExternalReference> originLocationReference =
        originLocation
            .flatMap(DirectoryMatchingOriginLocation::primaryLocation)
            .map(DirectoryMatchingOriginLocation.Location::reference);
    if (provider == null || originFacility.isEmpty() || originLocationReference.isEmpty()) {
      return List.of();
    }

    Set<ExternalReference> eligibleFacilities =
        evaluation.candidates().stream()
            .filter(
                candidate ->
                    candidate.outcome() == CandidateEligibilityEvaluator.CandidateOutcome.ELIGIBLE)
            .map(CandidateEligibilityEvaluator.CandidateResult::facility)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    Map<ExternalReference, List<ExternalReference>> facilitiesByLocation = new HashMap<>();
    for (DirectoryMatchingCandidatePage.Candidate candidate : candidates) {
      if (!eligibleFacilities.contains(candidate.facility())) {
        continue;
      }
      candidate
          .primaryLocation()
          .map(DirectoryMatchingCandidatePage.Location::reference)
          .ifPresent(
              location ->
                  facilitiesByLocation
                      .computeIfAbsent(location, ignored -> new ArrayList<>())
                      .add(candidate.facility()));
    }
    if (facilitiesByLocation.isEmpty()) {
      return List.of();
    }

    List<ExternalReference> requestedLocations =
        facilitiesByLocation.keySet().stream()
            .sorted(Comparator.comparing(MatchingRecommendationCommands::referenceKey))
            .toList();
    TravelTimePort.TravelTimeRequest request =
        new TravelTimePort.TravelTimeRequest(
            originLocationReference.orElseThrow(), requestedLocations, evaluationTime);
    List<TravelTimePort.Estimate> estimates;
    try {
      estimates = Objects.requireNonNull(provider.estimate(request), "travel-time port result");
    } catch (TravelTimePort.UnavailableException unavailable) {
      return List.of();
    }
    Set<ExternalReference> requested = Set.copyOf(requestedLocations);
    Set<ExternalReference> returned = new HashSet<>();
    List<TravelTimeEstimate> matchingEstimates = new ArrayList<>();
    for (TravelTimePort.Estimate estimate : estimates) {
      if (estimate == null
          || !requested.contains(estimate.destinationLocation())
          || !returned.add(estimate.destinationLocation())) {
        throw evidenceUnavailable();
      }
      for (ExternalReference facility : facilitiesByLocation.get(estimate.destinationLocation())) {
        matchingEstimates.add(
            new TravelTimeEstimate(
                originFacility.orElseThrow(),
                facility,
                estimate.seconds(),
                estimate.sourceCode(),
                estimate.sourceSystem(),
                estimate.observedAt(),
                estimate.validUntil()));
      }
    }
    return List.copyOf(matchingEstimates);
  }

  private ReferralMatchingRequirement readSubmittedRequirement(
      ExecutionContext requester, InternalId referralId, long revisionNumber) {
    try {
      return ownerEvidence.readSubmittedRequirement(requester, referralId, revisionNumber);
    } catch (PlatformProblemException failure) {
      throw mapOwnerFailure(failure);
    }
  }

  private MatchingRecommendationCommandResult replay(MatchingStore.ReceiptClaim claim) {
    return switch (claim.state()) {
      case OWNER -> null;
      case KEY_REUSED ->
          throw problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
              "The idempotency key was already used for a different Matching command.");
      case IN_PROGRESS ->
          throw problem(
              HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, COMMAND_BUSY_DETAIL);
      case REPLAY -> {
        InternalId snapshotId = claim.resultReference().orElseThrow();
        RecommendationSnapshot snapshot =
            store
                .findSnapshot(snapshotId)
                .orElseThrow(
                    () ->
                        problem(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            PlatformErrorCode.MATCHING_UNAVAILABLE,
                            "The recorded Matching result is unavailable."));
        yield new MatchingRecommendationCommandResult(
            snapshot, true, claim.responseStatus().orElse(CREATED_STATUS));
      }
    };
  }

  private void recordMutation(ExecutionContext requester, RecommendationSnapshot snapshot) {
    try {
      auditWriter.recordMutation(
          new AuditEvidence(
              AuditActionKind.PROTECTED_MUTATION,
              Optional.of(requester.actor()),
              Optional.of(requester.scope()),
              "MATCHING_RECOMMENDATION_GENERATE",
              "Recommendation",
              Optional.of(snapshot.id()),
              clock.instant(),
              AuditOutcome.SUCCEEDED,
              Optional.of("MATCHING_EVALUATION"),
              AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN,
              AuditAuthorizationOutcome.ALLOWED,
              requester.correlationId(),
              Optional.of(targetContext(requester.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The Recommendation and its required audit evidence could not be committed.");
    }
  }

  private <T> T execute(java.util.function.Supplier<T> operation) {
    try {
      return transaction.execute(status -> operation.get());
    } catch (CannotAcquireLockException busy) {
      throw problem(
          HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, COMMAND_BUSY_DETAIL);
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The Recommendation and its required audit evidence could not be committed.");
    } catch (DataAccessException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.MATCHING_UNAVAILABLE,
          "The Matching command could not be completed safely.");
    }
  }

  private static CommandReceiptKey commandKey(
      ExecutionContext requester, InternalId referralId, String rawIdempotencyKey) {
    try {
      return CommandReceiptKey.from(
          requester, "recommendation.generate", "recommendations/" + referralId, rawIdempotencyKey);
    } catch (IllegalArgumentException invalid) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MATCHING_REQUEST_INVALID,
          "A valid Idempotency-Key is required for this Matching command.");
    }
  }

  private static PlatformProblemException mapOwnerFailure(PlatformProblemException failure) {
    if (failure.code() == PlatformErrorCode.REFERRAL_VERSION_CONFLICT) {
      return staleReferral();
    }
    if (failure.code() == PlatformErrorCode.REFERRAL_NOT_FOUND) {
      return problem(
          HttpStatus.NOT_FOUND,
          PlatformErrorCode.MATCHING_REFERRAL_NOT_FOUND,
          "The submitted Referral revision is not available in the authorized origin scope.");
    }
    return evidenceUnavailable();
  }

  private static Optional<RecommendationRanker.Coordinate> originCoordinate(
      Optional<DirectoryMatchingOriginLocation> originLocation) {
    return originLocation
        .flatMap(DirectoryMatchingOriginLocation::primaryLocation)
        .flatMap(DirectoryMatchingOriginLocation.Location::coordinate)
        .map(
            coordinate ->
                new RecommendationRanker.Coordinate(coordinate.longitude(), coordinate.latitude()));
  }

  private static Map<ExternalReference, RecommendationRanker.Coordinate> destinationCoordinates(
      List<DirectoryMatchingCandidatePage.Candidate> candidates) {
    Map<ExternalReference, RecommendationRanker.Coordinate> coordinates = new HashMap<>();
    candidates.forEach(
        candidate ->
            candidate
                .primaryLocation()
                .flatMap(DirectoryMatchingCandidatePage.Location::coordinate)
                .ifPresent(
                    coordinate ->
                        coordinates.put(
                            candidate.facility(),
                            new RecommendationRanker.Coordinate(
                                coordinate.longitude(), coordinate.latitude()))));
    return Map.copyOf(coordinates);
  }

  private static boolean sameSubmittedRevision(
      ReferralMatchingRequirement first, ReferralMatchingRequirement second) {
    return first.referralId().equals(second.referralId())
        && first.revisionNumber() == second.revisionNumber()
        && first.currentReferralVersion() == second.currentReferralVersion()
        && first.originOrganization().equals(second.originOrganization())
        && first.originFacility().equals(second.originFacility())
        && first.requiredCareCapabilityCodes().equals(second.requiredCareCapabilityCodes())
        && first.requestedStart().equals(second.requestedStart())
        && first.requestedEnd().equals(second.requestedEnd())
        && first.permittedGeographyCodes().equals(second.permittedGeographyCodes())
        && first.selectedDestinationOrganization().equals(second.selectedDestinationOrganization())
        && first.priorityCode().equals(second.priorityCode())
        && first.submittedAt().equals(second.submittedAt());
  }

  private static AuditTargetContext targetContext(
      org.ayuvahak.platform.executioncontext.ExecutionScope scope) {
    return new AuditTargetContext(scope.kind(), scope.organization(), scope.facility());
  }

  private static String referenceKey(ExternalReference reference) {
    return reference.issuer().value() + "|" + reference.resourceType() + "|" + reference.value();
  }

  private static PlatformProblemException staleReferral() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.MATCHING_REFERRAL_REVISION_STALE,
        "The submitted Referral revision changed before the Recommendation was saved.");
  }

  private static PlatformProblemException evidenceUnavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.MATCHING_EVIDENCE_UNAVAILABLE,
        "Required owner evidence or its authorization is unavailable; no Recommendation was saved.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
