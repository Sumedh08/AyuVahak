package org.ayuvahak.matching.adapter.configuration;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Non-secret identity reference used for Matching's separately scoped owner reads. */
@Validated
@ConfigurationProperties("ayuvahak.matching")
public record MatchingProperties(@NotBlank String ownerReadClientId) {

  public MatchingProperties {
    if (ownerReadClientId == null
        || ownerReadClientId.isBlank()
        || !ownerReadClientId.equals(ownerReadClientId.strip())
        || ownerReadClientId.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          "Matching owner-read service client id must be nonblank, trimmed, and control-free");
    }
  }
}
