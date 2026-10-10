package org.ayuvahak.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.CandidateOutcome;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.CandidateResult;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.Completeness;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.Evaluation;
import org.ayuvahak.matching.domain.RecommendationRanker.CandidateStatus;
import org.ayuvahak.matching.domain.RecommendationRanker.Coordinate;
import org.ayuvahak.matching.domain.RecommendationRanker.MetricKind;
import org.ayuvahak.matching.domain.RecommendationRanker.Outcome;
import org.ayuvahak.matching.domain.RecommendationRanker.Policy;
import org.ayuvahak.matching.domain.RecommendationRanker.Ranking;
import org.ayuvahak.matching.domain.RecommendationRanker.Request;
import org.ayuvahak.matching.domain.RecommendationRanker.TravelTimeEstimate;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.junit.jupiter.api.Test;

class RecommendationRankerTest {

  private static final Instant EVALUATED_AT = Instant.parse("2026-10-10T12:00:00Z");
  private static final ExternalReference ORIGIN =
      ref("Facility", "00000000-0000-4000-8000-000000000001");
  private static final ExternalReference ORG = ref("Organization", "org-1");
  private static final ExternalReference NEAR =
      ref("Facility", "00000000-0000-4000-8000-000000000002");
  private static final ExternalReference FAR =
      ref("Facility", "00000000-0000-4000-8000-000000000003");
  private static final ExternalReference DISQUALIFIED =
      ref("Facility", "00000000-0000-4000-8000-000000000004");

  private final RecommendationRanker ranker = new RecommendationRanker();

