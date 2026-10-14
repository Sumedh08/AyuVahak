package org.ayuvahak.referral.application;

/** Safe failure raised when Transfer cannot establish amendment status from owner state. */
public final class TransferAmendmentStatusUnavailableException extends RuntimeException {

  public TransferAmendmentStatusUnavailableException(Throwable cause) {
    super("Transfer amendment status is unavailable", cause);
  }
}
