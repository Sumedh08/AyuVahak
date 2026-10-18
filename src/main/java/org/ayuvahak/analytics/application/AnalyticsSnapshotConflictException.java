package org.ayuvahak.analytics.application;

/** A stable computation request identity was reused for different source inputs. */
public final class AnalyticsSnapshotConflictException extends RuntimeException {

  public AnalyticsSnapshotConflictException() {
    super("aggregate computation identity conflicts with its stored result");
  }
}
