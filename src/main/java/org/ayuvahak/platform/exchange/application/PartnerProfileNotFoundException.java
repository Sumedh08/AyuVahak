package org.ayuvahak.platform.exchange.application;

/** Safe owner-level outcome when a configured partner profile does not exist. */
public final class PartnerProfileNotFoundException extends RuntimeException {

  public PartnerProfileNotFoundException() {
    super("The partner trust profile was not found.");
  }
}
