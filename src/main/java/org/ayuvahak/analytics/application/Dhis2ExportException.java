package org.ayuvahak.analytics.application;

/** Sanitized Analytics export failure; it never includes remote response content or target URLs. */
public final class Dhis2ExportException extends RuntimeException {

  private final Reason reason;

  public Dhis2ExportException(Reason reason) {
    super("DHIS2 aggregate export did not complete: " + reason);
    this.reason = reason;
  }

  public Dhis2ExportException(Reason reason, Throwable cause) {
    super("DHIS2 aggregate export did not complete: " + reason, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  public enum Reason {
    NOT_AUTHORIZED,
    SNAPSHOT_NOT_FOUND,
    SNAPSHOT_NOT_CURRENT,
    MAPPING_NOT_FOUND,
    MAPPING_NOT_EFFECTIVE,
    MAPPING_CONFLICT,
    INCOMPLETE_SNAPSHOT,
    ZERO_NOT_ALLOWED,
    DISCLOSURE_WITHHELD,
    DISCLOSURE_UNAVAILABLE,
    TARGET_AUTHENTICATION_FAILURE,
    TARGET_AUTHORIZATION_FAILURE,
    TARGET_METADATA_VALIDATION_FAILURE,
    TARGET_RATE_LIMITED,
    TARGET_REMOTE_FAILURE,
    TARGET_PROFILE_UNAVAILABLE,
    OUTCOME_UNKNOWN,
    RECONCILIATION_REQUIRED,
    INVALID_RESPONSE,
    STORE_UNAVAILABLE
  }
}
