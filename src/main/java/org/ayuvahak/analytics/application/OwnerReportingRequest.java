package org.ayuvahak.analytics.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.analytics.domain.ReportingPeriod;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Scope and exact resolved period supplied to a named owner reporting contract. */
public record OwnerReportingRequest(
    InternalId computationRequestId,
    ExternalReference organization,
    Optional<ExternalReference> facility,
    ReportingPeriod period,
    Instant referralSourceCutoff) {

  public OwnerReportingRequest {
    Objects.requireNonNull(computationRequestId, "computation request id");
    Objects.requireNonNull(organization, "organization");
    facility = Objects.requireNonNull(facility, "facility");
    facility.ifPresent(
        reference -> {
          if (!"Facility".equals(reference.resourceType())
              || !organization.issuer().equals(reference.issuer())) {
            throw new IllegalArgumentException("owner reporting facility scope is invalid");
          }
        });
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(referralSourceCutoff, "Referral source cutoff");
  }

  public OwnerReportingRequest(
      InternalId computationRequestId,
      ExternalReference organization,
      ReportingPeriod period,
      Instant referralSourceCutoff) {
    this(computationRequestId, organization, Optional.empty(), period, referralSourceCutoff);
  }
}
