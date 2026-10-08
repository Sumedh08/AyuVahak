package org.ayuvahak.platform.audit.domain;

/** Safe authentication categories; this value never contains a credential or token claim. */
public enum AuditAuthenticationEvidence {
  NONE,
  INVALID_CREDENTIAL,
  TRUST_SOURCE_UNAVAILABLE,
  HUMAN_ACCESS_TOKEN,
  SERVICE_ACCESS_TOKEN,
  CONFIGURED_SERVICE_IDENTITY
}
