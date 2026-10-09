package org.ayuvahak.capacity.application;

import org.ayuvahak.platform.core.InternalId;

/** The same issuer-scoped source identity was reused for different normalized evidence. */
public final class CapacitySourceIdentityConflictException extends RuntimeException {

  private final InternalId existingObservationId;

  public CapacitySourceIdentityConflictException(InternalId existingObservationId) {
    super("Capacity source identity conflicts with an earlier immutable observation.");
    this.existingObservationId = existingObservationId;
  }

  public InternalId existingObservationId() {
    return existingObservationId;
  }
}
