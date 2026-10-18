package org.ayuvahak.analytics.application;

/** A mapping version identity was reused for changed target or policy semantics. */
public final class Dhis2MappingConflictException extends RuntimeException {
  public Dhis2MappingConflictException() {
    super("DHIS2 mapping version conflicts with its stored immutable definition.");
  }
}
