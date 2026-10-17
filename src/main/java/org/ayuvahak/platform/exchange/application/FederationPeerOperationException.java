package org.ayuvahak.platform.exchange.application;

import java.util.Objects;

/** Sanitized transport result. OUTCOME_UNKNOWN must retain the same owner operation identity. */
public final class FederationPeerOperationException extends RuntimeException {

  private final FederationPeerOperationDisposition disposition;
  private final FederationPeerOperationFailure failure;

  public FederationPeerOperationException(
      FederationPeerOperationDisposition disposition, FederationPeerOperationFailure failure) {
    super("Federated peer operation did not complete: " + Objects.requireNonNull(failure));
    this.disposition = Objects.requireNonNull(disposition);
    this.failure = failure;
  }

  public FederationPeerOperationDisposition disposition() {
    return disposition;
  }

  public FederationPeerOperationFailure failure() {
    return failure;
  }

  @Override
  public String toString() {
    return "FederationPeerOperationException[disposition="
        + disposition
        + ", failure="
        + failure
        + "]";
  }
}
