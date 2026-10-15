package org.ayuvahak.referral.application;

import java.util.List;
import java.util.Objects;
import org.ayuvahak.referral.domain.PatientReference;

/** Transient typed projection from a clinical source; it is never a persistence model. */
public record PatientContextSourceDocument(
    Status status,
    PatientReference subject,
    List<PatientContextSection> sections,
    PatientContextSourceEvidence evidence) {

  public PatientContextSourceDocument {
    Objects.requireNonNull(status, "source status");
    Objects.requireNonNull(subject, "source subject");
    sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
    Objects.requireNonNull(evidence, "source evidence");
    if (status != Status.AVAILABLE && !sections.isEmpty()) {
      throw new IllegalArgumentException(
          "unavailable source outcomes cannot carry context sections");
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
    return "PatientContextSourceDocument[status="
        + status
        + ", sectionCount="
        + sections.size()
        + ", subject=redacted]";
  }
}
