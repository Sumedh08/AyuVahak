package org.ayuvahak.referral.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Immutable origin-authored snapshot created when a Referral is submitted. */
public record SubmittedReferralRevision(
    InternalId referralId,
    long revisionNumber,
    long referralVersion,
    ExternalReference originOrganization,
    Optional<ExternalReference> originFacility,
    PatientReference patientReference,
    CareRequirement careRequirement,
    ActorReference sourceActor,
    ActorReference submittedBy,
    Instant submittedAt) {

  public SubmittedReferralRevision {
    Objects.requireNonNull(referralId, "Referral id");
    if (revisionNumber < 1 || referralVersion < 1) {
      throw new IllegalArgumentException("revision number and Referral version must be positive");
    }
    Objects.requireNonNull(originOrganization, "origin organization");
    originFacility = Objects.requireNonNull(originFacility, "origin facility");
    Objects.requireNonNull(patientReference, "PatientReference");
    Objects.requireNonNull(careRequirement, "CareRequirement");
    Objects.requireNonNull(sourceActor, "source actor");
    Objects.requireNonNull(submittedBy, "submitter");
    Objects.requireNonNull(submittedAt, "submitted at");
  }
}
