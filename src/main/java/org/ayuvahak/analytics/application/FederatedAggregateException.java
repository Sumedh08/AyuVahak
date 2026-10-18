package org.ayuvahak.analytics.application;

/** Sanitized failure classification for named aggregate federation operations. */
public final class FederatedAggregateException extends RuntimeException {

  private final Reason reason;

  public FederatedAggregateException(Reason reason) {
    super("Federated aggregate operation could not be completed: " + reason.name());
    this.reason = reason;
  }

  public FederatedAggregateException(Reason reason, Throwable cause) {
    super("Federated aggregate operation could not be completed: " + reason.name(), cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  public enum Reason {
    NOT_AUTHORIZED,
    SNAPSHOT_NOT_FOUND,
    SNAPSHOT_NOT_CURRENT,
    DEFINITION_INCOMPATIBLE,
    MAPPING_NOT_FOUND,
    MAPPING_NOT_EFFECTIVE,
    OPERATION_CONFLICT,
    RECEIPT_CONFLICT,
    INCOMPLETE_SNAPSHOT,
    DISCLOSURE_NOT_ALLOWED,
    TRUST_NOT_AVAILABLE,
    OUTCOME_UNKNOWN,
    MALFORMED,
    STORE_UNAVAILABLE
  }
}
