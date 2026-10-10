package org.ayuvahak.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.CandidateEvidence;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.CandidateOutcome;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.CapabilityAssessment;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.CapabilityEvidence;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.CapacityEvidence;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.CapacityStatus;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.Completeness;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.DimensionReadiness;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.Evaluation;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.FacilityStatus;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.Request;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.junit.jupiter.api.Test;

class CandidateEligibilityEvaluatorTest {

  private static final Instant EVALUATED_AT = Instant.parse("2026-10-10T12:00:00Z");
  private static final String CAPABILITY = "SYN-EMERGENCY";
  private static final String DIMENSION_A = "SYN-TEAM-READY";
  private static final String DIMENSION_B = "SYN-OR-READY";
  private static final ExternalReference ORGANIZATION = ref("Organization", "org-1");
  private static final ExternalReference FACILITY = ref("Facility", "facility-1");
  private static final ExternalReference ASSERTION =
      ref("FacilityCapabilityAssertion", "assertion-1");

  private final CandidateEligibilityEvaluator evaluator = new CandidateEligibilityEvaluator();

  @Test
  void returnsEligibleOnlyForDirectSatisfiedCapabilityAndEveryCurrentReadyDimension() {
    Evaluation result =
        evaluator.evaluate(
            request(
                List.of(
                    candidate(
                        FACILITY, ORGANIZATION, FacilityStatus.ACTIVE, satisfied(CAPABILITY))),
                List.of(capacity(FACILITY, CapacityStatus.READY, DIMENSION_A, DIMENSION_B)),
                completeRequirements(),
                true,
                true,
                false,
                List.of()));

    assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
    assertThat(result.candidates())
        .singleElement()
        .satisfies(
            candidate -> {
              assertThat(candidate.outcome()).isEqualTo(CandidateOutcome.ELIGIBLE);
              assertThat(candidate.reasons()).isEmpty();
            });
  }

  @Test
  void knownHardFailureCannotBeOverriddenByMissingOrUncertainCapacity() {
    CandidateEvidence candidate =
        candidate(FACILITY, ORGANIZATION, FacilityStatus.ACTIVE, notAsserted(CAPABILITY));
    Evaluation result =
        evaluator.evaluate(
            request(
                List.of(candidate),
                List.of(),
                completeRequirements(),
                true,
                false,
                false,
                List.of()));

    assertThat(result.candidates())
        .singleElement()
        .satisfies(
            evaluated -> {
              assertThat(evaluated.outcome()).isEqualTo(CandidateOutcome.INELIGIBLE);
              assertThat(evaluated.reasonCodes()).contains("CAPABILITY_NOT_ASSERTED");
            });
  }

  @Test
  void selectedDestinationOrganizationIsAnExplicitHardConstraint() {
    Request unconstrained =
        request(
            List.of(
                candidate(FACILITY, ORGANIZATION, FacilityStatus.ACTIVE, satisfied(CAPABILITY))),
            List.of(capacity(FACILITY, CapacityStatus.READY, DIMENSION_A, DIMENSION_B)),
            completeRequirements(),
            true,
            true,
            false,
            List.of());
    Request constrained =
        new Request(
            unconstrained.requiredCapabilityCodes(),
            unconstrained.operationalRequirements(),
            unconstrained.candidates(),
            unconstrained.capacityEvidence(),
            unconstrained.candidateOrganizationScope(),
            Optional.of(ref("Organization", "selected-org")),
            unconstrained.permittedGeographyCodes(),
            unconstrained.evaluationTime(),
            unconstrained.destinationLocationRequired(),
            unconstrained.directoryEvidenceComplete(),
            unconstrained.capacityEvidenceAvailable());

    Evaluation result = evaluator.evaluate(constrained);

    assertThat(result.candidates())
        .singleElement()
        .satisfies(
            candidate -> {
              assertThat(candidate.outcome()).isEqualTo(CandidateOutcome.INELIGIBLE);
              assertThat(candidate.reasonCodes()).contains("DESTINATION_ORGANIZATION_MISMATCH");
            });
  }

