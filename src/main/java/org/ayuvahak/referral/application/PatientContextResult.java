package org.ayuvahak.referral.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Purpose-limited transient response with no patient identifier or source payload. */
public record PatientContextResult(
    Status status,
    List<PatientContextSection> sections,
    Optional<PatientContextSourceEvidence> sourceEvidence) {

  public PatientContextResult {
    Objects.requireNonNull(status, "patient-context status");
    sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
    sourceEvidence = Objects.requireNonNull(sourceEvidence, "source evidence");
    if (status != Status.AVAILABLE && !sections.isEmpty()) {
      throw new IllegalArgumentException("non-available outcomes cannot disclose context sections");
    }
    if (status == Status.AVAILABLE && sourceEvidence.isEmpty()) {
      throw new IllegalArgumentException("available context requires source evidence");
    }
  }

  public enum Status {
    AVAILABLE,
    UNAVAILABLE,
    INVALID,
    STALE
  }

  @Override
  public String toString() {
    return "PatientContextResult[status="
        + status
        + ", sectionCount="
        + sections.size()
        + ", patientContent=redacted]";
  }
}
