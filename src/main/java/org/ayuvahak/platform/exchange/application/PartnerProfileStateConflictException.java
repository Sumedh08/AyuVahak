package org.ayuvahak.platform.exchange.application;

/** Safe owner-level outcome for a disallowed partner profile lifecycle transition. */
public final class PartnerProfileStateConflictException extends RuntimeException {

  public PartnerProfileStateConflictException() {
    super("The partner profile is not in a state that permits this operation.");
  }
}
