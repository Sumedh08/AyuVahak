package org.ayuvahak.matching.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.ayuvahak.capacity.domain.CapacityDimensionReadiness;
import org.ayuvahak.capacity.domain.CapacityObservation;
import org.ayuvahak.capacity.domain.CapacityReadinessEvaluation;
import org.ayuvahak.directory.application.DirectoryMatchingCandidatePage;
import org.ayuvahak.directory.application.DirectoryMatchingOriginLocation;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.CandidateResult;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.Evaluation;
import org.ayuvahak.matching.domain.MatchingRecommendationPolicy;
import org.ayuvahak.matching.domain.RecommendationRanker;
import org.ayuvahak.matching.domain.RecommendationRanker.CandidateStatus;
import org.ayuvahak.matching.domain.RecommendationRanker.Ranking;
import org.ayuvahak.matching.domain.RecommendationSnapshot;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.referral.application.ReferralMatchingRequirement;

/** Converts owner evidence and deterministic evaluation results into an immutable explanation. */
public final class RecommendationSnapshotFactory {

  private static final IssuerId CAPACITY_ISSUER = new IssuerId("urn:ayuvahak:capacity");
  private static final IssuerId INTERNAL_ISSUER = new IssuerId("urn:ayuvahak:internal");
  private static final ExternalReference DIRECTORY_SYSTEM =
      new ExternalReference(INTERNAL_ISSUER, "System", "directory");

  public RecommendationSnapshot create(
      InternalId recommendationId,
      ReferralMatchingRequirement referral,
      MatchingRecommendationPolicy policy,
      Evaluation evaluation,
      Ranking ranking,
      List<DirectoryMatchingCandidatePage> pages,
      List<CapacityReadinessEvaluation> capacityEvaluations,
      Optional<DirectoryMatchingOriginLocation> originLocation,
      Instant generatedAt,
      ExecutionContext requester) {
    Map<ExternalReference, DirectoryMatchingCandidatePage.Candidate> candidatesByFacility =
        new HashMap<>();
    pages.forEach(
        page ->
            page.candidates()
                .forEach(candidate -> candidatesByFacility.put(candidate.facility(), candidate)));
    Map<ExternalReference, CapacityReadinessEvaluation> capacityByFacility = new HashMap<>();
    capacityEvaluations.forEach(result -> capacityByFacility.put(result.facility(), result));
    Map<ExternalReference, CandidateResult> eligibilityByFacility = new HashMap<>();
    evaluation.candidates().forEach(result -> eligibilityByFacility.put(result.facility(), result));

    List<RecommendationSnapshot.CandidateEvidence> candidateEvidence =
        ranking.candidates().stream()
            .map(
                ranked -> {
                  DirectoryMatchingCandidatePage.Candidate directoryCandidate =
                      candidatesByFacility.get(ranked.facility());
                  if (directoryCandidate == null) {
                    throw new IllegalArgumentException(
                        "a ranked candidate must have Directory owner evidence");
                  }
                  CandidateResult eligibility = eligibilityByFacility.get(ranked.facility());
                  if (eligibility == null) {
                    throw new IllegalArgumentException(
                        "a ranked candidate must have eligibility evidence");
                  }
                  java.util.Set<String> eligibilityReasonCodes =
                      eligibility.reasons().stream()
                          .map(reason -> reason.code().name())
                          .collect(java.util.stream.Collectors.toUnmodifiableSet());
                  List<RecommendationSnapshot.CandidateReason> reasons =
                      java.util.stream.Stream.concat(
                              eligibility.reasons().stream()
                                  .map(
                                      reason ->
                                          new RecommendationSnapshot.CandidateReason(
                                              reason.code().name(), reason.subjectCode())),
                              ranked.reasonCodes().stream()
                                  .filter(code -> !eligibilityReasonCodes.contains(code))
                                  .map(
                                      code ->
                                          new RecommendationSnapshot.CandidateReason(
                                              code, Optional.empty())))
                          .distinct()
                          .sorted(
                              Comparator.comparing(RecommendationSnapshot.CandidateReason::code)
                                  .thenComparing(reason -> reason.subjectCode().orElse("")))
                          .toList();
                  List<RecommendationSnapshot.DirectoryCapabilityAssessment>
                      directoryCapabilityAssessments =
                          directoryCandidate.capabilities().stream()
                              .sorted(
                                  Comparator.comparing(
                                      DirectoryMatchingCandidatePage.Capability::code))
                              .map(
                                  capability ->
                                      new RecommendationSnapshot.DirectoryCapabilityAssessment(
                                          capability.code(),
                                          capability.outcome(),
                                          capability.capabilityVersion().isPresent()
                                              ? OptionalLong.of(
                                                  capability.capabilityVersion().orElseThrow())
                                              : OptionalLong.empty(),
                                          capability.capabilityStatus(),
                                          DIRECTORY_SYSTEM))
                              .toList();
                  return new RecommendationSnapshot.CandidateEvidence(
                      ranked.facility(),
                      ranked.organization(),
                      ranked.status(),
                      ranked.rank(),
                      ranked.preferenceOrdinal(),
                      ranked.measure(),
                      reasons,
                      directoryCapabilityAssessments,
                      evidenceFacts(
                          directoryCandidate,
                          originLocation,
                          capacityByFacility.get(ranked.facility()),
                          generatedAt));
                })
            .toList();

    var operationalRequirements =
        policy.capabilityMapping().resolve(referral.requiredCareCapabilityCodes());
    List<RecommendationSnapshot.CapabilityDimensionMapping> resolvedMappings =
        referral.requiredCareCapabilityCodes().stream()
            .sorted()
            .filter(operationalRequirements.dimensionsByCapability()::containsKey)
            .map(
                capability ->
                    new RecommendationSnapshot.CapabilityDimensionMapping(
                        capability,
                        operationalRequirements.dimensionsByCapability().get(capability)))
            .toList();
    List<String> evaluationReasons =
        java.util.stream.Stream.concat(
                evaluation.reasonCodes().stream(), ranking.reasons().stream())
            .distinct()
            .sorted()
            .toList();
    Instant applicableUntil =
        applicableUntil(
            generatedAt, policy, candidateEvidence, capacityByFacility.values().stream().toList());
    ExecutionScope scope = requester.scope();
    return new RecommendationSnapshot(
        recommendationId,
        referral.referralId(),
        referral.revisionNumber(),
        generatedAt,
        applicableUntil,
        policy.matchingPolicy(),
        policy.mappingPolicy(),
        resolvedMappings,
        ranking.outcome() == RecommendationRanker.Outcome.COMPLETE
            ? RecommendationSnapshot.EvaluationOutcome.COMPLETE
            : RecommendationSnapshot.EvaluationOutcome.INDETERMINATE,
        evaluationReasons,
        candidateEvidence,
        new RecommendationSnapshot.Attribution(
            requester.actor(),
            scope.organization().orElseThrow(),
            scope.facility(),
            requester.correlationId(),
            "MATCHING_EVALUATION"));
  }

