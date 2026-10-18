package org.ayuvahak.analytics.application;

/**
 * Safe Analytics computation failure; messages contain no owner payload or caller-supplied text.
 */
public final class AnalyticsComputationException extends RuntimeException {

  private final Reason reason;

  public AnalyticsComputationException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public AnalyticsComputationException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  public enum Reason {
    NOT_AUTHORIZED,
    DEFINITION_NOT_FOUND,
    OWNER_CONTRACT_INVALID,
    ZERO_NOT_ALLOWED,
    VALUE_OVERFLOW
  }
}
