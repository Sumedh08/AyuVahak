package org.ayuvahak.transfer.application;

import java.util.Objects;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Eligibility-only proof that a requester participates in the selected pre-arrival Transfer. */
public record PatientContextParticipation(
    InternalId referralId,
    InternalId transferId,
    InternalId requestAttemptId,
    long referralRevision,
    ExternalReference receivingFacility) {

  public PatientContextParticipation {
    Objects.requireNonNull(referralId, "Referral id");
    Objects.requireNonNull(transferId, "Transfer id");
    Objects.requireNonNull(requestAttemptId, "request attempt id");
    if (referralRevision < 1) {
      throw new IllegalArgumentException("Referral revision must be positive");
    }
    Objects.requireNonNull(receivingFacility, "receiving facility");
    if (!"urn:ayuvahak:directory".equals(receivingFacility.issuer().value())
        || !"Facility".equals(receivingFacility.resourceType())) {
      throw new IllegalArgumentException("receiving facility must be a Directory Facility");
    }
  }
}
