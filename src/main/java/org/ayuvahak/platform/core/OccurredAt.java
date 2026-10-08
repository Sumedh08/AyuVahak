package org.ayuvahak.platform.core;

import java.time.Instant;
import java.util.Objects;

/** Time at which a source says an occurrence happened. */
public record OccurredAt(Instant value) {

  public OccurredAt {
    Objects.requireNonNull(value, "value");
  }
}
