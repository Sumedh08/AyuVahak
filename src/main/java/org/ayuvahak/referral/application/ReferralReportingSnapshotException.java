package org.ayuvahak.referral.application;

/** Safe application failure for invalid or conflicting owner snapshot requests. */
public final class ReferralReportingSnapshotException extends RuntimeException {

  public enum Reason {
    INVALID_REQUEST,
    REQUEST_ID_CONFLICT
  }

  private final Reason reason;

  public ReferralReportingSnapshotException(Reason reason) {
    super("Referral reporting snapshot request could not be completed safely.");
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
