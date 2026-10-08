package org.ayuvahak.platform.core;

import java.util.Objects;
import java.util.UUID;

/** An opaque AyuVahak identifier using the platform's UUIDv4 convention. */
public record InternalId(UUID value) {

  public InternalId {
    Objects.requireNonNull(value, "value");
    if (value.version() != 4 || value.variant() != 2) {
      throw new IllegalArgumentException("internal identifiers must be RFC 4122 UUIDv4 values");
    }
  }

  public static InternalId generate() {
    return new InternalId(UUID.randomUUID());
  }

  public static InternalId parse(String serialized) {
    if (serialized == null
        || !serialized.matches(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
      throw new IllegalArgumentException("internal identifier must be a canonical UUIDv4 string");
    }
    return new InternalId(UUID.fromString(serialized));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
