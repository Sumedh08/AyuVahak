package org.ayuvahak.analytics.application;

import java.time.Instant;
import java.util.Objects;
import org.ayuvahak.analytics.domain.ReportingPeriod;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Scope and exact resolved period supplied to a named owner reporting contract. */
public record OwnerReportingRequest(
    InternalId computationRequestId,
    ExternalReference organization,
    ReportingPeriod period,
    Instant referralSourceCutoff) {

  public OwnerReportingRequest {
    Objects.requireNonNull(computationRequestId, "computation request id");
    Objects.requireNonNull(organization, "organization");
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(referralSourceCutoff, "Referral source cutoff");
  }
}
