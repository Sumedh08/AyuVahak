package org.ayuvahak.platform.observability;

import java.util.Objects;

/** A deployment's explicit rationale for not selecting an SLI as an objective. */
public record ServiceLevelOmission(ServiceLevelIndicator indicator, String rationale) {

  public ServiceLevelOmission {
    Objects.requireNonNull(indicator, "indicator is required");
    rationale = boundedText(rationale, "rationale", 256);
  }

  static String boundedText(String value, String field, int maxLength) {
    if (value == null || value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(field + " must contain 1 to " + maxLength + " characters");
    }
    if (value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " must not contain control characters");
    }
    return value.trim();
  }
}
