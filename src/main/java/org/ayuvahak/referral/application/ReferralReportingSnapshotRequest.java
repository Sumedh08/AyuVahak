package org.ayuvahak.referral.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded, idempotent request for owner-computed Referral measures. */
public record ReferralReportingSnapshotRequest(
    UUID requestId,
    Set<String> measureKeys,
    Instant periodStart,
    Instant periodEnd,
    Instant sourceCutoff) {

  public ReferralReportingSnapshotRequest {
    Objects.requireNonNull(requestId, "request id");
    measureKeys = Set.copyOf(Objects.requireNonNull(measureKeys, "measure keys"));
    Objects.requireNonNull(periodStart, "period start");
    Objects.requireNonNull(periodEnd, "period end");
    Objects.requireNonNull(sourceCutoff, "source cutoff");
  }
}
