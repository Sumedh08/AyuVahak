package org.ayuvahak.referral.application;

import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;

/** Caller-selected active Transfer coordinates and a bounded purpose code; no patient input. */
public record ReferralPatientContextRequest(
    InternalId transferId, InternalId requestAttemptId, String purposeCode) {

  public ReferralPatientContextRequest {
    Objects.requireNonNull(transferId, "Transfer id");
    Objects.requireNonNull(requestAttemptId, "request attempt id");
    if (purposeCode == null || !purposeCode.matches("[A-Z][A-Z0-9_.-]{0,63}")) {
      throw new IllegalArgumentException("purpose code is invalid");
    }
  }
}
