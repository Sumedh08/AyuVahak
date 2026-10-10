package org.ayuvahak.transfer.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Minimal attributed evidence recorded by a receiving function. */
public record ReceivingAssessment(
    InternalId id,
    InternalId requestAttemptId,
    Kind kind,
    Outcome outcome,
    String reasonCode,
    Optional<String> evidenceReference,
    ActorReference recordedBy,
    String authorityCode,
    ExternalReference receivingOrganization,
    ExternalReference receivingFacility,
    Instant occurredAt,
    Instant recordedAt,
    long requestVersion) {

  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
  private static final Pattern EVIDENCE_REFERENCE =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

  public ReceivingAssessment {
    Objects.requireNonNull(id, "assessment id");
    Objects.requireNonNull(requestAttemptId, "request attempt id");
    Objects.requireNonNull(kind, "assessment kind");
    Objects.requireNonNull(outcome, "assessment outcome");
    if (reasonCode == null || !CODE.matcher(reasonCode).matches()) {
      throw new IllegalArgumentException("assessment reason must be a structured code");
    }
    evidenceReference = Objects.requireNonNull(evidenceReference, "evidence reference");
    evidenceReference.ifPresent(
        value -> {
          if (!EVIDENCE_REFERENCE.matcher(value).matches()) {
            throw new IllegalArgumentException("evidence reference must be a minimal identifier");
          }
        });
    Objects.requireNonNull(recordedBy, "actor");
    if (!kind.authorityCode().equals(authorityCode)) {
      throw new IllegalArgumentException("assessment must retain its functional authority");
    }
    Objects.requireNonNull(receivingOrganization, "receiving organization");
    Objects.requireNonNull(receivingFacility, "receiving Facility");
    if (!"urn:ayuvahak:directory".equals(receivingOrganization.issuer().value())
        || !"Organization".equals(receivingOrganization.resourceType())
        || !"urn:ayuvahak:directory".equals(receivingFacility.issuer().value())
        || !"Facility".equals(receivingFacility.resourceType())) {
      throw new IllegalArgumentException("receiving attribution must reference Directory entities");
    }
    Objects.requireNonNull(occurredAt, "occurrence time");
    Objects.requireNonNull(recordedAt, "recorded time");
    if (requestVersion < 1) {
      throw new IllegalArgumentException("request version must be positive");
    }
  }

  public enum Kind {
    CLINICAL_SUITABILITY("ClinicalSuitabilityAuthority"),
    OPERATIONAL_READINESS("OperationalReadinessAuthority");

    private final String authorityCode;

    Kind(String authorityCode) {
      this.authorityCode = authorityCode;
    }

    public String authorityCode() {
      return authorityCode;
    }
  }

  public enum Outcome {
    SUPPORTS,
    DOES_NOT_SUPPORT,
    INDETERMINATE
  }
}