  private static List<RecommendationSnapshot.EvidenceFact> evidenceFacts(
      DirectoryMatchingCandidatePage.Candidate candidate,
      Optional<DirectoryMatchingOriginLocation> originLocation,
      CapacityReadinessEvaluation capacity,
      Instant evaluatedAt) {
    List<RecommendationSnapshot.EvidenceFact> facts = new ArrayList<>();
    facts.add(
        new RecommendationSnapshot.EvidenceFact(
            "FACILITY_STATUS",
            "FACILITY",
            candidate.facility(),
            candidate.source(),
            candidate.version(),
            OptionalLong.empty(),
            Optional.empty(),
            candidate.lifecycleStatus(),
            Optional.empty(),
            Optional.empty(),
            candidate.provenance().observedAt(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    candidate
        .primaryLocation()
        .ifPresent(
            location ->
                facts.add(
                    new RecommendationSnapshot.EvidenceFact(
                        "DESTINATION_LOCATION",
                        "DESTINATION",
                        location.reference(),
                        location.source(),
                        location.version(),
                        OptionalLong.empty(),
                        Optional.empty(),
                        "PRIMARY",
                        Optional.empty(),
                        Optional.empty(),
                        location.provenance().observedAt(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())));
    originLocation.ifPresent(
        origin ->
            origin
                .primaryLocation()
                .ifPresent(
                    location ->
                        facts.add(
                            new RecommendationSnapshot.EvidenceFact(
                                "ORIGIN_LOCATION",
                                "ORIGIN",
                                location.reference(),
                                location.source(),
                                location.version(),
                                OptionalLong.empty(),
                                Optional.empty(),
                                "PRIMARY",
                                Optional.empty(),
                                Optional.empty(),
                                location.provenance().observedAt(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()))));
    candidate
        .capabilities()
        .forEach(
            capability ->
                capability
                    .assertions()
                    .forEach(
                        assertion ->
                            facts.add(
                                new RecommendationSnapshot.EvidenceFact(
                                    "CAPABILITY_ASSERTION",
                                    capability.code(),
                                    assertion.reference(),
                                    assertion.source(),
                                    assertion.version(),
                                    OptionalLong.empty(),
                                    Optional.empty(),
                                    assertion.verificationStatus(),
                                    Optional.empty(),
                                    Optional.empty(),
                                    assertion.provenance().observedAt(),
                                    Optional.empty(),
                                    assertion.effectiveUntil(),
                                    Optional.empty()))));
    if (capacity != null) {
      capacity
          .dimensions()
          .forEach(dimension -> facts.add(capacityFact(dimension, capacity, evaluatedAt)));
    }
    Map<String, RecommendationSnapshot.EvidenceFact> uniqueFacts = new java.util.TreeMap<>();
    facts.forEach(fact -> uniqueFacts.putIfAbsent(fact.factCode() + "|" + fact.reference(), fact));
    return List.copyOf(uniqueFacts.values());
  }

  private static RecommendationSnapshot.EvidenceFact capacityFact(
      CapacityDimensionReadiness dimension,
      CapacityReadinessEvaluation capacity,
      Instant evaluatedAt) {
    long sourceVersion =
        dimension.dimensionVersion().isPresent()
            ? dimension.dimensionVersion().getAsLong()
            : dimension.policyVersion().orElse(0L);
    if (sourceVersion < 1) {
      throw new IllegalArgumentException("Capacity readiness evidence lacks an owner version");
    }
    Optional<CapacityObservation> latestEvidence =
        dimension.evidence().stream().max(Comparator.comparing(CapacityObservation::receivedAt));
    Optional<Instant> validUntil =
        dimension.evidence().stream()
            .flatMap(observation -> observation.validUntil().stream())
            .min(Comparator.naturalOrder());
    Optional<ExternalReference> provenanceReference =
        latestEvidence.map(CapacityObservation::sourceObservation);
    Optional<BigDecimal> value = dimension.quantity();
    Optional<String> unit = dimension.unit();
    String outcome = dimension.outcome().name() + "_" + dimension.freshness().name();
    if (dimension.state().isPresent()) {
      outcome = outcome + "_" + dimension.state().orElseThrow();
    }
    return new RecommendationSnapshot.EvidenceFact(
        "CAPACITY_READINESS",
        dimension.dimensionCode(),
        new ExternalReference(CAPACITY_ISSUER, "CapacityDimension", dimension.dimensionCode()),
        new ExternalReference(INTERNAL_ISSUER, "System", "capacity"),
        sourceVersion,
        dimension.policyVersion(),
        Optional.of(dimension.projectionVersion()),
        outcome,
        value,
        unit,
        latestEvidence.map(CapacityObservation::observedAt).orElse(evaluatedAt),
        latestEvidence.map(CapacityObservation::receivedAt),
        validUntil,
        provenanceReference,
        Optional.of(
            new RecommendationSnapshot.CapacityProjectionDetails(
                dimension.reasonCodes(),
                dimension.fallbackUsed(),
                dimension.evidence().stream()
                    .sorted(
                        Comparator.comparing(CapacityObservation::sourceCode)
                            .thenComparing(CapacityObservation::observedAt)
                            .thenComparing(observation -> observation.id().toString()))
                    .map(
                        observation ->
                            new RecommendationSnapshot.CapacitySourceEvidence(
                                new ExternalReference(
                                    CAPACITY_ISSUER,
                                    "CapacityObservation",
                                    observation.id().toString()),
                                observation.sourceCode(),
                                observation.sourceObservation(),
                                observation.dimensionVersion(),
                                observation.policyVersion(),
                                observation.kind().name(),
                                observation.valueKind().map(Enum::name),
                                observation.quantity(),
                                observation.state(),
                                observation.unit(),
                                observation.observedAt(),
                                observation.receivedAt(),
                                observation.sourceValidUntil(),
                                observation.validUntil(),
                                observation.reasonCode()))
                    .toList())));
  }

  private static Instant applicableUntil(
      Instant generatedAt,
      MatchingRecommendationPolicy policy,
      List<RecommendationSnapshot.CandidateEvidence> candidates,
      List<CapacityReadinessEvaluation> capacities) {
    Instant deadline = generatedAt.plus(policy.maximumRecommendationAge());
    Map<ExternalReference, CapacityReadinessEvaluation> capacityByFacility = new HashMap<>();
    capacities.forEach(result -> capacityByFacility.put(result.facility(), result));
    for (RecommendationSnapshot.CandidateEvidence candidate : candidates) {
      if (candidate.status() != CandidateStatus.RANKED_ELIGIBLE) {
        continue;
      }
      CapacityReadinessEvaluation capacity = capacityByFacility.get(candidate.facility());
      if (capacity != null) {
        for (CapacityDimensionReadiness dimension : capacity.dimensions()) {
          for (CapacityObservation observation : dimension.evidence()) {
            Optional<Instant> validUntil = observation.validUntil();
            if (validUntil.isPresent()
                && validUntil.orElseThrow().isAfter(generatedAt)
                && validUntil.orElseThrow().isBefore(deadline)) {
              deadline = validUntil.orElseThrow();
            }
          }
        }
      }
      if (candidate.measure().isPresent()) {
        RecommendationRanker.Measure measure = candidate.measure().orElseThrow();
        if (measure.kind() == RecommendationRanker.MetricKind.TRAVEL_TIME
            && measure.validUntil().isBefore(deadline)) {
          deadline = measure.validUntil();
        }
      }
    }
    return deadline;
  }
}
