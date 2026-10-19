package org.ayuvahak.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.junit.jupiter.api.Test;

class AggregateDisclosureEquationTest {

  private static final ExternalReference ORGANIZATION =
      new ExternalReference(new IssuerId("urn:ayuvahak:directory"), "Organization", "SYN-ORG-001");
  private static final SourceMeasure SUBMITTED = SourceMeasure.referral("SUBMITTED_COUNT");
  private static final SourceMeasure RESOLVED = SourceMeasure.referral("RESOLVED_COUNT");
  private static final Instant CUTOFF = Instant.parse("2026-04-01T00:00:00Z");

  @Test
  void separateDisclosureChannelsReuseStableSourceCellsForCrossOutputInference() {
    AggregateSnapshot subtotal = snapshot("SYN-SUBTOTAL", List.of(SUBMITTED), List.of(9L));
    AggregateSnapshot total = snapshot("SYN-TOTAL", List.of(SUBMITTED, RESOLVED), List.of(9L, 1L));
    AggregateDisclosureEquation subtotalEquation =
        AggregateDisclosureEquation.from(
            subtotal,
            new IndicatorDefinitionVersion(
                "SYN-SUBTOTAL",
                1,
                "Synthetic subtotal",
                ZoneId.of("UTC"),
                List.of(SUBMITTED),
                true));
    AggregateDisclosureEquation totalEquation =
        AggregateDisclosureEquation.from(
            total,
            new IndicatorDefinitionVersion(
                "SYN-TOTAL",
                1,
                "Synthetic total",
                ZoneId.of("UTC"),
                List.of(SUBMITTED, RESOLVED),
                true));

    String sourceCell = subtotalEquation.cellValues().keySet().iterator().next();
    assertThat(totalEquation.cellValues()).containsEntry(sourceCell, 9L);

    UUID conservativeLineage = UUID.fromString("00000000-0000-0000-0000-000000000001");
    DisclosurePolicyVersion policy =
        new DisclosurePolicyVersion(
            "SYN-SCOPE",
            1,
            ORGANIZATION,
            5,
            true,
            conservativeLineage,
            Map.of(
                "DHIS2-SUBTOTAL", Set.of(conservativeLineage),
                "FEDERATION-TOTAL", Set.of(conservativeLineage)));
    DisclosureOutput priorSubtotal = subtotalEquation.output("DHIS2-SUBTOTAL");
    DisclosureCandidate federatedTotal =
        new DisclosureCandidate(
            "SYN-SCOPE",
            1,
            UUID.randomUUID(),
            totalEquation.cellValues(),
            List.of(totalEquation.output("FEDERATION-TOTAL")));

    assertThat(
            new DisclosureInferenceAnalyzer()
                .evaluate(
                    policy,
                    federatedTotal,
                    List.of(priorSubtotal),
                    subtotalEquation.cellValues(),
                    true))
        .isEqualTo(DisclosureInferenceAnalyzer.Result.INFERENCE_RISK);
  }

  @Test
  void snapshotStringRepresentationNeverIncludesAnAggregateValue() {
    AggregateSnapshot snapshot = snapshot("SYN-PRIVATE-COUNT", List.of(SUBMITTED), List.of(9L));

    assertThat(snapshot.toString()).contains("value=redacted").doesNotContain("value=9");
  }

  @Test
  void ratioUsesConservativeExactRelationWithoutApplyingCountThresholdToRatioValue() {
    AggregateSnapshot ratio = ratioSnapshot(10L, 100L, "0.100000");
    IndicatorDefinitionVersion definition = ratioDefinition();

    AggregateDisclosureEquation equation = AggregateDisclosureEquation.from(ratio, definition);
    DisclosureOutput output = equation.output("RATIO");
    DisclosureCandidate candidate =
        new DisclosureCandidate(
            "SYN-SCOPE",
            1,
            UUID.randomUUID(),
            equation.cellValues(),
            List.of(output));
    DisclosurePolicyVersion policy =
        new DisclosurePolicyVersion(
            "SYN-SCOPE",
            1,
            ORGANIZATION,
            5,
            true,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Map.of("RATIO", Set.of(UUID.fromString("00000000-0000-0000-0000-000000000001"))));

    assertThat(output.value()).isEqualTo(100_000L);
    assertThat(output.cellCoefficients()).containsValues(10L, -1L);
    assertThat(output.appliesMinimumCellCount()).isFalse();
    assertThat(
            new DisclosureInferenceAnalyzer()
                .evaluate(policy, candidate, List.of(), Map.of(), true))
        .isEqualTo(DisclosureInferenceAnalyzer.Result.SAFE);
  }

