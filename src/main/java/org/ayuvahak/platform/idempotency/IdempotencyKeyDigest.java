package org.ayuvahak.platform.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** A one-way digest of a caller-supplied idempotency key. The raw key is never retained. */
public final class IdempotencyKeyDigest {

  private final String value;

  private IdempotencyKeyDigest(String value) {
    this.value = value;
  }

  /** Validate the public header form and return its SHA-256 digest. */
  public static IdempotencyKeyDigest from(String rawKey) {
    if (rawKey == null
        || rawKey.isEmpty()
        || rawKey.length() > 128
        || rawKey.codePoints().anyMatch(codePoint -> codePoint < 0x21 || codePoint > 0x7e)) {
      throw new IllegalArgumentException(
          "idempotency key must contain 1 to 128 visible ASCII characters without whitespace");
    }
    return new IdempotencyKeyDigest(sha256(rawKey.getBytes(StandardCharsets.UTF_8)));
  }

  public String value() {
    return value;
  }

  static String sha256(byte[] value) {
    Objects.requireNonNull(value, "value");
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JDK SHA-256 provider is unavailable", exception);
    }
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof IdempotencyKeyDigest that && value.equals(that.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return "IdempotencyKeyDigest[redacted]";
  }
}
