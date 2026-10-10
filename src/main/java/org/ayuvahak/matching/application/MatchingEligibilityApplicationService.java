package org.ayuvahak.matching.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.ayuvahak.capacity.application.CapacityReadinessBatchEvaluation;
import org.ayuvahak.capacity.domain.CapacityReadinessEvaluation;
import org.ayuvahak.directory.application.DirectoryMatchingCandidatePage;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.CandidateEvidence;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.CapabilityAssessment;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.CapacityEvidence;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.CapacityStatus;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.DimensionReadiness;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.FacilityStatus;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.Request;
import org.ayuvahak.matching.domain.ResolvedOperationalRequirements;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.referral.application.ReferralMatchingRequirement;
import org.springframework.stereotype.Service;

/** Adapts complete owner projections into Matching's framework-independent eligibility model. */
@Service
public final class MatchingEligibilityApplicationService {

  private final CandidateEligibilityEvaluator evaluator = new CandidateEligibilityEvaluator();

  public CandidateEligibilityEvaluator.Evaluation evaluate(
      ReferralMatchingRequirement requirement,
      ResolvedOperationalRequirements operationalRequirements,
      List<DirectoryMatchingCandidatePage> directoryPages,
      Optional<CapacityReadinessBatchEvaluation> capacityReadiness,
      Set<ExternalReference> candidateOrganizationScope,
      Instant evaluationTime,
      boolean destinationLocationRequired) {
    Objects.requireNonNull(requirement, "submitted Referral requirement");
    Objects.requireNonNull(operationalRequirements, "operational requirements");
    directoryPages = List.copyOf(Objects.requireNonNull(directoryPages, "Directory pages"));
    capacityReadiness = Objects.requireNonNull(capacityReadiness, "Capacity readiness");
    candidateOrganizationScope =
        Set.copyOf(
            Objects.requireNonNull(candidateOrganizationScope, "candidate organization scope"));
    Objects.requireNonNull(evaluationTime, "evaluation time");

    List<DirectoryMatchingCandidatePage> orderedPages =
        directoryPages.stream()
            .sorted(
                Comparator.comparing(
                        (DirectoryMatchingCandidatePage page) -> referenceKey(page.organization()))
                    .thenComparingInt(DirectoryMatchingCandidatePage::page))
            .toList();
    List<CandidateEvidence> candidates = new ArrayList<>();
    boolean directoryComplete =
        pagesComplete(orderedPages, candidateOrganizationScope, evaluationTime);
    orderedPages.forEach(
        page ->
            page.candidates()
                .forEach(candidate -> candidates.add(toCandidate(candidate, evaluationTime))));

    List<CapacityEvidence> capacityEvidence = new ArrayList<>();
    capacityReadiness.ifPresent(
        batch ->
            batch.facilities().forEach(result -> capacityEvidence.add(toCapacityEvidence(result))));
    boolean capacityAvailable = capacityReadiness.isPresent();

    return evaluator.evaluate(
        new Request(
            requirement.requiredCareCapabilityCodes(),
            operationalRequirements,
            candidates,
            capacityEvidence,
            candidateOrganizationScope,
            requirement.selectedDestinationOrganization(),
            requirement.permittedGeographyCodes(),
            evaluationTime,
            destinationLocationRequired,
            directoryComplete,
            capacityAvailable));
  }

