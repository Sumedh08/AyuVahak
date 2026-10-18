package org.ayuvahak.analytics.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;

/** Stable, bounded request for one immutable indicator version and local reporting interval. */
public record AnalyticsComputationRequest(
    InternalId computationRequestId,
    String indicatorKey,
    int indicatorVersion,
    LocalDate startInclusive,
    LocalDate endExclusive,
    Instant referralSourceCutoff) {

  public AnalyticsComputationRequest {
    Objects.requireNonNull(computationRequestId, "computation request id");
    if (indicatorKey == null || !indicatorKey.matches("[A-Z0-9][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("indicator key is invalid");
    }
    if (indicatorVersion < 1) {
      throw new IllegalArgumentException("indicator version must be positive");
    }
    Objects.requireNonNull(startInclusive, "period start");
    Objects.requireNonNull(endExclusive, "period end");
    Objects.requireNonNull(referralSourceCutoff, "Referral source cutoff");
    if (!startInclusive.isBefore(endExclusive) || referralSourceCutoff.getNano() % 1_000 != 0) {
      throw new IllegalArgumentException("period or source cutoff precision is invalid");
    }
  }
}