  @Test
  void unverifiedCapabilityAndStaleCapacityRemainIndeterminate() {
    Evaluation result =
        evaluator.evaluate(
            request(
                List.of(
                    candidate(
                        FACILITY, ORGANIZATION, FacilityStatus.ACTIVE, uncertain(CAPABILITY))),
                List.of(capacity(FACILITY, CapacityStatus.STALE, DIMENSION_A, DIMENSION_B)),
                completeRequirements(),
                true,
                true,
                false,
                List.of()));

    assertThat(result.candidates())
        .singleElement()
        .satisfies(
            candidate -> {
              assertThat(candidate.outcome()).isEqualTo(CandidateOutcome.INDETERMINATE);
              assertThat(candidate.reasonCodes())
                  .contains("CAPABILITY_EVIDENCE_UNKNOWN", "CAPACITY_EVIDENCE_STALE");
            });
  }

  @Test
  void inactiveCapabilityIsAnExplicitHardDisqualification() {
    Evaluation result =
        evaluator.evaluate(
            request(
                List.of(
                    candidate(
                        FACILITY,
                        ORGANIZATION,
                        FacilityStatus.ACTIVE,
                        new CapabilityEvidence(
                            CAPABILITY,
                            CapabilityAssessment.INACTIVE_CAPABILITY,
                            List.of(ASSERTION)))),
                List.of(capacity(FACILITY, CapacityStatus.READY, DIMENSION_A, DIMENSION_B)),
                completeRequirements(),
                true,
                true,
                false,
                List.of()));

    assertThat(result.candidates())
        .singleElement()
        .satisfies(
            candidate -> {
              assertThat(candidate.outcome()).isEqualTo(CandidateOutcome.INELIGIBLE);
              assertThat(candidate.reasonCodes()).contains("CAPABILITY_INACTIVE");
            });
  }

  @Test
  void unknownConflictedAndUnavailableCapacityNeverProducePositiveEligibility() {
    Map<CapacityStatus, String> expectedReasons =
        Map.of(
            CapacityStatus.UNKNOWN, "CAPACITY_EVIDENCE_UNKNOWN",
            CapacityStatus.CONFLICTED, "CAPACITY_EVIDENCE_CONFLICTED",
            CapacityStatus.UNAVAILABLE, "CAPACITY_EVIDENCE_UNAVAILABLE");

    expectedReasons.forEach(
        (status, expectedReason) -> {
          Evaluation result =
              evaluator.evaluate(
                  request(
                      List.of(
                          candidate(
                              FACILITY,
                              ORGANIZATION,
                              FacilityStatus.ACTIVE,
                              satisfied(CAPABILITY))),
                      List.of(capacity(FACILITY, status, DIMENSION_A, DIMENSION_B)),
                      completeRequirements(),
                      true,
                      true,
                      false,
                      List.of()));

          assertThat(result.candidates())
              .as("capacity status %s", status)
              .singleElement()
              .satisfies(
                  candidate -> {
                    assertThat(candidate.outcome()).isEqualTo(CandidateOutcome.INDETERMINATE);
                    assertThat(candidate.reasonCodes()).contains(expectedReason);
                  });
        });
  }

  @Test
  void notReadyIsIneligibleWhileMissingLocationIsNeverTreatedAsZero() {
    Evaluation result =
        evaluator.evaluate(
            request(
                List.of(
                    candidate(
                        FACILITY,
                        ORGANIZATION,
                        FacilityStatus.ACTIVE,
                        Optional.empty(),
                        satisfied(CAPABILITY))),
                List.of(capacity(FACILITY, CapacityStatus.NOT_READY, DIMENSION_A, DIMENSION_B)),
                completeRequirements(),
                true,
                true,
                true,
                List.of()));

    assertThat(result.candidates())
        .singleElement()
        .satisfies(
            candidate -> {
              assertThat(candidate.outcome()).isEqualTo(CandidateOutcome.INELIGIBLE);
              assertThat(candidate.reasonCodes())
                  .contains("CAPACITY_NOT_READY", "DESTINATION_LOCATION_UNKNOWN");
            });
  }

