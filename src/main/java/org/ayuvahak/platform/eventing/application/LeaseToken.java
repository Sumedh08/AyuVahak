package org.ayuvahak.platform.eventing.application;

import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;

/** Fencing token for one claim. A newer lease generation invalidates every older token. */
public record LeaseToken(InternalId eventId, int deliveryGeneration, long leaseGeneration) {

  public LeaseToken {
    Objects.requireNonNull(eventId, "eventId");
    if (deliveryGeneration < 1 || leaseGeneration < 1) {
      throw new IllegalArgumentException("lease token generations must be positive");
    }
  }
}
