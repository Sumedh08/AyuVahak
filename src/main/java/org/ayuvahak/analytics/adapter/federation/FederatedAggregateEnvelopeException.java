package org.ayuvahak.analytics.adapter.federation;

/** Sanitized rejection for malformed or unsupported peer aggregate envelopes. */
public final class FederatedAggregateEnvelopeException extends RuntimeException {

  public FederatedAggregateEnvelopeException() {
    super("Federated aggregate envelope is malformed or unsupported.");
  }
}
