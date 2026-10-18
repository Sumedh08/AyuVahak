package org.ayuvahak.transfer.application;

/** A reporting request ID was reused for different measure, period, attribution, or scope. */
public final class TransferReportingSnapshotConflictException extends RuntimeException {

  public TransferReportingSnapshotConflictException() {
    super("reporting request identity was reused with a different request");
  }
}
