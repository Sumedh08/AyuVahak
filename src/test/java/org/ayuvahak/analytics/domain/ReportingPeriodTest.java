package org.ayuvahak.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ReportingPeriodTest {

  @Test
  void resolvesHalfOpenLocalDatesToStableUtcBoundsAcrossDaylightSavingTime() {
    ReportingPeriod period =
        ReportingPeriod.resolve(
            LocalDate.parse("2026-03-29"),
            LocalDate.parse("2026-03-30"),
            ZoneId.of("Europe/Berlin"));

    assertThat(period.startInclusiveUtc()).isEqualTo("2026-03-28T23:00:00Z");
    assertThat(period.endExclusiveUtc()).isEqualTo("2026-03-29T22:00:00Z");
    assertThat(period.timeZoneId()).isEqualTo("Europe/Berlin");
    assertThat(period.timeZoneRulesVersion()).isNotBlank();
  }

  @Test
  void rejectsFixedOffsetInsteadOfAnIanaReportingZone() {
    assertThatThrownBy(
            () ->
                ReportingPeriod.resolve(
                    LocalDate.parse("2026-01-01"),
                    LocalDate.parse("2026-02-01"),
                    ZoneId.of("+02:00")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void distinguishesResolvedFromForgedUtcBoundaries() {
    ReportingPeriod resolved =
        ReportingPeriod.resolve(
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"), ZoneId.of("UTC"));
    ReportingPeriod forged =
        ReportingPeriod.restore(
            resolved.startInclusiveLocal(),
            resolved.endExclusiveLocal(),
            resolved.timeZoneId(),
            resolved.startInclusiveUtc().plusSeconds(3_600),
            resolved.endExclusiveUtc().plusSeconds(3_600),
            resolved.timeZoneRulesVersion());

    assertThat(resolved.hasConsistentResolvedBoundaries()).isTrue();
    assertThat(forged.hasConsistentResolvedBoundaries()).isFalse();
  }

  @Test
  void failsClosedWhenTheRecordedZoneRulesVersionIsUnavailable() {
    ReportingPeriod resolved =
        ReportingPeriod.resolve(
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"), ZoneId.of("UTC"));
    ReportingPeriod unknownRules =
        ReportingPeriod.restore(
            resolved.startInclusiveLocal(),
            resolved.endExclusiveLocal(),
            resolved.timeZoneId(),
            resolved.startInclusiveUtc(),
            resolved.endExclusiveUtc(),
            "synthetic-unavailable-tzdb-version");

    assertThat(unknownRules.hasConsistentResolvedBoundaries()).isFalse();
  }
}
