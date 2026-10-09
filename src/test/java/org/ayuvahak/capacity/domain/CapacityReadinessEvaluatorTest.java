package org.ayuvahak.capacity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.junit.jupiter.api.Test;

class CapacityReadinessEvaluatorTest {

  private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
  private static final String DIMENSION = "COORDINATION_BEDS";
  private static final ActorReference ACTOR =
      ActorReference.human(new IssuerId("https://identity.example.test"), "synthetic-reviewer");
  private final CapacityReadinessEvaluator evaluator = new CapacityReadinessEvaluator();

  @Test
  void explicitZeroPassesOnlyWhenTheConfiguredQuantityRuleAllowsZero() {
    CapacityDimension dimension = quantityDimension();
    CapacityPolicy passZero = quantityPolicy(dimension, BigDecimal.ZERO);
    CapacityPolicy requireOne = quantityPolicy(dimension, BigDecimal.ONE);

    var passing =
        evaluator.evaluate(
            DIMENSION,
            Optional.of(dimension),
            Optional.of(passZero),
            currentProjection(dimension, passZero, BigDecimal.ZERO));
    var failing =
        evaluator.evaluate(
            DIMENSION,
            Optional.of(dimension),
            Optional.of(requireOne),
            currentProjection(dimension, requireOne, BigDecimal.ZERO));

    assertThat(passing.outcome()).isEqualTo(CapacityReadinessOutcome.READY);
    assertThat(passing.quantity()).contains(BigDecimal.ZERO);
    assertThat(failing.outcome()).isEqualTo(CapacityReadinessOutcome.NOT_READY);
    assertThat(failing.reasonCodes()).contains("BELOW_MINIMUM_QUANTITY");
  }

  @Test
  void missingDimensionOrPolicyNeverProducesReady() {
    CapacityDimension dimension = quantityDimension();
    CapacityPolicy policy = quantityPolicy(dimension, BigDecimal.ONE);
    CapacityEvidenceProjection projection = currentProjection(dimension, policy, BigDecimal.TEN);

    assertThat(
            evaluator
                .evaluate(DIMENSION, Optional.empty(), Optional.of(policy), projection)
                .outcome())
        .isEqualTo(CapacityReadinessOutcome.UNKNOWN);
    assertThat(
            evaluator
                .evaluate(DIMENSION, Optional.of(dimension), Optional.empty(), projection)
                .outcome())
        .isEqualTo(CapacityReadinessOutcome.UNKNOWN);
  }

  @Test
  void controlledStateReadinessUsesOnlyConfiguredPassingStates() {
    CapacityDimension dimension =
        CapacityDimension.version(
            InternalId.generate(),
            1,
            new CapacityDimensionDefinition(
                "SERVICE_STATE",
                "Service state",
                "Synthetic service availability state",
                CapacityValueKind.STATE,
                null,
                null,
                null,
                Set.of("AVAILABLE", "UNAVAILABLE"),
                true,
                false,
                "TEST_CONFIGURATION"),
            ACTOR,
            NOW);
    CapacityPolicy policy =
        CapacityPolicy.version(
            InternalId.generate(),
            1,
            new CapacityPolicyDefinition(
                "SERVICE_STATE",
                dimension.version(),
                List.of(new CapacitySourcePriority("manual", 0, false, false)),
                600,
                300L,
                30,
                null,
                Set.of("AVAILABLE"),
                "TEST_CONFIGURATION"),
            ACTOR,
            NOW);

    assertThat(
            evaluator
                .evaluate(
                    "SERVICE_STATE",
                    Optional.of(dimension),
                    Optional.of(policy),
                    stateProjection(dimension, policy, "AVAILABLE"))
                .outcome())
        .isEqualTo(CapacityReadinessOutcome.READY);
    assertThat(
            evaluator
                .evaluate(
                    "SERVICE_STATE",
                    Optional.of(dimension),
                    Optional.of(policy),
                    stateProjection(dimension, policy, "UNAVAILABLE"))
                .outcome())
        .isEqualTo(CapacityReadinessOutcome.NOT_READY);
  }

