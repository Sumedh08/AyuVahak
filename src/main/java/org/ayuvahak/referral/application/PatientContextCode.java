package org.ayuvahak.referral.application;

import java.net.URI;
import java.util.Objects;

/** A coded value only; free-text narratives and display strings are intentionally excluded. */
public record PatientContextCode(String system, String code) {

  public PatientContextCode {
    Objects.requireNonNull(system, "code system");
    Objects.requireNonNull(code, "code");
    try {
      if (!URI.create(system).isAbsolute() || system.length() > 256) {
        throw new IllegalArgumentException("code system must be a bounded absolute URI");
      }
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("code system must be a bounded absolute URI");
    }
    if (code.isBlank() || code.length() > 128 || code.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("coded value is outside the supported bounds");
    }
  }

  @Override
  public String toString() {
    return "PatientContextCode[content=redacted]";
  }
}
