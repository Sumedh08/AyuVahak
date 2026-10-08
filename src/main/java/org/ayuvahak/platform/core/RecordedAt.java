package org.ayuvahak.platform.core;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Time at which AyuVahak recorded a fact, captured from an injectable clock. */
public record RecordedAt(Instant value) {

  public RecordedAt {
    Objects.requireNonNull(value, "value");
  }

  public static RecordedAt capture(Clock clock) {
    return new RecordedAt(Objects.requireNonNull(clock, "clock").instant());
  }
}
