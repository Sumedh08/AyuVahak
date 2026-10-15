package org.ayuvahak.referral.application;

import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.referral.domain.PatientReference;

/** Referral-internal policy input; the PatientReference never leaves Referral. */
public record PatientContextDisclosureRequest(
    ExecutionContext context,
    InternalId referralId,
    long referralRevision,
    InternalId transferId,
    InternalId requestAttemptId,
    String purposeCode,
    PatientReference patientReference) {

  public PatientContextDisclosureRequest {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(referralId, "Referral id");
    if (referralRevision < 1) {
      throw new IllegalArgumentException("Referral revision must be positive");
    }
    Objects.requireNonNull(transferId, "Transfer id");
    Objects.requireNonNull(requestAttemptId, "request attempt id");
    if (purposeCode == null || !purposeCode.matches("[A-Z][A-Z0-9_.-]{0,63}")) {
      throw new IllegalArgumentException("purpose code is invalid");
    }
    Objects.requireNonNull(patientReference, "PatientReference");
  }

  @Override
  public String toString() {
    return "PatientContextDisclosureRequest[referralRevision="
        + referralRevision
        + ", purposeCode="
        + purposeCode
        + ", patientReference=redacted]";
  }
}
