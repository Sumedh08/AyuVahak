package org.ayuvahak.platform.exchange.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Safe transport failure with no raw URL, response body, or secret in its message. */
public final class PartnerTransportException extends RuntimeException {

  private final PartnerTransportFailure failure;
  private final Optional<Duration> retryAfter;

  public PartnerTransportException(PartnerTransportFailure failure) {
    this(failure, Optional.empty());
  }

  public PartnerTransportException(PartnerTransportFailure failure, Optional<Duration> retryAfter) {
    super("Configured partner exchange did not complete: " + Objects.requireNonNull(failure));
    this.failure = failure;
    this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
  }

  public PartnerTransportFailure failure() {
    return failure;
  }

  public Optional<Duration> retryAfter() {
    return retryAfter;
  }

  @Override
  public String toString() {
    return "PartnerTransportException[failure=" + failure + "]";
  }
}
