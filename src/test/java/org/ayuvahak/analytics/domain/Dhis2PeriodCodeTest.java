package org.ayuvahak.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class Dhis2PeriodCodeTest {

  @Test
  void resolvesOnlyExactConfiguredCalendarPeriods() {
    assertThat(Dhis2PeriodFormat.MONTHLY.periodCode(date("2026-01-01"), date("2026-02-01")))
        .isEqualTo("202601");
    assertThat(Dhis2PeriodFormat.QUARTERLY.periodCode(date("2026-04-01"), date("2026-07-01")))
        .isEqualTo("2026Q2");
    assertThat(Dhis2PeriodFormat.ANNUAL.periodCode(date("2026-01-01"), date("2027-01-01")))
        .isEqualTo("2026");
    assertThat(Dhis2PeriodFormat.DAILY.periodCode(date("2026-02-03"), date("2026-02-04")))
        .isEqualTo("20260203");
  }

  @Test
  void refusesPartialOrUnsupportedCalendarIntervals() {
    assertThatThrownBy(
            () -> Dhis2PeriodFormat.MONTHLY.periodCode(date("2026-01-02"), date("2026-02-02")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> Dhis2PeriodFormat.QUARTERLY.periodCode(date("2026-02-01"), date("2026-05-01")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static LocalDate date(String value) {
    return LocalDate.parse(value);
  }
}
