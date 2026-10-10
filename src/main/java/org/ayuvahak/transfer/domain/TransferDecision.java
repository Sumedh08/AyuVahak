package org.ayuvahak.transfer.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Explicit receiving Acceptance or Rejection, kept separate from Capacity reservation. */
public record TransferDecision(
    InternalId id,
    InternalId requestAttemptId,
    Kind kind,
    Optional<String> reasonCode,
    Optional<InternalId> transferId,
    ActorReference recordedBy,
    String authorityCode,
    ExternalReference receivingOrganization,
    ExternalReference receivingFacility,
    Instant occurredAt,
    Instant recordedAt,
    long requestVersion) {

  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

  public TransferDecision {
    Objects.requireNonNull(id, "decision id");
    Objects.requireNonNull(requestAttemptId, "request attempt id");
    Objects.requireNonNull(kind, "decision kind");
    reasonCode = Objects.requireNonNull(reasonCode, "reason code");
    reasonCode.ifPresent(
        value -> {
          if (!CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("decision reason must be a structured code");
          }
        });
    transferId = Objects.requireNonNull(transferId, "transfer id");
    if ((kind == Kind.ACCEPTED) != transferId.isPresent()) {
      throw new IllegalArgumentException("only Acceptance creates a Transfer reference");
    }
    if ((kind == Kind.REJECTED) != reasonCode.isPresent()) {
      throw new IllegalArgumentException("Rejection requires a structured reason");
    }
    Objects.requireNonNull(recordedBy, "actor");
    if (!"AcceptanceAuthority".equals(authorityCode)) {
      throw new IllegalArgumentException("receiving decision requires AcceptanceAuthority");
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
    ACCEPTED,
    REJECTED
  }
}
