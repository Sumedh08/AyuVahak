package org.ayuvahak.referral.application;

import java.util.Objects;
import org.ayuvahak.referral.domain.ReferralDraft;

/** Safe command outcome used by the Referral HTTP adapter. */
public record ReferralDraftCommandResult(
    ReferralDraft draft, boolean commandReplayed, int responseStatus) {

  public ReferralDraftCommandResult {
    Objects.requireNonNull(draft, "draft");
    if (responseStatus < 200 || responseStatus > 299) {
      throw new IllegalArgumentException("successful command status is required");
    }
  }
}
