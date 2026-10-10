package org.ayuvahak.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.ayuvahak.matching.domain.RecommendationRanker.CandidateStatus;
import org.ayuvahak.matching.domain.RecommendationRanker.Measure;
import org.ayuvahak.matching.domain.RecommendationRanker.MetricKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.junit.jupiter.api.Test;

class RecommendationSnapshotTest {

  private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
  private static final ExternalReference FACILITY =
      ref("Facility", "00000000-0000-4000-8000-000000000001");
  private static final ExternalReference FACILITY_2 =
      ref("Facility", "00000000-0000-4000-8000-000000000002");
  private static final ExternalReference ORGANIZATION = ref("Organization", "org-1");

  @Test
  void capturesImmutablePolicyMappingEvidenceAndAttribution() {
    List<String> dimensions = new ArrayList<>(List.of("SYN-TEAM-READY", "SYN-OR-READY"));
    List<RecommendationSnapshot.CandidateReason> reasons =
        new ArrayList<>(
            List.of(new RecommendationSnapshot.CandidateReason("ELIGIBLE", Optional.empty())));
    RecommendationSnapshot snapshot = snapshot(candidate(1, reasons), dimensions);
    dimensions.clear();
    reasons.clear();

    assertThat(snapshot.resolvedMappings().getFirst().dimensionCodes())
        .containsExactly("SYN-OR-READY", "SYN-TEAM-READY");
    assertThat(snapshot.candidates().getFirst().reasons())
        .extracting(RecommendationSnapshot.CandidateReason::code)
        .containsExactly("ELIGIBLE");
    assertThat(snapshot.attribution().purposeCode()).isEqualTo("MATCHING_EVALUATION");
    assertThat(snapshot.toString()).doesNotContain("patient");
  }

