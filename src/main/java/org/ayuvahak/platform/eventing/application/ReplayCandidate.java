package org.ayuvahak.platform.eventing.application;

import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.event.EventType;

/** Internal replay precondition; the envelope is never returned by an HTTP adapter. */
public record ReplayCandidate(
    InternalId eventId, int generation, String state, EventType eventType, byte[] envelopeJson) {
  public ReplayCandidate {
    Objects.requireNonNull(eventId, "eventId");
    if (generation < 1) throw new IllegalArgumentException("generation must be positive");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(eventType, "eventType");
    envelopeJson = Objects.requireNonNull(envelopeJson, "envelopeJson").clone();
  }

  @Override
  public byte[] envelopeJson() {
    return envelopeJson.clone();
  }
}
