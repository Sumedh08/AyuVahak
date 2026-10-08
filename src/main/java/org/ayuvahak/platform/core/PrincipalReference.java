package org.ayuvahak.platform.core;

import java.util.Objects;

/** An identity-provider subject scoped by the exact issuer that authenticated it. */
public record PrincipalReference(IssuerId issuer, String subject) {

  public PrincipalReference {
    Objects.requireNonNull(issuer, "issuer");
    if (subject == null
        || subject.isBlank()
        || !subject.equals(subject.strip())
        || subject.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          "principal subject must be nonblank, trimmed, and contain no control characters");
    }
  }

  @Override
  public String toString() {
    return "PrincipalReference[redacted]";
  }
}