  @Test
  void incompleteEvaluationCannotContainRankedCandidate() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new RecommendationSnapshot(
                    InternalId.generate(),
                    InternalId.generate(),
                    1,
                    NOW,
                    NOW.plusSeconds(30),
                    policyVersion("MatchingPolicy", "policy-1"),
                    policyVersion("CapabilityOperationalRequirementPolicy", "mapping-1"),
                    List.of(mapping(List.of("SYN-DIMENSION"))),
                    RecommendationSnapshot.EvaluationOutcome.INDETERMINATE,
                    List.of("GLOBAL_DATA_UNAVAILABLE"),
                    List.of(candidate(1, List.of())),
                    attribution()));
  }

  @Test
  void duplicateOrNonContiguousRanksAreRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new RecommendationSnapshot(
                    InternalId.generate(),
                    InternalId.generate(),
                    1,
                    NOW,
                    NOW.plusSeconds(30),
                    policyVersion("MatchingPolicy", "policy-1"),
                    policyVersion("CapabilityOperationalRequirementPolicy", "mapping-1"),
                    List.of(mapping(List.of("SYN-DIMENSION"))),
                    RecommendationSnapshot.EvaluationOutcome.COMPLETE,
                    List.of(),
                    List.of(candidate(1, List.of()), candidate(FACILITY_2, 1, List.of())),
                    attribution()));
  }

  @Test
  void nonrankedCandidateCannotCarryMetricOrRank() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new RecommendationSnapshot.CandidateEvidence(
                    FACILITY,
                    ORGANIZATION,
                    CandidateStatus.INDETERMINATE,
                    OptionalInt.of(1),
                    OptionalInt.empty(),
                    Optional.of(measure()),
                    List.of(),
                    List.of()));
  }

  @Test
  void invalidEvidenceValidityIntervalIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new RecommendationSnapshot.EvidenceFact(
                    "CAPACITY_DIMENSION",
                    "SYN-OR-READY",
                    ref("CapacityObservation", "observation-1"),
                    ref("CapacitySource", "source-1"),
                    2,
                    OptionalLong.of(2),
                    Optional.of("capacity-projection-v1"),
                    "READY",
                    Optional.of(BigDecimal.ONE),
                    Optional.of("bed"),
                    NOW,
                    Optional.empty(),
                    Optional.of(NOW),
                    Optional.empty()));
  }

  @Test
  void applicabilityCannotOutliveCapacityEvidence() {
    RecommendationSnapshot.EvidenceFact capacityFact =
        new RecommendationSnapshot.EvidenceFact(
            "CAPACITY_DIMENSION",
            "SYN-OR-READY",
            ref("CapacityObservation", "observation-1"),
            ref("CapacitySource", "source-1"),
            2,
            OptionalLong.of(3),
            Optional.of("capacity-projection-v1"),
            "READY",
            Optional.of(BigDecimal.ONE),
            Optional.of("bed"),
            NOW.minusSeconds(5),
            Optional.of(NOW.minusSeconds(2)),
            Optional.of(NOW.plusSeconds(10)),
            Optional.empty());

    assertThat(capacityFact.receivedAt()).isEqualTo(Optional.of(NOW.minusSeconds(2)));

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                snapshot(
                    candidate(FACILITY, 1, List.of(), List.of(capacityFact)),
                    List.of("SYN-DIMENSION")));
  }

  private static RecommendationSnapshot snapshot(
      RecommendationSnapshot.CandidateEvidence candidate, List<String> dimensionCodes) {
    return new RecommendationSnapshot(
        InternalId.generate(),
        InternalId.generate(),
        1,
        NOW,
        NOW.plusSeconds(30),
        policyVersion("MatchingPolicy", "policy-1"),
        policyVersion("CapabilityOperationalRequirementPolicy", "mapping-1"),
        List.of(mapping(dimensionCodes)),
        RecommendationSnapshot.EvaluationOutcome.COMPLETE,
        List.of(),
        List.of(candidate),
        attribution());
  }

  private static RecommendationSnapshot.CandidateEvidence candidate(
      int rank, List<RecommendationSnapshot.CandidateReason> reasons) {
    return candidate(FACILITY, rank, reasons);
  }

  private static RecommendationSnapshot.CandidateEvidence candidate(
      ExternalReference facility, int rank, List<RecommendationSnapshot.CandidateReason> reasons) {
    return candidate(
        facility,
        rank,
        reasons,
        List.of(
            new RecommendationSnapshot.EvidenceFact(
                "FACILITY_CAPABILITY_ASSERTION",
                "SYN-EMERGENCY",
                ref("FacilityCapabilityAssertion", "assertion-1"),
                ref("DirectorySource", "directory-1"),
                4,
                OptionalLong.empty(),
                Optional.empty(),
                "VERIFIED_ACTIVE_DIRECT",
                Optional.empty(),
                Optional.empty(),
                NOW.minusSeconds(10),
                Optional.empty(),
                Optional.of(NOW.plusSeconds(30)),
                Optional.of(ref("Provenance", "provenance-1")))));
  }

  private static RecommendationSnapshot.CandidateEvidence candidate(
      ExternalReference facility,
      int rank,
      List<RecommendationSnapshot.CandidateReason> reasons,
      List<RecommendationSnapshot.EvidenceFact> evidence) {
    return new RecommendationSnapshot.CandidateEvidence(
        facility,
        ORGANIZATION,
        CandidateStatus.RANKED_ELIGIBLE,
        OptionalInt.of(rank),
        OptionalInt.empty(),
        Optional.of(measure()),
        reasons,
        evidence);
  }

  private static RecommendationSnapshot.CapabilityDimensionMapping mapping(
      List<String> dimensions) {
    return new RecommendationSnapshot.CapabilityDimensionMapping("SYN-EMERGENCY", dimensions);
  }

  private static RecommendationSnapshot.PolicyVersion policyVersion(String type, String id) {
    return new RecommendationSnapshot.PolicyVersion(ref(type, id), 1);
  }

  private static RecommendationSnapshot.Attribution attribution() {
    return new RecommendationSnapshot.Attribution(
        ActorReference.human(new IssuerId("urn:identity:synthetic"), "operator-1"),
        ORGANIZATION,
        Optional.of(FACILITY),
        new CorrelationId("matching-correlation-1"),
        "MATCHING_EVALUATION");
  }

  private static Measure measure() {
    return new Measure(
        MetricKind.STRAIGHT_LINE_DISTANCE,
        "STRAIGHT_LINE_HAVERSINE_V1",
        BigDecimal.valueOf(3200),
        "m",
        NOW,
        NOW.plusSeconds(30),
        Optional.empty());
  }

  private static ExternalReference ref(String type, String id) {
    return new ExternalReference(new IssuerId("urn:ayuvahak:synthetic"), type, id);
  }
}
