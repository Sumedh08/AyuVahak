package org.ayuvahak.directory.application;

/** An active issuer/resource/value tuple is already associated within this concept type. */
public final class ActiveExternalIdentifierConflictException extends RuntimeException {

  public ActiveExternalIdentifierConflictException(Throwable cause) {
    super(
        "An active external identifier tuple already exists for this Directory concept type.",
        cause);
  }
}