  @Test
  void aMissingLocationWithReadyCapacityRemainsIndeterminateAndUnrankable() {
    Evaluation result =
        evaluator.evaluate(
            request(
                List.of(
                    candidate(
                        FACILITY,
                        ORGANIZATION,
                        FacilityStatus.ACTIVE,
                        Optional.empty(),
                        satisfied(CAPABILITY))),
                List.of(capacity(FACILITY, CapacityStatus.READY, DIMENSION_A, DIMENSION_B)),
                completeRequirements(),
                true,
                true,
                true,
                List.of()));

    assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
    assertThat(result.rankableCandidates()).isEmpty();
    assertThat(result.candidates())
        .singleElement()
        .satisfies(
            candidate -> {
              assertThat(candidate.outcome()).isEqualTo(CandidateOutcome.INDETERMINATE);
              assertThat(candidate.reasonCodes()).containsExactly("DESTINATION_LOCATION_UNKNOWN");
            });
  }

  @Test
  void oneKnownNotReadyDimensionDisqualifiesEvenWhenAnotherIsStale() {
    CapacityEvidence mixedCapacity =
        new CapacityEvidence(
            FACILITY,
            EVALUATED_AT,
            List.of(
                new DimensionReadiness(DIMENSION_A, CapacityStatus.NOT_READY),
                new DimensionReadiness(DIMENSION_B, CapacityStatus.STALE)));
    Evaluation result =
        evaluator.evaluate(
            request(
                List.of(
                    candidate(
                        FACILITY, ORGANIZATION, FacilityStatus.ACTIVE, satisfied(CAPABILITY))),
                List.of(mixedCapacity),
                completeRequirements(),
                true,
                true,
                false,
                List.of()));

    assertThat(result.candidates())
        .singleElement()
        .satisfies(
            candidate -> {
              assertThat(candidate.outcome()).isEqualTo(CandidateOutcome.INELIGIBLE);
              assertThat(candidate.reasonCodes())
                  .contains("CAPACITY_NOT_READY", "CAPACITY_EVIDENCE_STALE");
            });
  }

  @Test
  void unresolvedMappingAndGeographyNeverProducePositiveEligibility() {
    ResolvedOperationalRequirements mappingWithGap =
        new CapabilityOperationalMapping("SYNTHETIC_MAPPING", 1, Map.of())
            .resolve(List.of(CAPABILITY));
    Evaluation result =
        evaluator.evaluate(
            request(
                List.of(
                    candidate(
                        FACILITY, ORGANIZATION, FacilityStatus.ACTIVE, satisfied(CAPABILITY))),
                List.of(),
                mappingWithGap,
                true,
                false,
                false,
                List.of("SYN-GEOGRAPHY-7")));

    assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
    assertThat(result.reasonCodes())
        .contains("OPERATIONAL_MAPPING_MISSING", "GEOGRAPHIC_CONSTRAINT_UNRESOLVED");
    assertThat(result.candidates())
        .singleElement()
        .satisfies(
            candidate -> {
              assertThat(candidate.outcome()).isEqualTo(CandidateOutcome.INDETERMINATE);
              assertThat(candidate.reasonCodes())
                  .contains("OPERATIONAL_MAPPING_MISSING", "GEOGRAPHIC_CONSTRAINT_UNRESOLVED");
            });
  }

  @Test
  void incompleteCandidateUniverseIsReportedEvenWhenKnownCandidateLooksEligible() {
    Evaluation result =
        evaluator.evaluate(
            request(
                List.of(
                    candidate(
                        FACILITY, ORGANIZATION, FacilityStatus.ACTIVE, satisfied(CAPABILITY))),
                List.of(capacity(FACILITY, CapacityStatus.READY, DIMENSION_A, DIMENSION_B)),
                completeRequirements(),
                false,
                true,
                false,
                List.of()));

    assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
    assertThat(result.reasonCodes()).contains("DIRECTORY_EVIDENCE_INCOMPLETE");
    assertThat(result.rankableCandidates()).isEmpty();
    assertThat(result.candidates())
        .singleElement()
        .extracting(CandidateEligibilityEvaluator.CandidateResult::outcome)
        .isEqualTo(CandidateOutcome.ELIGIBLE);
  }

