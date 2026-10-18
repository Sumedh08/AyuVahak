package org.ayuvahak.analytics.application;

/** Sanitized conflict for immutable, versioned federated aggregate mappings. */
public final class FederatedAggregateMappingConflictException extends RuntimeException {

  public FederatedAggregateMappingConflictException() {
    super("Federated aggregate mapping conflicts with its immutable scope or version.");
  }
}