  @Test
  void ratioRelationParticipatesInDifferencingAnalysis() {
    AggregateSnapshot ratio = ratioSnapshot(1L, 100L, "0.010000");
    AggregateDisclosureEquation equation = AggregateDisclosureEquation.from(ratio, ratioDefinition());
    String denominatorCell =
        equation.cellValues().keySet().stream()
            .filter(key -> equation.cellValues().get(key) == 100L)
            .findFirst()
            .orElseThrow();
    DisclosureOutput knownDenominator =
        new DisclosureOutput("KNOWN_DENOMINATOR", 100L, Map.of(denominatorCell, 1L));
    DisclosureCandidate candidate =
        new DisclosureCandidate(
            "SYN-SCOPE", 1, UUID.randomUUID(), equation.cellValues(), List.of(equation.output("RATIO")));
    DisclosurePolicyVersion policy =
        new DisclosurePolicyVersion(
            "SYN-SCOPE",
            1,
            ORGANIZATION,
            5,
            true,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Map.of("RATIO", Set.of(UUID.fromString("00000000-0000-0000-0000-000000000001"))));

    assertThat(
            new DisclosureInferenceAnalyzer()
                .evaluate(
                    policy,
                    candidate,
                    List.of(knownDenominator),
                    Map.of(denominatorCell, 100L),
                    true))
        .isEqualTo(DisclosureInferenceAnalyzer.Result.INFERENCE_RISK);
  }

  private static IndicatorDefinitionVersion ratioDefinition() {
    return new IndicatorDefinitionVersion(
        "SYN-RATIO",
        1,
        "Synthetic ratio",
        AggregationFormula.RATIO_6DP_HALF_EVEN,
        ZoneId.of("UTC"),
        List.of(SUBMITTED),
        true,
        List.of(RESOLVED),
        IndicatorDefinitionGovernance.syntheticFixture("Synthetic ratio"));
  }

  private static AggregateSnapshot ratioSnapshot(long numerator, long denominator, String value) {
    var period =
        ReportingPeriod.resolve(
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1), ZoneId.of("UTC"));
    List<AggregateInput> inputs =
        List.of(
            ratioInput(SUBMITTED, numerator), ratioInput(RESOLVED, denominator)).stream()
            .sorted(java.util.Comparator.comparing(input -> input.measure().stableKey()))
            .toList();
    return new AggregateSnapshot(
        UUID.randomUUID(),
        InternalId.generate(),
        "SYN-RATIO",
        1,
        ORGANIZATION,
        Optional.empty(),
        period,
        AggregateCompleteness.COMPLETE,
        OptionalLong.empty(),
        Optional.of(new BigDecimal(value)),
        inputs,
        CUTOFF,
        "b".repeat(64));
  }

  private static AggregateInput ratioInput(SourceMeasure measure, long value) {
    return new AggregateInput(
        measure,
        Optional.of(UUID.randomUUID()),
        Optional.of(CUTOFF),
        Optional.of("SYN-OWNER-CONTRACT-V1"),
        AggregateCompleteness.COMPLETE,
        OptionalLong.of(value),
        Optional.of(CUTOFF));
  }

  private static AggregateSnapshot snapshot(
      String indicatorKey, List<SourceMeasure> measures, List<Long> values) {
    var period =
        ReportingPeriod.resolve(
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1), ZoneId.of("UTC"));
    List<AggregateInput> inputs =
        java.util.stream.IntStream.range(0, measures.size())
            .mapToObj(
                index ->
                    new AggregateInput(
                        measures.get(index),
                        Optional.of(
                            UUID.nameUUIDFromBytes(
                                measures.get(index).stableKey().getBytes(StandardCharsets.UTF_8))),
                        Optional.of(CUTOFF),
                        Optional.of("SYN-OWNER-CONTRACT-V1"),
                        AggregateCompleteness.COMPLETE,
                        OptionalLong.of(values.get(index)),
                        Optional.of(CUTOFF)))
            .sorted(java.util.Comparator.comparing(input -> input.measure().stableKey()))
            .toList();
    long sum = values.stream().mapToLong(Long::longValue).sum();
    return new AggregateSnapshot(
        UUID.nameUUIDFromBytes(indicatorKey.getBytes(StandardCharsets.UTF_8)),
        InternalId.generate(),
        indicatorKey,
        1,
        ORGANIZATION,
        period,
        AggregateCompleteness.COMPLETE,
        OptionalLong.of(sum),
        inputs,
        CUTOFF,
        "a".repeat(64));
  }
}
