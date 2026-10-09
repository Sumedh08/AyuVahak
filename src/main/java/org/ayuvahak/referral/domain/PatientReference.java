package org.ayuvahak.referral.domain;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;

/** Minimal issuer-scoped patient reference; source identifiers never appear in its string form. */
public record PatientReference(
    ExternalReference patient, Optional<ExternalReference> encounterOrCase) {

  private static final int MAX_ISSUER_LENGTH = 256;
  private static final int MAX_IDENTIFIER_LENGTH = 512;

  public PatientReference {
    Objects.requireNonNull(patient, "patient reference");
    encounterOrCase = Objects.requireNonNull(encounterOrCase, "encounter or case reference");
    validateReference(patient, "Patient");
    encounterOrCase.ifPresent(reference -> validateReference(reference, null));
  }

  private static void validateReference(ExternalReference reference, String requiredType) {
    if (reference.issuer().value().length() > MAX_ISSUER_LENGTH
        || reference.value().length() > MAX_IDENTIFIER_LENGTH) {
      throw new IllegalArgumentException("external patient reference exceeds its size limit");
    }
    if (requiredType != null && !requiredType.equals(reference.resourceType())) {
      throw new IllegalArgumentException("patient reference must identify a Patient resource");
    }
  }

  @Override
  public String toString() {
    return "PatientReference[redacted]";
  }
}
