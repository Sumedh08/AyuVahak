package org.ayuvahak.platform.exchange.application;

import java.util.Arrays;
import java.util.Objects;

/** Mutable, closeable secret bytes resolved only for the duration of authentication work. */
public final class PartnerSecretMaterial implements AutoCloseable {

  private final char[] value;

  public PartnerSecretMaterial(char[] value) {
    this.value = Objects.requireNonNull(value, "value").clone();
    if (this.value.length == 0) {
      throw new IllegalArgumentException("secret material must not be empty");
    }
  }

  public char[] copy() {
    return value.clone();
  }

  @Override
  public void close() {
    Arrays.fill(value, '\0');
  }

  @Override
  public String toString() {
    return "PartnerSecretMaterial[value=redacted]";
  }
}
