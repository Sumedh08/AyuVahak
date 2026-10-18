package org.ayuvahak.referral.application;

/** Source completeness for one bounded Referral reporting snapshot. */
public enum ReferralReportingCompleteness {
  COMPLETE,
  PARTIAL,
  MISSING_SOURCE,
  STALE_SOURCE,
  NOT_APPLICABLE
}
