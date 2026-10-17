package org.ayuvahak.transfer.application;

import java.util.Objects;

/** Sanitized owner-level failure; it contains no endpoint, payload or credential data. */
public final class FederatedReceivingOriginExchangeException extends RuntimeException {

  private final Disposition disposition;

  public FederatedReceivingOriginExchangeException(Disposition disposition) {
    super("Federated receiving exchange did not complete: " + Objects.requireNonNull(disposition));
    this.disposition = disposition;
  }

  public static FederatedReceivingOriginExchangeException outcomeUnknown() {
    return new FederatedReceivingOriginExchangeException(Disposition.OUTCOME_UNKNOWN);
  }

  public static FederatedReceivingOriginExchangeException notSent() {
    return new FederatedReceivingOriginExchangeException(Disposition.NOT_SENT);
  }

  public static FederatedReceivingOriginExchangeException rejected() {
    return new FederatedReceivingOriginExchangeException(Disposition.REJECTED);
  }

  public static FederatedReceivingOriginExchangeException invalidResponse() {
    return new FederatedReceivingOriginExchangeException(Disposition.INVALID_RESPONSE);
  }

  public Disposition disposition() {
    return disposition;
  }

  public enum Disposition {
    NOT_SENT,
    REJECTED,
    OUTCOME_UNKNOWN,
    INVALID_RESPONSE
  }
}
