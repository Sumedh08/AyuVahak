package org.ayuvahak.platform.exchange.application;

/** Safe signal that a configured secret provider could not resolve references at this time. */
public final class PartnerSecretProviderUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PartnerSecretProviderUnavailableException() {
    super("Configured secret provider is unavailable");
  }

  @Override
  public String toString() {
    return "PartnerSecretProviderUnavailableException";
  }
}
