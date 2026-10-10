package org.ayuvahak.transfer.domain;

import java.time.Instant;
import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;

/** Transfer coordination state created by Acceptance; no Capacity is reserved here. */
public record CoordinatedTransfer(
    InternalId id,
    InternalId journeyId,
    InternalId requestAttemptId,
    Status status,
    Instant createdAt,
    long version) {

  public CoordinatedTransfer {
    Objects.requireNonNull(id, "transfer id");
    Objects.requireNonNull(journeyId, "journey id");
    Objects.requireNonNull(requestAttemptId, "request attempt id");
    Objects.requireNonNull(createdAt, "creation time");
    if (version < 1) {
      throw new IllegalArgumentException("transfer version must be positive");
    }
  }

  public enum Status {
    AWAITING_RESERVATION,
    READY
  }
}
