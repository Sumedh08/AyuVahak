package org.ayuvahak.referral.application;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import org.ayuvahak.platform.core.ExternalReference;

/** Aggregate-only immutable owner snapshot; it contains no Referral or patient identifiers. */
public record ReferralReportingSnapshot(
    ExternalReference organization,
    Instant periodStart,
    Instant periodEnd,
    Instant sourceCutoff,
    ReferralReportingCompleteness completeness,
    Map<String, OptionalLong> measures,
    long sourceVersion,
    UUID sourceSnapshotReference,
    Instant generatedAt) {

  public ReferralReportingSnapshot {
    Objects.requireNonNull(organization, "organization");
    Objects.requireNonNull(periodStart, "period start");
    Objects.requireNonNull(periodEnd, "period end");
    Objects.requireNonNull(sourceCutoff, "source cutoff");
    Objects.requireNonNull(completeness, "completeness");
    measures = Map.copyOf(Objects.requireNonNull(measures, "measures"));
    Objects.requireNonNull(sourceSnapshotReference, "source snapshot reference");
    Objects.requireNonNull(generatedAt, "generated at");
    if (!periodStart.isBefore(periodEnd) || sourceVersion < 1) {
      throw new IllegalArgumentException("invalid Referral reporting snapshot metadata");
    }
    if (completeness == ReferralReportingCompleteness.COMPLETE
        && measures.values().stream().anyMatch(OptionalLong::isEmpty)) {
      throw new IllegalArgumentException("complete snapshots require every requested value");
    }
    if (completeness != ReferralReportingCompleteness.COMPLETE
        && measures.values().stream().anyMatch(OptionalLong::isPresent)) {
      throw new IllegalArgumentException("incomplete snapshots cannot expose numeric values");
    }
  }

  @Override
  public String toString() {
    return "ReferralReportingSnapshot[organization=redacted, periodStart="
        + periodStart
        + ", periodEnd="
        + periodEnd
        + ", completeness="
        + completeness
        + ", measureKeys="
        + measures.keySet()
        + ", sourceVersion="
        + sourceVersion
        + ", sourceSnapshotReference=redacted]";
  }
}
