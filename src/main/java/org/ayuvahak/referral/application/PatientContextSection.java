package org.ayuvahak.referral.application;

import java.util.List;
import java.util.Objects;

/** Purpose-limited projection for one clinical category, with no narrative or raw FHIR fields. */
public record PatientContextSection(
    PatientContextCategory category, List<PatientContextCode> codes) {

  private static final int MAX_CODES_PER_SECTION = 50;

  public PatientContextSection {
    Objects.requireNonNull(category, "patient-context category");
    codes = List.copyOf(Objects.requireNonNull(codes, "codes"));
    if (codes.size() > MAX_CODES_PER_SECTION) {
      throw new IllegalArgumentException("patient-context section exceeds its code limit");
    }
  }

  @Override
  public String toString() {
    return "PatientContextSection[content=redacted]";
  }
}