  @Test
  void evaluatesTheDeduplicatedDimensionUnionAndMissingRowsFailClosed() {
    ResolvedOperationalRequirements shared =
        new CapabilityOperationalMapping(
                "SYNTHETIC_MAPPING",
                2,
                Map.of(
                    "SYN-EMERGENCY",
                    Set.of(DIMENSION_A),
                    "SYN-TRAUMA",
                    Set.of(DIMENSION_A, DIMENSION_B)))
            .resolve(List.of(CAPABILITY, "SYN-TRAUMA"));
    Evaluation result =
        evaluator.evaluate(
            request(
                List.of(
                    candidate(
                        FACILITY,
                        ORGANIZATION,
                        FacilityStatus.ACTIVE,
                        satisfied(CAPABILITY),
                        satisfied("SYN-TRAUMA"))),
                List.of(capacity(FACILITY, CapacityStatus.READY, DIMENSION_A)),
                shared,
                true,
                true,
                false,
                List.of()));

    assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
    assertThat(result.reasonCodes()).contains("CAPACITY_RESULT_INCOMPLETE");
    assertThat(result.candidates())
        .singleElement()
        .satisfies(
            candidate -> {
              assertThat(candidate.outcome()).isEqualTo(CandidateOutcome.INDETERMINATE);
              assertThat(candidate.reasonCodes()).contains("CAPACITY_RESULT_INCOMPLETE");
            });
  }

  private static Request request(
      List<CandidateEvidence> candidates,
      List<CapacityEvidence> capacity,
      ResolvedOperationalRequirements mapping,
      boolean directoryComplete,
      boolean capacityAvailable,
      boolean locationRequired,
      List<String> permittedGeographies) {
    List<String> requiredCapabilities =
        mapping.dimensionsByCapability().isEmpty()
            ? List.of(CAPABILITY)
            : mapping.dimensionsByCapability().keySet().stream().sorted().toList();
    if (!mapping.missingCapabilityCodes().isEmpty()) {
      requiredCapabilities = List.of(CAPABILITY);
    }
    return new Request(
        requiredCapabilities,
        mapping,
        candidates,
        capacity,
        Set.of(ORGANIZATION),
        Optional.empty(),
        permittedGeographies,
        EVALUATED_AT,
        locationRequired,
        directoryComplete,
        capacityAvailable);
  }

  private static ResolvedOperationalRequirements completeRequirements() {
    return new CapabilityOperationalMapping(
            "SYNTHETIC_MAPPING", 1, Map.of(CAPABILITY, Set.of(DIMENSION_A, DIMENSION_B)))
        .resolve(List.of(CAPABILITY));
  }

  private static CandidateEvidence candidate(
      ExternalReference facility,
      ExternalReference organization,
      FacilityStatus status,
      CapabilityEvidence... capabilities) {
    return candidate(
        facility,
        organization,
        status,
        Optional.of(new CandidateEligibilityEvaluator.Coordinate(72.8, 19.1)),
        capabilities);
  }

  private static CandidateEvidence candidate(
      ExternalReference facility,
      ExternalReference organization,
      FacilityStatus status,
      Optional<CandidateEligibilityEvaluator.Coordinate> coordinate,
      CapabilityEvidence... capabilities) {
    return new CandidateEvidence(facility, organization, status, coordinate, List.of(capabilities));
  }

  private static CapabilityEvidence satisfied(String code) {
    return new CapabilityEvidence(code, CapabilityAssessment.SATISFIED, List.of(ASSERTION));
  }

  private static CapabilityEvidence notAsserted(String code) {
    return new CapabilityEvidence(code, CapabilityAssessment.NOT_ASSERTED, List.of());
  }

  private static CapabilityEvidence uncertain(String code) {
    return new CapabilityEvidence(code, CapabilityAssessment.UNVERIFIED, List.of(ASSERTION));
  }

  private static CapacityEvidence capacity(
      ExternalReference facility, CapacityStatus status, String... dimensions) {
    return new CapacityEvidence(
        facility,
        EVALUATED_AT,
        java.util.Arrays.stream(dimensions)
            .map(dimension -> new DimensionReadiness(dimension, status))
            .toList());
  }

  private static ExternalReference ref(String type, String value) {
    return new ExternalReference(new IssuerId("urn:ayuvahak:synthetic"), type, value);
  }
}
