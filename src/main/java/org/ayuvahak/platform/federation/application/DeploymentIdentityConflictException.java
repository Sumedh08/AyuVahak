package org.ayuvahak.platform.federation.application;

/** A sanitized identity mismatch; never carries the installation-binding value or digest. */
public final class DeploymentIdentityConflictException extends RuntimeException {

  private final ConflictCode conflictCode;

  public DeploymentIdentityConflictException(ConflictCode conflictCode) {
    super("The registered deployment identity conflicts with this installation configuration.");
    this.conflictCode = conflictCode;
  }

  public ConflictCode conflictCode() {
    return conflictCode;
  }

  public enum ConflictCode {
    DEPLOYMENT_ID_MISMATCH,
    INSTALLATION_BINDING_MISMATCH
  }
}
