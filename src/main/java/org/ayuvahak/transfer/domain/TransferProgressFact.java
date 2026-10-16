package org.ayuvahak.transfer.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** An attributable Transfer lifecycle fact; occurrence and recording times remain distinct. */
public record TransferProgressFact(
    InternalId id,
    InternalId transferId,
    InternalId journeyId,
    Kind kind,
    Optional<CoordinatedTransfer.Status> previousStatus,
    CoordinatedTransfer.Status status,
    Optional<String> exceptionCode,
    Optional<String> reasonCode,
    Optional<String> sourceReference,
    ActorReference recordedBy,
    String authorityCode,
    ExternalReference organization,
    ExternalReference facility,
    Instant occurredAt,
    Instant recordedAt,
    long transferVersion,
    long aggregateSequence) {

  public TransferProgressFact {
    Objects.requireNonNull(id, "progress fact id");
    Objects.requireNonNull(transferId, "Transfer id");
    Objects.requireNonNull(journeyId, "TransferJourney id");
    Objects.requireNonNull(kind, "progress fact kind");
    Objects.requireNonNull(previousStatus, "previous Transfer status");
    Objects.requireNonNull(status, "resulting Transfer status");
    Objects.requireNonNull(exceptionCode, "exception code");
    Objects.requireNonNull(reasonCode, "reason code");
    Objects.requireNonNull(sourceReference, "source reference");
    Objects.requireNonNull(recordedBy, "recorded by");
    if (authorityCode == null || !authorityCode.matches("[A-Za-z][A-Za-z0-9]{1,63}")) {
      throw new IllegalArgumentException("progress authority code is invalid");
    }
    requireDirectoryReference(organization, "Organization");
    requireDirectoryReference(facility, "Facility");
    Objects.requireNonNull(occurredAt, "occurrence time");
    Objects.requireNonNull(recordedAt, "recording time");
    if (transferVersion < 2 || aggregateSequence < 2) {
      throw new IllegalArgumentException(
          "progress versions must be greater than their initial value");
    }
  }

  private static void requireDirectoryReference(ExternalReference reference, String type) {
    Objects.requireNonNull(reference, type + " reference");
    if (!"urn:ayuvahak:directory".equals(reference.issuer().value())
        || !type.equals(reference.resourceType())) {
      throw new IllegalArgumentException("progress scope must use a Directory reference");
    }
    InternalId.parse(reference.value());
  }

  public enum Kind {
    DEPARTURE,
    INTERRUPTION,
    RESUMPTION,
    ARRIVAL,
    MOVEMENT_RECONCILIATION,
    HANDOVER,
    COMPLETION,
    CANCELLATION,
    FINAL_ABANDONMENT,
    CANCELLATION_RELEASE,
    REFERRAL_CANCELLATION,
    CAPACITY_EXCEPTION,
    CAPACITY_RECONFIRMATION
  }
}
