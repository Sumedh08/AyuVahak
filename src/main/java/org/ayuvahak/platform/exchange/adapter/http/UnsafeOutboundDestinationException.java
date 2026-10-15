package org.ayuvahak.platform.exchange.adapter.http;

/** Sanitized rejection when an approved partner endpoint resolves outside deployment policy. */
final class UnsafeOutboundDestinationException extends RuntimeException {

  private final Reason reason;

  UnsafeOutboundDestinationException(Reason reason) {
    super("Outbound destination rejected by local network policy.");
    this.reason = reason;
  }

  Reason reason() {
    return reason;
  }

  enum Reason {
    PORT_NOT_ALLOWED,
    NO_ADDRESSES,
    PRIVATE_ADDRESS_NOT_ALLOWED,
    FORBIDDEN_ADDRESS
  }
}
