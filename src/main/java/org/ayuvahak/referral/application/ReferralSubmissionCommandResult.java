package org.ayuvahak.referral.application;

import java.util.Objects;
import org.ayuvahak.referral.domain.SubmittedReferralRevision;

/** Safe result of a successful Referral submission command. */
public record ReferralSubmissionCommandResult(
    SubmittedReferralRevision revision, boolean commandReplayed, int responseStatus) {

  public ReferralSubmissionCommandResult {
    Objects.requireNonNull(revision, "submitted revision");
    if (responseStatus < 200 || responseStatus > 299) {
      throw new IllegalArgumentException("successful command status is required");
    }
  }
}
