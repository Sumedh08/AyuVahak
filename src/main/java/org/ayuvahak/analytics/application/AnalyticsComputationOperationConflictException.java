package org.ayuvahak.analytics.application;

/** A stable computation request ID was reused for a different request or state transition. */
public final class AnalyticsComputationOperationConflictException extends RuntimeException {

  public AnalyticsComputationOperationConflictException() {
    super("The Analytics computation request conflicts with its durable prior operation.");
  }
}
