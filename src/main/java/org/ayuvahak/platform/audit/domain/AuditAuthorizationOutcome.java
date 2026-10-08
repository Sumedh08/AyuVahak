package org.ayuvahak.platform.audit.domain;

/** Safe policy decision category, independent of authentication. */
public enum AuditAuthorizationOutcome {
  NOT_EVALUATED,
  ALLOWED,
  DENIED
}
