package org.ayuvahak.referral.application;

import java.util.Objects;
import org.ayuvahak.referral.domain.SubmittedReferralRevision;

/** Safe command outcome for an explicit Referral amendment. */
public record ReferralAmendmentCommandResult(
    SubmittedReferralRevision revision, boolean commandReplayed, int responseStatus) {

  public ReferralAmendmentCommandResult {
    Objects.requireNonNull(revision, "amended Referral revision");
    if (responseStatus < 200 || responseStatus > 299) {
      throw new IllegalArgumentException("successful command status is required");
    }
  }
}
