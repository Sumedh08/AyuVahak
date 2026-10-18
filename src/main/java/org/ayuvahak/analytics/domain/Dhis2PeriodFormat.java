package org.ayuvahak.analytics.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Explicit DHIS2 aggregate period encodings supported by the initial export profile. */
public enum Dhis2PeriodFormat {
  DAILY {
    @Override
    public String periodCode(LocalDate startInclusive, LocalDate endExclusive) {
      requireInterval(startInclusive, endExclusive);
      if (!endExclusive.equals(startInclusive.plusDays(1))) {
        throw unsupportedInterval();
      }
      return startInclusive.format(DateTimeFormatter.BASIC_ISO_DATE);
    }
  },
  MONTHLY {
    @Override
    public String periodCode(LocalDate startInclusive, LocalDate endExclusive) {
      requireInterval(startInclusive, endExclusive);
      if (startInclusive.getDayOfMonth() != 1
          || !endExclusive.equals(startInclusive.plusMonths(1))) {
        throw unsupportedInterval();
      }
      return YearMonth.from(startInclusive).format(DateTimeFormatter.ofPattern("yyyyMM"));
    }
  },
  QUARTERLY {
    @Override
    public String periodCode(LocalDate startInclusive, LocalDate endExclusive) {
      requireInterval(startInclusive, endExclusive);
      if (startInclusive.getDayOfMonth() != 1
          || !SetOfQuarterMonths.contains(startInclusive.getMonthValue())
          || !endExclusive.equals(startInclusive.plusMonths(3))) {
        throw unsupportedInterval();
      }
      return startInclusive.getYear() + "Q" + (((startInclusive.getMonthValue() - 1) / 3) + 1);
    }
  },
  ANNUAL {
    @Override
    public String periodCode(LocalDate startInclusive, LocalDate endExclusive) {
      requireInterval(startInclusive, endExclusive);
      if (startInclusive.getMonthValue() != 1
          || startInclusive.getDayOfMonth() != 1
          || !endExclusive.equals(startInclusive.plusYears(1))) {
        throw unsupportedInterval();
      }
      return Integer.toString(startInclusive.getYear());
    }
  };

  public abstract String periodCode(LocalDate startInclusive, LocalDate endExclusive);

  private static void requireInterval(LocalDate startInclusive, LocalDate endExclusive) {
    Objects.requireNonNull(startInclusive, "period start");
    Objects.requireNonNull(endExclusive, "period end");
    if (!startInclusive.isBefore(endExclusive)) {
      throw new IllegalArgumentException("DHIS2 period must be a non-empty half-open interval");
    }
  }

  private static IllegalArgumentException unsupportedInterval() {
    return new IllegalArgumentException(
        "snapshot interval does not match the configured DHIS2 period format");
  }

  private static final class SetOfQuarterMonths {
    private SetOfQuarterMonths() {}

    private static boolean contains(int month) {
      return month == 1 || month == 4 || month == 7 || month == 10;
    }
  }
}
