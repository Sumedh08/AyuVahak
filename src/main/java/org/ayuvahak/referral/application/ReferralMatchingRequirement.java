package org.ayuvahak.referral.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Minimized, owner-produced Referral facts needed by Matching for one current submission. */
public record ReferralMatchingRequirement(
    InternalId referralId,
    long revisionNumber,
    long currentReferralVersion,
    ExternalReference originOrganization,
    Optional<ExternalReference> originFacility,
    List<String> requiredCareCapabilityCodes,
    Optional<Instant> requestedStart,
    Optional<Instant> requestedEnd,
    List<String> permittedGeographyCodes,
    Optional<ExternalReference> selectedDestinationOrganization,
    Optional<String> priorityCode,
    Instant submittedAt) {

  public ReferralMatchingRequirement {
    Objects.requireNonNull(referralId, "Referral id");
    if (revisionNumber < 1 || currentReferralVersion < 1) {
      throw new IllegalArgumentException("Referral revision and version must be positive");
    }
    Objects.requireNonNull(originOrganization, "origin organization");
    originFacility = Objects.requireNonNull(originFacility, "origin facility");
    requiredCareCapabilityCodes = List.copyOf(requiredCareCapabilityCodes);
    if (requiredCareCapabilityCodes.isEmpty()) {
      throw new IllegalArgumentException("at least one CareCapability is required");
    }
    requestedStart = Objects.requireNonNull(requestedStart, "requested start");
    requestedEnd = Objects.requireNonNull(requestedEnd, "requested end");
    permittedGeographyCodes = List.copyOf(permittedGeographyCodes);
    selectedDestinationOrganization =
        Objects.requireNonNull(selectedDestinationOrganization, "selected destination");
    priorityCode = Objects.requireNonNull(priorityCode, "priority code");
    Objects.requireNonNull(submittedAt, "submitted at");
  }
}
