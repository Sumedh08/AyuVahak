package org.ayuvahak.platform.core;

import java.util.UUID;

/** Identifier of the immediate command or event that caused another fact. */
public record CausationId(String value) {

  private static final String SAFE_VALUE = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";

  public CausationId {
    if (value == null || !value.matches(SAFE_VALUE)) {
      throw new IllegalArgumentException(
          "causation identifier must use 1-128 safe ASCII characters");
    }
  }

  public static CausationId generate() {
    return new CausationId(UUID.randomUUID().toString());
  }

  @Override
  public String toString() {
    return value;
  }
}
