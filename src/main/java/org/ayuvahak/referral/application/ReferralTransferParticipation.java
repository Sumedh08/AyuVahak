package org.ayuvahak.referral.application;

import java.util.Objects;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Transfer's minimal eligibility proof; it contains no PatientReference or clinical content. */
public record ReferralTransferParticipation(
    InternalId referralId,
    InternalId transferId,
    InternalId requestAttemptId,
    long referralRevision,
    ExternalReference receivingFacility) {

  public ReferralTransferParticipation {
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
