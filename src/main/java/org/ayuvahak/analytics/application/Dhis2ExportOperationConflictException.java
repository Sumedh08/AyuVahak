package org.ayuvahak.analytics.application;

/** A stable export operation identity was reused for different snapshot or target semantics. */
public final class Dhis2ExportOperationConflictException extends RuntimeException {
  public Dhis2ExportOperationConflictException() {
    super("DHIS2 export operation identity conflicts with its stored metadata.");
  }
}
