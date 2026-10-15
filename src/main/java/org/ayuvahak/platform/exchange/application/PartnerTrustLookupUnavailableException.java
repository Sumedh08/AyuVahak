package org.ayuvahak.platform.exchange.application;

/** Raised when the current partner trust configuration cannot be read safely. */
public final class PartnerTrustLookupUnavailableException extends RuntimeException {

  public PartnerTrustLookupUnavailableException(Throwable cause) {
    super("Partner trust configuration is unavailable.", cause);
  }
}
