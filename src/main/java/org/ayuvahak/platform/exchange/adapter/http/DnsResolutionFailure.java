package org.ayuvahak.platform.exchange.adapter.http;

import java.net.UnknownHostException;

/** Sanitized DNS failure reason used to preserve timeout and policy classifications. */
final class DnsResolutionFailure extends UnknownHostException {

  private final Reason reason;

  DnsResolutionFailure(Reason reason) {
    super("Configured partner DNS resolution did not complete safely.");
    this.reason = reason;
  }

  Reason reason() {
    return reason;
  }

  enum Reason {
    FAILED,
    TIMED_OUT,
    CAPACITY_EXCEEDED,
    DESTINATION_REJECTED
  }
}
