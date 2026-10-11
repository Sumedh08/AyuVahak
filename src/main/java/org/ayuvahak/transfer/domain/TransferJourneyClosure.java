package org.ayuvahak.transfer.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Transfer-owned terminal record that a Journey ended without a fulfilled Transfer. */
public record TransferJourneyClosure(
    InternalId journeyId,
    InternalId referralId,
    String reasonCode,
    ActorReference closedBy,
    String authorityCode,
    ExternalReference organization,
    Optional<ExternalReference> facility,
    Instant occurredAt,
    Instant recordedAt,
    long journeyVersion) {

  public TransferJourneyClosure {
    Objects.requireNonNull(journeyId, "journey id");
    Objects.requireNonNull(referralId, "Referral id");
    Objects.requireNonNull(reasonCode, "reason code");
    Objects.requireNonNull(closedBy, "closing actor");
    Objects.requireNonNull(authorityCode, "authority code");
    Objects.requireNonNull(organization, "origin organization");
    facility = Objects.requireNonNull(facility, "origin facility");
    Objects.requireNonNull(occurredAt, "occurrence time");
    Objects.requireNonNull(recordedAt, "recorded time");
    if (journeyVersion < 1) {
      throw new IllegalArgumentException("Journey version must be positive");
    }
    if (!reasonCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
      throw new IllegalArgumentException("reason code is invalid");
    }
  }
}
