package org.ayuvahak.referral.application;

import java.util.Objects;
import java.util.Set;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.referral.domain.PatientReference;

/** Referral-only transient input to the source adapter; it must not be logged or persisted. */
public record PatientContextSourceRequest(
    PatientReference patientReference,
    String purposeCode,
    Set<PatientContextCategory> permittedCategories,
    CorrelationId correlationId) {

  public PatientContextSourceRequest {
    Objects.requireNonNull(patientReference, "PatientReference");
    if (purposeCode == null || !purposeCode.matches("[A-Z][A-Z0-9_.-]{0,63}")) {
      throw new IllegalArgumentException("purpose code is invalid");
    }
    permittedCategories =
        Set.copyOf(Objects.requireNonNull(permittedCategories, "permitted categories"));
    Objects.requireNonNull(correlationId, "correlation id");
  }

  @Override
  public String toString() {
    return "PatientContextSourceRequest[purposeCode="
        + purposeCode
        + ", permittedCategories="
        + permittedCategories
        + ", patientReference=redacted]";
  }
}
