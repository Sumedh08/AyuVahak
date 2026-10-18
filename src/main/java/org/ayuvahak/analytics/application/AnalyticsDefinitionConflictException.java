package org.ayuvahak.analytics.application;

/** An immutable definition version was reused with different semantics or skipped. */
public final class AnalyticsDefinitionConflictException extends RuntimeException {

  public AnalyticsDefinitionConflictException() {
    super("indicator definition version conflicts with its immutable history");
  }
}
