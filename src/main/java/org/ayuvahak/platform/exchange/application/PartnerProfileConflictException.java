package org.ayuvahak.platform.exchange.application;

/** Safe owner-level outcome for a duplicate key or stale expected configuration version. */
public final class PartnerProfileConflictException extends RuntimeException {

  public PartnerProfileConflictException() {
    super("The partner profile changed or already exists.");
  }
}
