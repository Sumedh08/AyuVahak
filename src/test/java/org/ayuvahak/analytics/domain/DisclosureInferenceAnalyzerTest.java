package org.ayuvahak.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.junit.jupiter.api.Test;

class DisclosureInferenceAnalyzerTest {

  private static final String POLICY_KEY = "SYN-REPORTING-SCOPE-A";
  private static final UUID CONSERVATIVE_LINEAGE =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final ExternalReference ORGANIZATION =
      new ExternalReference(new IssuerId("urn:ayuvahak:directory"), "Organization", "SYN-ORG-001");
  private final DisclosureInferenceAnalyzer analyzer = new DisclosureInferenceAnalyzer();

  @Test
  void overlappingPeriodTotalAndSubtotalCannotRevealSmallCell() {
    DisclosurePolicyVersion policy = policy(true);
    DisclosureOutput priorPeriodTotal =
        new DisclosureOutput("PERIOD_TOTAL", 16, Map.of("CELL_A", 1L, "CELL_B", 1L, "CELL_C", 1L));
    DisclosureCandidate candidate =
        candidate(
            Map.of("CELL_A", 2L, "CELL_B", 8L, "CELL_C", 6L),
            new DisclosureOutput("OVERLAPPING_SUBTOTAL", 14, Map.of("CELL_B", 1L, "CELL_C", 1L)));

    assertThat(analyzer.evaluate(policy, candidate, List.of(priorPeriodTotal), Map.of(), true))
        .isEqualTo(DisclosureInferenceAnalyzer.Result.INFERENCE_RISK);
  }

  @Test
  void incompleteHistoryAndUnknownExternalBaselineFailClosed() {
    DisclosureCandidate candidate =
        candidate(
            Map.of("CELL_A", 2L, "CELL_B", 8L),
            new DisclosureOutput("TOTAL", 10, Map.of("CELL_A", 1L, "CELL_B", 1L)));

    assertThat(analyzer.evaluate(policy(true), candidate, List.of(), Map.of(), false))
        .isEqualTo(DisclosureInferenceAnalyzer.Result.REVIEW_REQUIRED);
    assertThat(analyzer.evaluate(policy(false), candidate, List.of(), Map.of(), true))
        .isEqualTo(DisclosureInferenceAnalyzer.Result.REVIEW_REQUIRED);
  }

  @Test
  void completeEmptyHistoryCanReleaseACompleteAggregate() {
    DisclosureCandidate candidate =
        candidate(
            Map.of("CELL_A", 9L, "CELL_B", 8L),
            new DisclosureOutput("TOTAL", 17, Map.of("CELL_A", 1L, "CELL_B", 1L)));

    assertThat(analyzer.evaluate(policy(true), candidate, List.of(), Map.of(), true))
        .isEqualTo(DisclosureInferenceAnalyzer.Result.SAFE);
  }

  private static DisclosurePolicyVersion policy(boolean baselineComplete) {
    return new DisclosurePolicyVersion(
        POLICY_KEY,
        1,
        ORGANIZATION,
        5,
        baselineComplete,
        CONSERVATIVE_LINEAGE,
        Map.of("DEFAULT", Set.of(UUID.fromString("00000000-0000-0000-0000-000000000002"))));
  }

  private static DisclosureCandidate candidate(
      Map<String, Long> cells, DisclosureOutput... outputs) {
    return new DisclosureCandidate(POLICY_KEY, 1, UUID.randomUUID(), cells, List.of(outputs));
  }
}
