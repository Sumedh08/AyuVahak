package org.ayuvahak.platform.fhir;

import java.util.Objects;
import java.util.Set;

public record FhirProfileContext(
    String release, String requiredProfileUri, Set<String> allowedProfileUris) {

  public FhirProfileContext(String release, String requiredProfileUri) {
    this(release, requiredProfileUri, Set.of(requiredProfileUri));
  }

  public FhirProfileContext {
    Objects.requireNonNull(release);
    Objects.requireNonNull(requiredProfileUri);
    Objects.requireNonNull(allowedProfileUris);
    allowedProfileUris = Set.copyOf(allowedProfileUris);
    if (release.isBlank() || requiredProfileUri.isBlank()) {
      throw new IllegalArgumentException("FHIR release and profile URI are required");
    }
    if (allowedProfileUris.isEmpty()
        || allowedProfileUris.stream().anyMatch(uri -> uri == null || uri.isBlank())
        || !allowedProfileUris.contains(requiredProfileUri)) {
      throw new IllegalArgumentException(
          "FHIR allowed profiles must be non-empty and include the required profile");
    }
  }
}
