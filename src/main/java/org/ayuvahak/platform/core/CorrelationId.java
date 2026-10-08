package org.ayuvahak.platform.core;

import java.util.UUID;

/** Safe identifier for correlating one request or work chain. It conveys no authority. */
public record CorrelationId(String value) {

  private static final String SAFE_VALUE = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";

  public CorrelationId {
    if (value == null || !value.matches(SAFE_VALUE)) {
      throw new IllegalArgumentException(
          "correlation identifier must use 1-128 safe ASCII characters");
    }
  }

  public static CorrelationId generate() {
    return new CorrelationId(UUID.randomUUID().toString());
  }

  @Override
  public String toString() {
    return value;
  }
}
