package org.ayuvahak.referral.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.referral.domain.ReferralDraft;
import org.ayuvahak.referral.domain.SubmittedReferralRevision;

/** Origin-authorized current Referral view with its latest immutable submitted snapshot. */
public record ReferralCurrentView(
    ReferralDraft current,
    Optional<SubmittedReferralRevision> submittedRevision,
    boolean identifierRestricted) {

  public ReferralCurrentView {
    Objects.requireNonNull(current, "current Referral");
    submittedRevision = Objects.requireNonNull(submittedRevision, "submitted revision");
  }
}
