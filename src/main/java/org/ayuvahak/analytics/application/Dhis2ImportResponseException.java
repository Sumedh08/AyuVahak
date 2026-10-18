package org.ayuvahak.analytics.application;

/** Sanitized parse/contract failure; the raw remote response is deliberately not retained. */
public final class Dhis2ImportResponseException extends RuntimeException {

  public Dhis2ImportResponseException() {
    super("DHIS2 import response did not match the configured synchronous profile.");
  }
}