  @Test
  void readinessPrecedenceIsNotReadyThenConflictThenStaleThenUnknownThenReady() {
    assertThat(
            CapacityReadinessEvaluation.aggregate(
                List.of(
                    result("BEDS_UNKNOWN", CapacityReadinessOutcome.UNKNOWN),
                    result("BEDS_READY", CapacityReadinessOutcome.READY),
                    result("BEDS_STALE", CapacityReadinessOutcome.STALE),
                    result("BEDS_CONFLICT", CapacityReadinessOutcome.CONFLICTED),
                    result("BEDS_NOT_READY", CapacityReadinessOutcome.NOT_READY))))
        .isEqualTo(CapacityReadinessOutcome.NOT_READY);
    assertThat(
            CapacityReadinessEvaluation.aggregate(
                List.of(
                    result("BEDS_UNKNOWN", CapacityReadinessOutcome.UNKNOWN),
                    result("BEDS_STALE", CapacityReadinessOutcome.STALE),
                    result("BEDS_CONFLICT", CapacityReadinessOutcome.CONFLICTED),
                    result("BEDS_READY", CapacityReadinessOutcome.READY))))
        .isEqualTo(CapacityReadinessOutcome.CONFLICTED);
    assertThat(
            CapacityReadinessEvaluation.aggregate(
                List.of(
                    result("BEDS_UNKNOWN", CapacityReadinessOutcome.UNKNOWN),
                    result("BEDS_STALE", CapacityReadinessOutcome.STALE),
                    result("BEDS_READY", CapacityReadinessOutcome.READY))))
        .isEqualTo(CapacityReadinessOutcome.STALE);
    assertThat(
            CapacityReadinessEvaluation.aggregate(
                List.of(
                    result("BEDS_UNKNOWN", CapacityReadinessOutcome.UNKNOWN),
                    result("BEDS_READY", CapacityReadinessOutcome.READY))))
        .isEqualTo(CapacityReadinessOutcome.UNKNOWN);
    assertThat(
            CapacityReadinessEvaluation.aggregate(
                List.of(result("BEDS_READY", CapacityReadinessOutcome.READY))))
        .isEqualTo(CapacityReadinessOutcome.READY);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CapacityReadinessEvaluation.aggregate(List.of()));
  }

  private static CapacityDimensionReadiness result(String code, CapacityReadinessOutcome outcome) {
    return new CapacityDimensionReadiness(
        code,
        outcome,
        CapacityEvidenceFreshness.UNKNOWN,
        OptionalLong.empty(),
        OptionalLong.empty(),
        CapacityEvidenceProjector.PROJECTION_VERSION,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        List.of(),
        false,
        List.of("TEST_RESULT"));
  }

  private static CapacityDimension quantityDimension() {
    return CapacityDimension.version(
        InternalId.generate(),
        1,
        new CapacityDimensionDefinition(
            DIMENSION,
            "Coordination beds",
            "Synthetic available bed count",
            CapacityValueKind.QUANTITY,
            "bed",
            BigDecimal.ZERO,
            new BigDecimal("100"),
            Set.of(),
            true,
            false,
            "TEST_CONFIGURATION"),
        ACTOR,
        NOW);
  }

  private static CapacityPolicy quantityPolicy(
      CapacityDimension dimension, BigDecimal minimumPassingQuantity) {
    return CapacityPolicy.version(
        InternalId.generate(),
        1,
        new CapacityPolicyDefinition(
            DIMENSION,
            dimension.version(),
            List.of(new CapacitySourcePriority("manual", 0, false, false)),
            600,
            300L,
            30,
            minimumPassingQuantity,
            Set.of(),
            "TEST_CONFIGURATION"),
        ACTOR,
        NOW);
  }

  private static CapacityEvidenceProjection currentProjection(
      CapacityDimension dimension, CapacityPolicy policy, BigDecimal quantity) {
    CapacityObservation observation =
        new CapacityObservation(
            InternalId.generate(),
            new ExternalReference(
                new IssuerId("urn:ayuvahak:directory"), "Facility", "facility-01"),
            DIMENSION,
            dimension.version(),
            CapacityObservationKind.VALUE,
            Optional.of(CapacityValueKind.QUANTITY),
            Optional.of(quantity),
            Optional.empty(),
            Optional.of("bed"),
            "manual",
            new ExternalReference(
                new IssuerId("urn:synthetic:source"),
                "CapacityObservation",
                "source-observation-01"),
            CapacityReportingMethod.MANUAL_ENTRY,
            ACTOR,
            NOW.minusSeconds(10),
            NOW.minusSeconds(9),
            Optional.empty(),
            Optional.of(NOW.plusSeconds(300)),
            policy.version(),
            false,
            "SYNTHETIC_TEST",
            Optional.empty());
    return new CapacityEvidenceProjection(
        DIMENSION,
        OptionalLong.of(dimension.version()),
        OptionalLong.of(policy.version()),
        CapacityEvidenceFreshness.CURRENT,
        Optional.of(CapacityValueKind.QUANTITY),
        Optional.of(quantity),
        Optional.empty(),
        Optional.of("bed"),
        List.of(observation),
        false,
        List.of("CURRENT_SOURCE_EVIDENCE"),
        CapacityEvidenceProjector.PROJECTION_VERSION);
  }

  private static CapacityEvidenceProjection stateProjection(
      CapacityDimension dimension, CapacityPolicy policy, String state) {
    CapacityObservation observation =
        new CapacityObservation(
            InternalId.generate(),
            new ExternalReference(
                new IssuerId("urn:ayuvahak:directory"), "Facility", "facility-01"),
            dimension.code(),
            dimension.version(),
            CapacityObservationKind.VALUE,
            Optional.of(CapacityValueKind.STATE),
            Optional.empty(),
            Optional.of(state),
            Optional.empty(),
            "manual",
            new ExternalReference(
                new IssuerId("urn:synthetic:source"),
                "CapacityObservation",
                "source-observation-02"),
            CapacityReportingMethod.MANUAL_ENTRY,
            ACTOR,
            NOW.minusSeconds(10),
            NOW.minusSeconds(9),
            Optional.empty(),
            Optional.of(NOW.plusSeconds(300)),
            policy.version(),
            false,
            "SYNTHETIC_TEST",
            Optional.empty());
    return new CapacityEvidenceProjection(
        dimension.code(),
        OptionalLong.of(dimension.version()),
        OptionalLong.of(policy.version()),
        CapacityEvidenceFreshness.CURRENT,
        Optional.of(CapacityValueKind.STATE),
        Optional.empty(),
        Optional.of(state),
        Optional.empty(),
        List.of(observation),
        false,
        List.of("CURRENT_SOURCE_EVIDENCE"),
        CapacityEvidenceProjector.PROJECTION_VERSION);
  }
}
