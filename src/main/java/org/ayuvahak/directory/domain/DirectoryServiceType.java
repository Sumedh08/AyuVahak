package org.ayuvahak.directory.domain;

import java.util.Objects;
import java.util.Optional;

/** A source-scoped coded classification of a Directory HealthcareService. */
public record DirectoryServiceType(String system, String code, Optional<String> version) {

  public DirectoryServiceType {
    if (system == null || system.isBlank() || code == null || code.isBlank()) {
      throw new IllegalArgumentException("service type requires a code system and code");
    }
    if (system.length() > 2048 || code.length() > 256) {
      throw new IllegalArgumentException("service type system and code must be bounded");
    }
    version = Objects.requireNonNull(version, "version");
    version.ifPresent(
        value -> {
          if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("service type version must be bounded");
          }
        });
  }
}
