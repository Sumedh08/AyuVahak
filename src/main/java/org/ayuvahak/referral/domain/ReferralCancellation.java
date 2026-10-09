package org.ayuvahak.referral.domain;

import java.time.Instant;
import java.util.Objects;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.InternalId;

/** Immutable evidence of the origin's explicit Referral cancellation. */
public record ReferralCancellation(
    InternalId referralId,
    String reasonCode,
    ActorReference cancelledBy,
    Instant cancelledAt,
    long referralVersion) {

  public ReferralCancellation {
    Objects.requireNonNull(referralId, "Referral id");
    if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("cancellation reason must be a valid coded value");
    }
    Objects.requireNonNull(cancelledBy, "cancelled by");
    Objects.requireNonNull(cancelledAt, "cancelled at");
    if (referralVersion < 1) {
      throw new IllegalArgumentException("Referral version must be positive");
    }
  }
}
