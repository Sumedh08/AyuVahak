package org.ayuvahak.platform.eventing.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;

public record DeliveryReceiptClaim(
    State state, Optional<InternalId> eventId, Optional<Integer> generation) {
  public enum State {
    OWNER,
    REPLAY,
    KEY_REUSED,
    IN_PROGRESS
  }

  public DeliveryReceiptClaim {
    Objects.requireNonNull(state, "state");
    eventId = Objects.requireNonNull(eventId, "eventId");
    generation = Objects.requireNonNull(generation, "generation");
  }
}
