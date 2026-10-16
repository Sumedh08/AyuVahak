package org.ayuvahak.transfer.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Receiving-authority assessment kept separate from the original Transfer acceptance. */
public record DestinationChangeAssessment(
    InternalId id,
    InternalId operationId,
    ReceivingAssessment.Kind kind,
    ReceivingAssessment.Outcome outcome,
    String reasonCode,
    Optional<String> evidenceReference,
    ActorReference recordedBy,
    ExternalReference organization,
    ExternalReference facility,
    Instant occurredAt,
    Instant recordedAt) {

  public DestinationChangeAssessment {
    Objects.requireNonNull(id, "assessment id");
    Objects.requireNonNull(operationId, "destination-change operation id");
    Objects.requireNonNull(kind, "assessment kind");
    Objects.requireNonNull(outcome, "assessment outcome");
    if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
      throw new IllegalArgumentException("assessment reason code is invalid");
    }
    evidenceReference = Objects.requireNonNull(evidenceReference, "evidence reference");
    evidenceReference.ifPresent(
        value -> {
          if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) {
            throw new IllegalArgumentException("assessment evidence reference is invalid");
          }
        });
    Objects.requireNonNull(recordedBy, "recorded-by actor");
    require(organization, "Organization");
    require(facility, "Facility");
    Objects.requireNonNull(occurredAt, "occurred at");
    Objects.requireNonNull(recordedAt, "recorded at");
  }

  private static void require(ExternalReference reference, String type) {
    Objects.requireNonNull(reference, type);
    if (!"urn:ayuvahak:directory".equals(reference.issuer().value())
        || !type.equals(reference.resourceType())) {
      throw new IllegalArgumentException("receiving " + type + " must be a Directory reference");
    }
    InternalId.parse(reference.value());
  }
}
