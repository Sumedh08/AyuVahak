package org.ayuvahak.platform.audit.application;

/** Safe failure used when required audit evidence could not be durably stored. */
public final class AuditPersistenceUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AuditPersistenceUnavailableException(Throwable cause) {
    super("Audit evidence could not be persisted", cause);
  }
}
