package org.ayuvahak.analytics.application;

/** Safe mapping administration state or authorization failure. */
public final class Dhis2MappingException extends RuntimeException {

  private final Reason reason;

  public Dhis2MappingException(Reason reason) {
    super("DHIS2 mapping operation did not complete: " + reason);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  public enum Reason {
    NOT_AUTHORIZED,
    NOT_FOUND,
    CONFLICT
  }
}