  @Test
  void ranksOnlyEligibleCandidatesByStraightLineDistanceAndStableFacilityId() {
    Ranking ranking =
        ranker.rank(
            request(
                evaluation(
                    Completeness.COMPLETE, eligible(NEAR), eligible(FAR), ineligible(DISQUALIFIED)),
                Map.of(
                    NEAR, new Coordinate(72.81, 19.10),
                    FAR, new Coordinate(73.20, 19.10),
                    DISQUALIFIED, new Coordinate(72.80, 19.10)),
                new Policy(
                    MetricKind.STRAIGHT_LINE_DISTANCE,
                    false,
                    Map.of(DISQUALIFIED, 1, NEAR, 2, FAR, 3)),
                List.of()));

    assertThat(ranking.outcome()).isEqualTo(Outcome.COMPLETE);
    assertThat(ranking.candidates())
        .extracting(Ranking.CandidateRanking::facility, Ranking.CandidateRanking::status)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(NEAR, CandidateStatus.RANKED_ELIGIBLE),
            org.assertj.core.groups.Tuple.tuple(FAR, CandidateStatus.RANKED_ELIGIBLE),
            org.assertj.core.groups.Tuple.tuple(DISQUALIFIED, CandidateStatus.INELIGIBLE));
    assertThat(ranking.candidates().getFirst().rank()).isEqualTo(OptionalInt.of(1));
    assertThat(ranking.candidates().get(1).rank()).isEqualTo(OptionalInt.of(2));
    assertThat(ranking.candidates().getFirst().measure().orElseThrow().kind())
        .isEqualTo(MetricKind.STRAIGHT_LINE_DISTANCE);
    assertThat(ranking.candidates().getLast().rank()).isEmpty();
    assertThat(ranking.candidates().getLast().preferenceOrdinal()).isEmpty();
  }

  @Test
  void appliesConfiguredPreferenceOnlyAfterThePrimaryMetricAndBeforeTheIdTieBreak() {
    Ranking ranking =
        ranker.rank(
            request(
                evaluation(Completeness.COMPLETE, eligible(NEAR), eligible(FAR)),
                Map.of(NEAR, new Coordinate(72.9, 19.1), FAR, new Coordinate(72.9, 19.1)),
                new Policy(MetricKind.STRAIGHT_LINE_DISTANCE, false, Map.of(FAR, 1, NEAR, 2)),
                List.of()));

    assertThat(ranking.candidates())
        .extracting(Ranking.CandidateRanking::facility)
        .containsExactly(FAR, NEAR);
    assertThat(ranking.candidates().getFirst().preferenceOrdinal()).isEqualTo(OptionalInt.of(1));
  }

  @Test
  void usesFacilityUuidv4AsTheFinalStableTieBreak() {
    Ranking ranking =
        ranker.rank(
            request(
                evaluation(Completeness.COMPLETE, eligible(FAR), eligible(NEAR)),
                Map.of(NEAR, new Coordinate(72.9, 19.1), FAR, new Coordinate(72.9, 19.1)),
                new Policy(MetricKind.STRAIGHT_LINE_DISTANCE, false, Map.of()),
                List.of()));

    assertThat(ranking.candidates())
        .extracting(Ranking.CandidateRanking::facility)
        .containsExactly(NEAR, FAR);
  }

  @Test
  void usesCurrentTravelTimeWithSecondsAndSourceAttributionWhenAvailableForEveryCandidate() {
    Ranking ranking =
        ranker.rank(
            request(
                evaluation(Completeness.COMPLETE, eligible(NEAR), eligible(FAR)),
                Map.of(),
                new Policy(MetricKind.TRAVEL_TIME, true, Map.of()),
                List.of(travelTime(NEAR, 120), travelTime(FAR, 240))));

    assertThat(ranking.candidates().getFirst().facility()).isEqualTo(NEAR);
    assertThat(ranking.candidates().getFirst().measure().orElseThrow().kind())
        .isEqualTo(MetricKind.TRAVEL_TIME);
    assertThat(ranking.candidates().getFirst().measure().orElseThrow().unit()).isEqualTo("s");
    assertThat(ranking.candidates().getFirst().measure().orElseThrow().sourceCode())
        .isEqualTo("SYNTHETIC_ROUTER");
  }

  @Test
  void travelTimeFallbackUsesOneConsistentMetricForTheWholeEligibleSet() {
    Ranking ranking =
        ranker.rank(
            request(
                evaluation(Completeness.COMPLETE, eligible(NEAR), eligible(FAR)),
                Map.of(NEAR, new Coordinate(72.81, 19.1), FAR, new Coordinate(73.0, 19.1)),
                new Policy(MetricKind.TRAVEL_TIME, true, Map.of()),
                List.of(travelTime(NEAR, 120))));

    assertThat(ranking.outcome()).isEqualTo(Outcome.COMPLETE);
    assertThat(ranking.candidates())
        .allSatisfy(
            candidate ->
                assertThat(candidate.measure().orElseThrow().kind())
                    .isEqualTo(MetricKind.STRAIGHT_LINE_DISTANCE));
    assertThat(ranking.reasons()).contains("DISTANCE_FALLBACK_USED");
  }

  @Test
  void missingTravelTimeWithoutFallbackMakesOtherwiseEligibleCandidatesIndeterminate() {
    Ranking ranking =
        ranker.rank(
            request(
                evaluation(Completeness.COMPLETE, eligible(NEAR), eligible(FAR)),
                Map.of(NEAR, new Coordinate(72.81, 19.1), FAR, new Coordinate(73.0, 19.1)),
                new Policy(MetricKind.TRAVEL_TIME, false, Map.of()),
                List.of()));

    assertThat(ranking.outcome()).isEqualTo(Outcome.INDETERMINATE);
    assertThat(ranking.candidates())
        .allSatisfy(
            candidate -> {
              assertThat(candidate.status()).isEqualTo(CandidateStatus.INDETERMINATE);
              assertThat(candidate.rank()).isEmpty();
              assertThat(candidate.reasonCodes()).contains("TRAVEL_TIME_UNAVAILABLE");
            });
  }

  @Test
  void incompleteEligibilityNeverProducesAnyRankEvenForAnObservedEligibleFacility() {
    Ranking ranking =
        ranker.rank(
            request(
                evaluation(Completeness.INCOMPLETE, eligible(NEAR), eligible(FAR)),
                Map.of(NEAR, new Coordinate(72.81, 19.1), FAR, new Coordinate(73.0, 19.1)),
                new Policy(MetricKind.STRAIGHT_LINE_DISTANCE, false, Map.of()),
                List.of()));

    assertThat(ranking.outcome()).isEqualTo(Outcome.INDETERMINATE);
    assertThat(ranking.candidates())
        .allSatisfy(candidate -> assertThat(candidate.rank()).isEmpty());
  }

  @Test
  void missingOriginOrDestinationCoordinateIsIndeterminateNeverDistanceZero() {
    Ranking originMissing =
        ranker.rank(
            new Request(
                evaluation(Completeness.COMPLETE, eligible(NEAR)),
                ORIGIN,
                Optional.empty(),
                Map.of(NEAR, new Coordinate(72.81, 19.1)),
                EVALUATED_AT,
                new Policy(MetricKind.STRAIGHT_LINE_DISTANCE, false, Map.of()),
                List.of()));
    Ranking destinationMissing =
        ranker.rank(
            request(
                evaluation(Completeness.COMPLETE, eligible(NEAR)),
                Map.of(),
                new Policy(MetricKind.STRAIGHT_LINE_DISTANCE, false, Map.of()),
                List.of()));

    assertThat(originMissing.candidates().getFirst().status())
        .isEqualTo(CandidateStatus.INDETERMINATE);
    assertThat(originMissing.candidates().getFirst().reasonCodes())
        .contains("ORIGIN_LOCATION_UNKNOWN");
    assertThat(destinationMissing.candidates().getFirst().status())
        .isEqualTo(CandidateStatus.INDETERMINATE);
    assertThat(destinationMissing.candidates().getFirst().reasonCodes())
        .contains("DESTINATION_LOCATION_UNKNOWN");
  }

  @Test
  void missingOriginFacilityCanBeRepresentedAsAnIndeterminateEvaluation() {
    Ranking ranking =
        ranker.rank(
            new Request(
                evaluation(Completeness.COMPLETE, eligible(NEAR)),
                Optional.empty(),
                Optional.empty(),
                Map.of(NEAR, new Coordinate(72.81, 19.1)),
                EVALUATED_AT,
                new Policy(MetricKind.STRAIGHT_LINE_DISTANCE, false, Map.of()),
                List.of()));

    assertThat(ranking.outcome()).isEqualTo(Outcome.INDETERMINATE);
    assertThat(ranking.candidates().getFirst().status()).isEqualTo(CandidateStatus.INDETERMINATE);
    assertThat(ranking.candidates().getFirst().reasonCodes()).contains("ORIGIN_LOCATION_UNKNOWN");
  }

  @Test
  void staleTravelTimeIsUnavailableAndNeverUsedForRanking() {
    TravelTimeEstimate stale =
        new TravelTimeEstimate(
            ORIGIN,
            NEAR,
            BigDecimal.valueOf(60),
            "SYNTHETIC_ROUTER",
            Optional.of(ref("RoutingProvider", "router-1")),
            EVALUATED_AT.minusSeconds(600),
            EVALUATED_AT.minusSeconds(1));
    Ranking ranking =
        ranker.rank(
            request(
                evaluation(Completeness.COMPLETE, eligible(NEAR)),
                Map.of(NEAR, new Coordinate(72.81, 19.1)),
                new Policy(MetricKind.TRAVEL_TIME, false, Map.of()),
                List.of(stale)));

    assertThat(ranking.candidates().getFirst().status()).isEqualTo(CandidateStatus.INDETERMINATE);
    assertThat(ranking.candidates().getFirst().reasonCodes()).contains("TRAVEL_TIME_UNAVAILABLE");
  }

  @Test
  void malformedTravelTimeCannotBeConstructed() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TravelTimeEstimate(
                    ORIGIN,
                    NEAR,
                    BigDecimal.valueOf(-1),
                    "SYNTHETIC_ROUTER",
                    Optional.empty(),
                    EVALUATED_AT,
                    EVALUATED_AT.plusSeconds(60)));
  }

  private static Request request(
      Evaluation evaluation,
      Map<ExternalReference, Coordinate> coordinates,
      Policy policy,
      List<TravelTimeEstimate> estimates) {
    return new Request(
        evaluation,
        ORIGIN,
        Optional.of(new Coordinate(72.8, 19.1)),
        coordinates,
        EVALUATED_AT,
        policy,
        estimates);
  }

  private static Evaluation evaluation(Completeness completeness, CandidateResult... candidates) {
    return new Evaluation(completeness, List.of(), List.of(candidates));
  }

  private static CandidateResult eligible(ExternalReference facility) {
    return new CandidateResult(facility, ORG, CandidateOutcome.ELIGIBLE, List.of());
  }

  private static CandidateResult ineligible(ExternalReference facility) {
    return new CandidateResult(
        facility,
        ORG,
        CandidateOutcome.INELIGIBLE,
        List.of(
            new CandidateEligibilityEvaluator.Reason(
                CandidateEligibilityEvaluator.ReasonCode.CAPABILITY_NOT_ASSERTED,
                Optional.of("SYN-EMERGENCY"))));
  }

  private static TravelTimeEstimate travelTime(ExternalReference facility, long seconds) {
    return new TravelTimeEstimate(
        ORIGIN,
        facility,
        BigDecimal.valueOf(seconds),
        "SYNTHETIC_ROUTER",
        Optional.of(ref("RoutingProvider", "router-1")),
        EVALUATED_AT.minusSeconds(1),
        EVALUATED_AT.plusSeconds(60));
  }

  private static ExternalReference ref(String type, String value) {
    return new ExternalReference(new IssuerId("urn:ayuvahak:synthetic"), type, value);
  }
}
