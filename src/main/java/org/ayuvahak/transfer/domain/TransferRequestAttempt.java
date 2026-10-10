package org.ayuvahak.transfer.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** A directed request bound to one Referral revision and one recommendation candidate. */
public record TransferRequestAttempt(
    InternalId id,
    InternalId journeyId,
    int attemptNumber,
    InternalId referralId,
    long referralRevision,
    InternalId recommendationId,
    ExternalReference destinationFacility,
    Status status,
    ActorReference requestedBy,
    Instant requestedAt,
    Instant expiresAt,
    Optional<Instant> terminalOccurredAt,
    Optional<Instant> terminalRecordedAt,
    long version) {

  public TransferRequestAttempt {
    Objects.requireNonNull(id, "request attempt id");
    Objects.requireNonNull(journeyId, "journey id");
    if (attemptNumber < 1) {
      throw new IllegalArgumentException("attempt number must be positive");
    }
    Objects.requireNonNull(referralId, "Referral id");
    if (referralRevision < 1) {
      throw new IllegalArgumentException("Referral revision must be positive");
    }
    Objects.requireNonNull(recommendationId, "recommendation id");
    Objects.requireNonNull(destinationFacility, "destination Facility");
    if (!"urn:ayuvahak:directory".equals(destinationFacility.issuer().value())
        || !"Facility".equals(destinationFacility.resourceType())) {
      throw new IllegalArgumentException("destination must be a Directory Facility reference");
    }
    InternalId.parse(destinationFacility.value());
    Objects.requireNonNull(status, "request status");
    Objects.requireNonNull(requestedBy, "requester");
    Objects.requireNonNull(requestedAt, "request time");
    Objects.requireNonNull(expiresAt, "request expiry");
    terminalOccurredAt = Objects.requireNonNull(terminalOccurredAt, "terminal occurrence time");
    terminalRecordedAt = Objects.requireNonNull(terminalRecordedAt, "terminal recorded time");
    if (!expiresAt.isAfter(requestedAt)) {
      throw new IllegalArgumentException("request expiry must follow its request time");
    }
    if (version < 1) {
      throw new IllegalArgumentException("request version must be positive");
    }
    boolean requiresTerminalTimes = status == Status.EXPIRED || status == Status.WITHDRAWN;
    if (terminalOccurredAt.isPresent() != requiresTerminalTimes
        || terminalRecordedAt.isPresent() != requiresTerminalTimes) {
      throw new IllegalArgumentException(
          "expired and withdrawn requests require terminal occurrence and recorded times");
    }
  }

  public enum Status {
    PENDING,
    ACCEPTED,
    REJECTED,
    EXPIRED,
    WITHDRAWN
  }
}
