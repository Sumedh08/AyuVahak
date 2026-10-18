package org.ayuvahak.analytics.application;

/** Safe failure classes for configuration, privacy evaluation, and reservation lifecycle. */
public final class DisclosureReservationException extends RuntimeException {

  private final Reason reason;

  public DisclosureReservationException(Reason reason) {
    super(messageFor(reason));
    this.reason = reason;
  }

  public DisclosureReservationException(Reason reason, Throwable cause) {
    super(messageFor(reason), cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  private static String messageFor(Reason reason) {
    return switch (reason) {
      case NOT_AUTHORIZED -> "disclosure operation is not authorized";
      case POLICY_NOT_FOUND -> "disclosure policy is not configured";
      case POLICY_VERSION_CONFLICT ->
          "disclosure policy version changed; retry with current policy";
      case LINEAGE_INVALID -> "disclosure lineage is incomplete or inconsistent";
      case OPERATION_ID_REUSED -> "disclosure operation identity was reused with changed content";
      case RESERVATION_NOT_FOUND -> "disclosure reservation is unavailable";
      case INVALID_STATE -> "disclosure reservation state does not permit this transition";
      case NO_SEND_UNPROVEN -> "no-send evidence does not prove that request bytes stayed local";
      case STORE_UNAVAILABLE -> "disclosure history is unavailable; release was not allowed";
    };
  }

  public enum Reason {
    NOT_AUTHORIZED,
    POLICY_NOT_FOUND,
    POLICY_VERSION_CONFLICT,
    LINEAGE_INVALID,
    OPERATION_ID_REUSED,
    RESERVATION_NOT_FOUND,
    INVALID_STATE,
    NO_SEND_UNPROVEN,
    STORE_UNAVAILABLE
  }
}
