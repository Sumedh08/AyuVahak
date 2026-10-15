package org.ayuvahak.referral.application;

/** Transfer could not safely verify the selected receiving participant. */
public final class TransferPatientContextParticipationUnavailableException
    extends RuntimeException {

  public TransferPatientContextParticipationUnavailableException(Throwable cause) {
    super("Transfer participant verification is unavailable", cause);
  }
}
