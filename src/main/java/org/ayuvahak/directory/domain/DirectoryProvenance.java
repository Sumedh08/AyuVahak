package org.ayuvahak.directory.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;
import org.ayuvahak.platform.core.ActorReference;

/** Minimal origin and recording evidence attached to one current Directory fact. */
public record DirectoryProvenance(
    String method,
    Instant observedAt,
    ActorReference recordedBy,
    Instant recordedAt,
    String reasonCode) {

  private static final Pattern TOKEN = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");

  public DirectoryProvenance {
    requireToken(method, "method");
    Objects.requireNonNull(observedAt, "observedAt");
    Objects.requireNonNull(recordedBy, "recordedBy");
    Objects.requireNonNull(recordedAt, "recordedAt");
    requireToken(reasonCode, "reasonCode");
  }

  private static void requireToken(String value, String label) {
    if (value == null || !TOKEN.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be a stable uppercase code");
    }
  }
}
