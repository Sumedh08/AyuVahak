package org.ayuvahak.analytics.application;

/** Sanitized failure for Analytics revision, correction, and finalization commands. */
public final class AnalyticsSnapshotRevisionException extends RuntimeException {

  public enum Reason {
    NOT_AUTHORIZED,
    SNAPSHOT_NOT_FOUND,
    SERIES_NOT_FOUND,
    SERIES_ALREADY_EXISTS,
    OPERATION_ID_REUSED,
    REVISION_CONFLICT,
    SERIES_MISMATCH,
    NO_CHANGE,
    CORRECTION_WINDOW_CLOSED,
    FINALIZED_CORRECTION_REQUIRED,
    INCOMPLETE_NOT_FINALIZABLE,
    AUDIT_UNAVAILABLE
  }

  private final Reason reason;

  public AnalyticsSnapshotRevisionException(Reason reason) {
    super(message(reason));
    this.reason = reason;
  }

  public AnalyticsSnapshotRevisionException(Reason reason, Throwable cause) {
    super(message(reason), cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  private static String message(Reason reason) {
    return switch (reason) {
      case NOT_AUTHORIZED -> "The Analytics snapshot lifecycle action is not authorized.";
      case SNAPSHOT_NOT_FOUND -> "The Analytics snapshot is unavailable.";
      case SERIES_NOT_FOUND -> "The current Analytics snapshot series is unavailable.";
      case SERIES_ALREADY_EXISTS -> "An initial revision already exists for this reporting period.";
      case OPERATION_ID_REUSED ->
          "The Analytics operation identity was reused with different content.";
      case REVISION_CONFLICT -> "The Analytics snapshot revision changed; reload before retrying.";
      case SERIES_MISMATCH ->
          "The replacement snapshot does not match the current reporting period.";
      case NO_CHANGE -> "The replacement snapshot contains no new source evidence.";
      case CORRECTION_WINDOW_CLOSED -> "The configured Analytics correction window has closed.";
      case FINALIZED_CORRECTION_REQUIRED ->
          "A finalized snapshot requires an explicitly authorized correction.";
      case INCOMPLETE_NOT_FINALIZABLE -> "Only a complete Analytics snapshot can be finalized.";
      case AUDIT_UNAVAILABLE ->
          "The Analytics lifecycle action could not proceed because required audit evidence was unavailable.";
    };
  }
}
