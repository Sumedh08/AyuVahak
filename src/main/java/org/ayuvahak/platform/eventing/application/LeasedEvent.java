package org.ayuvahak.platform.eventing.application;

import java.util.Objects;

/** Event and fencing token held by a worker for the duration of one delivery attempt. */
public record LeasedEvent(LeaseToken leaseToken, InternalEvent event) {

  public LeasedEvent {
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(event, "event");
    if (!leaseToken.eventId().equals(event.eventId())) {
      throw new IllegalArgumentException("lease token and event identifiers must match");
    }
  }
}
