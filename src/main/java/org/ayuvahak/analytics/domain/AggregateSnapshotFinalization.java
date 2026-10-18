package org.ayuvahak.analytics.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable finalization evidence for one exact current revision. */
public record AggregateSnapshotFinalization(
    UUID operationId,
    UUID seriesId,
    UUID snapshotId,
    int revisionNumber,
    AnalyticsFinalizationReason reason,
    Instant finalizedAt,
    Optional<UUID> auditRecordId,
    boolean replayed) {

  public AggregateSnapshotFinalization {
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(seriesId, "series id");
    Objects.requireNonNull(snapshotId, "snapshot id");
    Objects.requireNonNull(reason, "finalization reason");
    Objects.requireNonNull(finalizedAt, "finalized at");
    auditRecordId = Objects.requireNonNull(auditRecordId, "audit record id");
    if (revisionNumber < 1) {
      throw new IllegalArgumentException("revision number must be positive");
    }
  }
}
