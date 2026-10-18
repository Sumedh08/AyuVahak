package org.ayuvahak.analytics.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesProvider;
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
}
