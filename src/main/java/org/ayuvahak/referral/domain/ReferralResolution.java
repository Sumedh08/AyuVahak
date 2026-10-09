package org.ayuvahak.referral.domain;

import java.time.Instant;
import java.util.Objects;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.InternalId;

/** Explicit, non-clinical closure of a Referral coordination request. */
public record ReferralResolution(
    InternalId referralId,
    ReferralResolutionOutcome outcome,
    String reasonCode,
    ActorReference resolvedBy,
    Instant resolvedAt,
    long referralVersion) {

  public ReferralResolution {
    Objects.requireNonNull(referralId, "Referral id");
    Objects.requireNonNull(outcome, "resolution outcome");
    Objects.requireNonNull(reasonCode, "resolution reason code");
    if (!reasonCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("resolution reason code is invalid");
    }
    Objects.requireNonNull(resolvedBy, "resolver");
    Objects.requireNonNull(resolvedAt, "resolved at");
    if (referralVersion < 1) {
      throw new IllegalArgumentException("Referral version must be positive");
    }
  }
}
