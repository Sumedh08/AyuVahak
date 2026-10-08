package org.ayuvahak.platform.audit.domain;

/** Selects the durability boundary required for one minimized audit fact. */
public enum AuditActionKind {
  PROTECTED_MUTATION,
  SENSITIVE_READ,
  SECURITY_OUTCOME
}
