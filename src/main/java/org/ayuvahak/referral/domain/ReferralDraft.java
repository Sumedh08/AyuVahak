package org.ayuvahak.referral.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Current Referral state as it is prepared at origin and then submitted. */
public record ReferralDraft(
    InternalId id,
    ExternalReference originOrganization,
    Optional<ExternalReference> originFacility,
    PatientReference patientReference,
    CareRequirement careRequirement,
    ActorReference createdBy,
    ActorReference updatedBy,
    ReferralState state,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public ReferralDraft {
    Objects.requireNonNull(id, "referral id");
    Objects.requireNonNull(originOrganization, "origin organization");
    if (!"Organization".equals(originOrganization.resourceType())) {
      throw new IllegalArgumentException("Referral origin must be an Organization");
    }
    originFacility = Objects.requireNonNull(originFacility, "origin facility");
    originFacility.ifPresent(
        facility -> {
          if (!"Facility".equals(facility.resourceType())) {
            throw new IllegalArgumentException("Referral origin facility reference is invalid");
          }
        });
    Objects.requireNonNull(patientReference, "patient reference");
    Objects.requireNonNull(careRequirement, "care requirement");
    Objects.requireNonNull(createdBy, "created by");
    Objects.requireNonNull(updatedBy, "updated by");
    Objects.requireNonNull(state, "Referral state");
    if (version < 1) {
      throw new IllegalArgumentException("draft version must be positive");
    }
    Objects.requireNonNull(createdAt, "created at");
    Objects.requireNonNull(updatedAt, "updated at");
  }

  public ReferralDraft revise(
      PatientReference replacementPatientReference,
      CareRequirement replacementCareRequirement,
      ActorReference editor,
      Instant changedAt) {
    if (state != ReferralState.DRAFT) {
      throw new IllegalStateException("only a draft Referral can be edited");
    }
    return new ReferralDraft(
        id,
        originOrganization,
        originFacility,
        replacementPatientReference,
        replacementCareRequirement,
        createdBy,
        editor,
        ReferralState.DRAFT,
        Math.addExact(version, 1),
        createdAt,
        changedAt);
  }

  public ReferralDraft submit(ActorReference submitter, Instant submittedAt) {
    if (state != ReferralState.DRAFT) {
      throw new IllegalStateException("only a draft Referral can be submitted");
    }
    return new ReferralDraft(
        id,
        originOrganization,
        originFacility,
        patientReference,
        careRequirement,
        createdBy,
        submitter,
        ReferralState.SUBMITTED,
        Math.addExact(version, 1),
        createdAt,
        submittedAt);
  }

  @Override
  public String toString() {
    return "ReferralDraft[id="
        + id
        + ", status="
        + state
        + ", version="
        + version
        + ", patientReference=redacted]";
  }
}