  private static boolean pagesComplete(
      List<DirectoryMatchingCandidatePage> pages,
      Set<ExternalReference> organizationScope,
      Instant evaluationTime) {
    if (organizationScope.isEmpty()) {
      return false;
    }
    Map<ExternalReference, List<DirectoryMatchingCandidatePage>> byOrganization = new HashMap<>();
    pages.forEach(
        page ->
            byOrganization
                .computeIfAbsent(page.organization(), ignored -> new ArrayList<>())
                .add(page));
    if (!byOrganization.keySet().equals(organizationScope)) {
      return false;
    }
    for (ExternalReference organization : organizationScope) {
      List<DirectoryMatchingCandidatePage> organizationPages =
          byOrganization.getOrDefault(organization, List.of()).stream()
              .sorted(Comparator.comparingInt(DirectoryMatchingCandidatePage::page))
              .toList();
      if (organizationPages.isEmpty()) {
        return false;
      }
      int expectedPage = 0;
      int expectedSize = organizationPages.getFirst().size();
      for (int index = 0; index < organizationPages.size(); index++) {
        DirectoryMatchingCandidatePage page = organizationPages.get(index);
        if (page.page() != expectedPage++
            || page.size() != expectedSize
            || !page.evaluatedAt().equals(evaluationTime)
            || (index < organizationPages.size() - 1 && !page.hasMore())
            || (index == organizationPages.size() - 1 && page.hasMore())) {
          return false;
        }
      }
    }
    return true;
  }

  private static CandidateEvidence toCandidate(
      DirectoryMatchingCandidatePage.Candidate candidate, Instant evaluationTime) {
    FacilityStatus facilityStatus =
        switch (candidate.lifecycleStatus()) {
          case "ACTIVE" -> FacilityStatus.ACTIVE;
          case "INACTIVE" -> FacilityStatus.INACTIVE;
          default -> FacilityStatus.UNKNOWN;
        };
    List<CandidateEligibilityEvaluator.CapabilityEvidence> capabilities =
        candidate.capabilities().stream()
            .map(
                capability -> {
                  CapabilityAssessment assessment = assessment(capability);
                  List<ExternalReference> qualifyingAssertions =
                      capability.assertions().stream()
                          .filter(assertion -> assertion.verificationStatus().equals("VERIFIED"))
                          .filter(assertion -> !assertion.effectiveFrom().isAfter(evaluationTime))
                          .filter(
                              assertion ->
                                  assertion
                                      .effectiveUntil()
                                      .map(until -> until.isAfter(evaluationTime))
                                      .orElse(true))
                          .filter(assertion -> assertion.healthcareServiceActive())
                          .map(DirectoryMatchingCandidatePage.Assertion::reference)
                          .toList();
                  return new CandidateEligibilityEvaluator.CapabilityEvidence(
                      capability.code(), assessment, qualifyingAssertions);
                })
            .toList();
    return new CandidateEvidence(
        candidate.facility(),
        candidate.organization(),
        facilityStatus,
        candidate
            .primaryLocation()
            .flatMap(DirectoryMatchingCandidatePage.Location::coordinate)
            .map(
                coordinate ->
                    new CandidateEligibilityEvaluator.Coordinate(
                        coordinate.longitude(), coordinate.latitude())),
        capabilities);
  }

  private static CapabilityAssessment assessment(
      DirectoryMatchingCandidatePage.Capability capability) {
    if ("SATISFIED".equals(capability.outcome())
        && !capability.capabilityStatus().equals(Optional.of("ACTIVE"))) {
      return CapabilityAssessment.UNKNOWN;
    }
    if ("NO_ASSERTION".equals(capability.outcome())) {
      return CapabilityAssessment.NOT_ASSERTED;
    }
    try {
      return CapabilityAssessment.valueOf(capability.outcome());
    } catch (IllegalArgumentException | NullPointerException invalidOutcome) {
      return CapabilityAssessment.UNKNOWN;
    }
  }

  private static CapacityEvidence toCapacityEvidence(CapacityReadinessEvaluation result) {
    List<DimensionReadiness> dimensions =
        result.dimensions().stream()
            .map(
                dimension ->
                    new DimensionReadiness(
                        dimension.dimensionCode(),
                        CapacityStatus.valueOf(dimension.outcome().name())))
            .toList();
    return new CapacityEvidence(result.facility(), result.evaluationTime(), dimensions);
  }

  private static String referenceKey(ExternalReference reference) {
    return reference.issuer().value() + "|" + reference.resourceType() + "|" + reference.value();
  }
}
