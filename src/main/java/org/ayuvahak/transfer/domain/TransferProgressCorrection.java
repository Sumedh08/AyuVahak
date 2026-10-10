package org.ayuvahak.transfer.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Append-only correction evidence; it never rewrites a reported fact or Transfer state. */
public record TransferProgressCorrection(
    InternalId id,
    InternalId transferId,
    InternalId journeyId,
    InternalId correctsProgressFactId,
    Kind kind,
    Optional<Instant> correctedOccurredAt,
    Optional<String> correctedResultCode,
    String reasonCode,
    ActorReference recordedBy,
    String authorityCode,
    ExternalReference organization,
    ExternalReference facility,
    Instant recordedAt,
    long transferVersion,
    long aggregateSequence) {

  public TransferProgressCorrection {
    Objects.requireNonNull(id, "correction id");
    Objects.requireNonNull(transferId, "Transfer id");
    Objects.requireNonNull(journeyId, "TransferJourney id");
    Objects.requireNonNull(correctsProgressFactId, "corrected progress fact id");
    Objects.requireNonNull(kind, "correction kind");
    correctedOccurredAt = Objects.requireNonNull(correctedOccurredAt, "corrected occurrence time");
    correctedResultCode = Objects.requireNonNull(correctedResultCode, "corrected result code");
    if ((kind == Kind.OCCURRENCE_TIME)
            != (correctedOccurredAt.isPresent() && correctedResultCode.isEmpty())
        || (kind == Kind.REPORTED_RESULT)
            != (correctedResultCode.isPresent() && correctedOccurredAt.isEmpty())) {
      throw new IllegalArgumentException("correction values must match the correction kind");
    }
    if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
      throw new IllegalArgumentException("a structured correction reason is required");
    }
    Objects.requireNonNull(recordedBy, "recorded by");
    if (authorityCode == null || !authorityCode.matches("[A-Za-z][A-Za-z0-9]{1,63}")) {
      throw new IllegalArgumentException("correction authority code is invalid");
    }
    requireDirectoryReference(organization, "Organization");
    requireDirectoryReference(facility, "Facility");
    Objects.requireNonNull(recordedAt, "recording time");
    if (transferVersion < 2 || aggregateSequence < 2) {
      throw new IllegalArgumentException(
          "correction versions must be greater than their initial value");
    }
  }

  private static void requireDirectoryReference(ExternalReference reference, String type) {
    Objects.requireNonNull(reference, type + " reference");
    if (!"urn:ayuvahak:directory".equals(reference.issuer().value())
        || !type.equals(reference.resourceType())) {
      throw new IllegalArgumentException("correction scope must use a Directory reference");
    }
    InternalId.parse(reference.value());
  }

  public enum Kind {
    OCCURRENCE_TIME,
    REPORTED_RESULT
  }
}
