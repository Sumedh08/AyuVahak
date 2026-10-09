package org.ayuvahak.referral.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.referral.domain.ReferralState;

/** Minimized, owner-produced list view that cannot carry PatientReference or clinical context. */
public record ReferralListItem(
    InternalId id,
    ReferralState state,
    long version,
    ExternalReference originOrganization,
    Optional<ExternalReference> originFacility,
    Instant relevantAt,
    Optional<Instant> submittedAt) {

  public ReferralListItem {
    Objects.requireNonNull(id, "Referral id");
    Objects.requireNonNull(state, "Referral state");
    if (version < 1) {
      throw new IllegalArgumentException("Referral version must be positive");
    }
    Objects.requireNonNull(originOrganization, "origin organization");
    originFacility = Objects.requireNonNull(originFacility, "origin facility");
    Objects.requireNonNull(relevantAt, "relevant time");
    submittedAt = Objects.requireNonNull(submittedAt, "submitted time");
  }
}
