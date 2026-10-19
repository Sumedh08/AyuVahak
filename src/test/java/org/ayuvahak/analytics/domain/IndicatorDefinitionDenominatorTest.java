package org.ayuvahak.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndicatorDefinitionDenominatorTest {

  @Test
  void acceptsASeparateBoundedDenominatorForRatioFormula() {
    SourceMeasure numerator = SourceMeasure.referral("SUBMITTED_COUNT");
    SourceMeasure denominator = SourceMeasure.referral("RESOLVED_COUNT");

    IndicatorDefinitionVersion definition =
        new IndicatorDefinitionVersion(
            "SYN-REFERRAL-RATE",
            1,
            "Synthetic referral ratio",
            AggregationFormula.RATIO_6DP_HALF_EVEN,
            ZoneId.of("UTC"),
            List.of(numerator),
            true,
            List.of(denominator),
            IndicatorDefinitionGovernance.syntheticFixture("Synthetic ratio demonstration"));

    assertThat(definition.numeratorMeasures()).containsExactly(numerator);
    assertThat(definition.denominatorMeasures()).containsExactly(denominator);
    assertThat(definition.fingerprint()).hasSize(64);
  }

  @Test
  void rejectsAMeasureAppearingInBothNumeratorAndDenominator() {
    SourceMeasure same = SourceMeasure.referral("SUBMITTED_COUNT");

    assertThatThrownBy(
            () ->
                new IndicatorDefinitionVersion(
                    "SYN-INVALID-RATIO",
                    1,
                    "Synthetic invalid ratio",
                    AggregationFormula.RATIO_6DP_HALF_EVEN,
                    ZoneId.of("UTC"),
                    List.of(same),
                    true,
                    List.of(same),
                    IndicatorDefinitionGovernance.syntheticFixture("Synthetic invalid ratio")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("disjoint");
  }

  @Test
  void sumFormulaCannotCarryDenominatorMeasures() {
    assertThatThrownBy(
            () ->
                new IndicatorDefinitionVersion(
                    "SYN-INVALID-SUM",
                    1,
                    "Synthetic invalid sum",
                    AggregationFormula.SUM,
                    ZoneId.of("UTC"),
                    List.of(SourceMeasure.referral("SUBMITTED_COUNT")),
                    true,
                    List.of(SourceMeasure.referral("RESOLVED_COUNT")),
                    IndicatorDefinitionGovernance.syntheticFixture("Synthetic invalid sum")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("formula");
  }
}
