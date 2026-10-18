package org.ayuvahak.analytics.domain;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneRulesProvider;
import java.util.List;
import java.util.Objects;

/** A half-open local reporting interval with its resolved, reproducible UTC boundaries. */
public record ReportingPeriod(
    LocalDate startInclusiveLocal,
    LocalDate endExclusiveLocal,
    String timeZoneId,
    Instant startInclusiveUtc,
    Instant endExclusiveUtc,
    String timeZoneRulesVersion) {

  public ReportingPeriod {
    Objects.requireNonNull(startInclusiveLocal, "local start");
    Objects.requireNonNull(endExclusiveLocal, "local end");
    Objects.requireNonNull(timeZoneId, "time zone id");
    Objects.requireNonNull(startInclusiveUtc, "UTC start");
    Objects.requireNonNull(endExclusiveUtc, "UTC end");
    Objects.requireNonNull(timeZoneRulesVersion, "time zone rules version");
    if (!startInclusiveLocal.isBefore(endExclusiveLocal)
        || !startInclusiveUtc.isBefore(endExclusiveUtc)
        || timeZoneRulesVersion.isBlank()) {
      throw new IllegalArgumentException("reporting period must be a non-empty half-open interval");
    }
  }

  public static ReportingPeriod resolve(
      LocalDate startInclusive, LocalDate endExclusive, ZoneId reportingZone) {
    Objects.requireNonNull(startInclusive, "local start");
    Objects.requireNonNull(endExclusive, "local end");
    Objects.requireNonNull(reportingZone, "reporting zone");
    if (!ZoneId.getAvailableZoneIds().contains(reportingZone.getId())) {
      throw new IllegalArgumentException("reporting zone must be an IANA zone");
    }
    var versions = ZoneRulesProvider.getVersions(reportingZone.getId());
    if (versions.isEmpty()) {
      throw new IllegalArgumentException("reporting zone has no versioned rules");
    }
    Instant start = startInclusive.atStartOfDay(reportingZone).toInstant();
    Instant end = endExclusive.atStartOfDay(reportingZone).toInstant();
    return new ReportingPeriod(
        startInclusive, endExclusive, reportingZone.getId(), start, end, versions.lastKey());
  }

  /** Rehydrates stored boundaries without recomputing them under a potentially newer tzdb. */
  public static ReportingPeriod restore(
      LocalDate startInclusive,
      LocalDate endExclusive,
      String timeZoneId,
      Instant startUtc,
      Instant endUtc,
      String timeZoneRulesVersion) {
    return new ReportingPeriod(
        startInclusive, endExclusive, timeZoneId, startUtc, endUtc, timeZoneRulesVersion);
  }

  /**
   * Returns whether both UTC boundaries match the exact IANA rules version carried by this period.
   * This deliberately fails closed when that version is unavailable locally.
   */
  public boolean hasConsistentResolvedBoundaries() {
    if (!ZoneId.getAvailableZoneIds().contains(timeZoneId)) {
      return false;
    }
    try {
      ZoneRules rules = ZoneRulesProvider.getVersions(timeZoneId).get(timeZoneRulesVersion);
      return rules != null
          && startOfDay(startInclusiveLocal, rules).equals(startInclusiveUtc)
          && startOfDay(endExclusiveLocal, rules).equals(endExclusiveUtc);
    } catch (DateTimeException invalidZone) {
      return false;
    }
  }

  private static Instant startOfDay(LocalDate date, ZoneRules rules) {
    LocalDateTime localStart = date.atStartOfDay();
    List<ZoneOffset> offsets = rules.getValidOffsets(localStart);
    if (!offsets.isEmpty()) {
      // ZoneRules orders overlap offsets with the earlier offset first, as ZonedDateTime does.
      return localStart.toInstant(offsets.getFirst());
    }
    ZoneOffsetTransition gap = rules.getTransition(localStart);
    if (gap == null) {
      throw new DateTimeException("no zone transition resolves local start of day");
    }
    return gap.getDateTimeAfter().toInstant(gap.getOffsetAfter());
  }
}
