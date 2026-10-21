package org.ayuvahak.analytics.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.domain.AggregateCompleteness;

/** Minimized Analytics-owned presentation of an immutable aggregate snapshot. */
public record AnalyticsSnapshotSummary(
    UUID snapshotId,
    String indicatorKey,
    int indicatorVersion,
    LocalDate periodStart,
    LocalDate periodEndExclusive,
    String timeZone,
    AggregateCompleteness completeness,
    Optional<BigDecimal> value,
    Instant computedAt,
    Optional<Integer> revision,
    Optional<String> revisionState,
    Optional<String> exportState,
    Optional<String> exportResultClass) {

  public AnalyticsSnapshotSummary {
    Objects.requireNonNull(snapshotId, "snapshot id");
    Objects.requireNonNull(indicatorKey, "indicator key");
    if (indicatorVersion < 1) throw new IllegalArgumentException("indicator version is invalid");
    Objects.requireNonNull(periodStart, "period start");
    Objects.requireNonNull(periodEndExclusive, "period end");
    if (!periodStart.isBefore(periodEndExclusive)) {
      throw new IllegalArgumentException("reporting period must be non-empty");
    }
    Objects.requireNonNull(timeZone, "time zone");
    Objects.requireNonNull(completeness, "completeness");
    value = Objects.requireNonNull(value, "aggregate value");
    Objects.requireNonNull(computedAt, "computed at");
    revision = Objects.requireNonNull(revision, "revision");
    revisionState = Objects.requireNonNull(revisionState, "revision state");
    exportState = Objects.requireNonNull(exportState, "export state");
    exportResultClass = Objects.requireNonNull(exportResultClass, "export result class");
    if ((completeness == AggregateCompleteness.COMPLETE) != value.isPresent()) {
      throw new IllegalArgumentException("only complete snapshots may expose a numeric value");
    }
    if (revision.isEmpty() != revisionState.isEmpty()) {
      throw new IllegalArgumentException("revision number and state must be supplied together");
    }
  }
}
