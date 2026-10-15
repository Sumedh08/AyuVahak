package org.ayuvahak.platform.exchange.application;

import java.time.Instant;
import java.util.Objects;

/** Short-lived in-memory bearer token; it must never enter persistence or diagnostics. */
public record PartnerBearerToken(String value, Instant expiresAt) {

  public PartnerBearerToken {
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(expiresAt, "expiresAt");
    if (value.isBlank()
        || value.length() > 8_192
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("partner bearer token is outside the allowed bounds");
    }
  }

  public String authorizationHeader() {
    return "Bearer " + value;
  }

  @Override
  public String toString() {
    return "PartnerBearerToken[value=redacted, expiresAt=" + expiresAt + "]";
  }
}
