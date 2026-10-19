package org.ayuvahak.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
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

  @Test
  void transferAttributionInDenominatorRequiresTheBoundedAttributionDimension() {
    IndicatorDefinitionGovernance governance =
        new IndicatorDefinitionGovernance(
            "Synthetic ratio with transfer denominator",
            Instant.EPOCH,
            Optional.empty(),
            IndicatorDefinitionGovernance.ReportingCalendar.CUSTOM_HALF_OPEN,
            EnumSet.of(IndicatorDefinitionGovernance.ReportingDimension.ORGANIZATION),
            IndicatorDefinitionGovernance.CompletenessRequirement
                .ALL_REQUIRED_SOURCES_COMPLETE_FOR_NUMERIC_VALUE,
            Optional.empty(),
            IndicatorDefinitionGovernance.SensitivityClassification.SENSITIVE_AGGREGATE,
            Optional.empty(),
            Optional.empty());

    assertThatThrownBy(
            () ->
                new IndicatorDefinitionVersion(
                    "SYN-TRANSFER-DENOMINATOR-RATIO",
                    1,
                    "Synthetic ratio denominator attribution",
                    AggregationFormula.RATIO_6DP_HALF_EVEN,
                    ZoneId.of("UTC"),
                    List.of(SourceMeasure.referral("SUBMITTED_COUNT")),
                    true,
                    List.of(
                        SourceMeasure.transfer(
                            "TRANSFER_ACCEPTED", SourceMeasure.Attribution.ORIGIN)),
                    governance))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source attribution");
  }
}
