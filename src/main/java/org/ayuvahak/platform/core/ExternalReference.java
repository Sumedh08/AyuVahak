package org.ayuvahak.platform.core;

import java.util.Objects;

/** An external identifier whose issuer/source and resource type are part of its identity. */
public record ExternalReference(IssuerId issuer, String resourceType, String value) {

  public ExternalReference {
    Objects.requireNonNull(issuer, "issuer");
    if (resourceType == null || !resourceType.matches("[A-Za-z][A-Za-z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("resource type must be a nonblank identifier token");
    }
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          "external identifier value must be nonblank, trimmed, and contain no control characters");
    }
  }

  @Override
  public String toString() {
    return "ExternalReference[redacted]";
  }
}
