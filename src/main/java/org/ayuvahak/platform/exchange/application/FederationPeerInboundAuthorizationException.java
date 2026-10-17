package org.ayuvahak.platform.exchange.application;

import java.util.Objects;

/** Safe denial/unavailability result for a named inbound federation operation. */
public final class FederationPeerInboundAuthorizationException extends RuntimeException {

  private final Failure failure;

  public FederationPeerInboundAuthorizationException(Failure failure) {
    super("Federated peer operation is not authorized: " + Objects.requireNonNull(failure));
    this.failure = failure;
  }

  public Failure failure() {
    return failure;
  }

  @Override
  public String toString() {
    return "FederationPeerInboundAuthorizationException[failure=" + failure + "]";
  }

  public enum Failure {
    DENIED,
    TRUST_UNAVAILABLE
  }
}
