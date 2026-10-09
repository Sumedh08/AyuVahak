package org.ayuvahak.referral.application;

import java.util.Objects;
import org.ayuvahak.referral.domain.ReferralDraft;
import org.ayuvahak.referral.domain.ReferralResolution;

/** Safe command outcome for explicit Referral closure. */
public record ReferralResolutionCommandResult(
    ReferralDraft referral,
    ReferralResolution resolution,
    boolean commandReplayed,
    int responseStatus) {

  public ReferralResolutionCommandResult {
    Objects.requireNonNull(referral, "resolved Referral");
    Objects.requireNonNull(resolution, "Referral resolution");
    if (responseStatus < 200 || responseStatus > 299) {
      throw new IllegalArgumentException("successful command status is required");
    }
  }
}
