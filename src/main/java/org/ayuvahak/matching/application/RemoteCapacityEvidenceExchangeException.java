package org.ayuvahak.matching.application;

/** Sanitized inability to retrieve one named remote Capacity observation. */
public final class RemoteCapacityEvidenceExchangeException extends RuntimeException {

  private final Reason reason;

  public RemoteCapacityEvidenceExchangeException(Reason reason) {
    super("Remote Capacity evidence is unavailable or invalid.");
    this.reason = java.util.Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }

  @Override
  public String toString() {
    return "RemoteCapacityEvidenceExchangeException[reason=" + reason + "]";
  }

  public enum Reason {
    PEER_NOT_CONFIGURED,
    LOCAL_DEPLOYMENT_IDENTITY_UNAVAILABLE,
    TRUST_DENIED,
    TRUST_UNAVAILABLE,
    AUTHENTICATION_FAILED,
    REMOTE_REJECTED,
    OUTCOME_UNKNOWN,
    INVALID_RESPONSE
  }
}
