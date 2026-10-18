package org.ayuvahak.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.analytics.domain.IndicatorDefinitionGovernance.AuthorityDefinition;
import org.ayuvahak.analytics.domain.IndicatorDefinitionGovernance.CompletenessRequirement;
import org.ayuvahak.analytics.domain.IndicatorDefinitionGovernance.ReportingCalendar;
import org.ayuvahak.analytics.domain.IndicatorDefinitionGovernance.ReportingDimension;
import org.ayuvahak.analytics.domain.IndicatorDefinitionGovernance.SensitivityClassification;
import org.junit.jupiter.api.Test;

class IndicatorDefinitionGovernanceTest {

  @Test
  void periodAndGovernanceSemanticsArePartOfTheImmutableIndicatorFingerprint() {
    IndicatorDefinitionVersion first = definition(ReportingCalendar.MONTHLY, 30);
    IndicatorDefinitionVersion changed = definition(ReportingCalendar.CUSTOM_HALF_OPEN, 30);

    assertThat(first.numeratorMeasures())
        .containsExactly(SourceMeasure.referral("SUBMITTED_COUNT"));
    assertThat(first.denominatorMeasures()).isEmpty();
    assertThat(first.fingerprint()).isNotEqualTo(changed.fingerprint());

    ReportingPeriod validMonth =
        ReportingPeriod.resolve(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), ZoneId.of("UTC"));
    ReportingPeriod partialMonth =
        ReportingPeriod.resolve(
            LocalDate.of(2026, 1, 2), LocalDate.of(2026, 2, 2), ZoneId.of("UTC"));
    assertThat(first.governance().calendarAccepts(validMonth)).isTrue();
    assertThat(first.governance().calendarAccepts(partialMonth)).isFalse();
    assertThat(first.governance().effectiveFor(validMonth)).isTrue();
  }

  @Test
  void sensitiveDefinitionRequiresBoundedOrganizationAndValidEffectiveInterval() {
    assertThatThrownBy(
            () ->
                new IndicatorDefinitionGovernance(
                    "Synthetic indicator",
                    Instant.parse("2026-02-01T00:00:00Z"),
                    Optional.of(Instant.parse("2026-01-01T00:00:00Z")),
                    ReportingCalendar.MONTHLY,
                    EnumSet.of(ReportingDimension.SOURCE_ATTRIBUTION),
                    CompletenessRequirement.ALL_REQUIRED_SOURCES_COMPLETE_FOR_NUMERIC_VALUE,
                    Optional.of(Duration.ofDays(30)),
                    SensitivityClassification.SENSITIVE_AGGREGATE,
                    Optional.of("SYN-POLICY-001"),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static IndicatorDefinitionVersion definition(ReportingCalendar calendar, int days) {
    IndicatorDefinitionGovernance governance =
        new IndicatorDefinitionGovernance(
            "Synthetic submitted referral count",
            Instant.parse("2026-01-01T00:00:00Z"),
            Optional.empty(),
            calendar,
            EnumSet.of(ReportingDimension.ORGANIZATION, ReportingDimension.SOURCE_ATTRIBUTION),
            CompletenessRequirement.ALL_REQUIRED_SOURCES_COMPLETE_FOR_NUMERIC_VALUE,
            Optional.of(Duration.ofDays(days)),
            SensitivityClassification.SENSITIVE_AGGREGATE,
            Optional.of("SYN-POLICY-001"),
            Optional.of(new AuthorityDefinition("urn:synthetic:authority", "SYN-v1")));
    return new IndicatorDefinitionVersion(
        "SYN-INDICATOR-001",
        1,
        "Synthetic submitted referral count",
        AggregationFormula.SUM,
        ZoneId.of("UTC"),
        List.of(SourceMeasure.referral("SUBMITTED_COUNT")),
        false,
        List.of(),
        governance);
  }
}
