package org.ayuvahak.analytics.application;

import java.util.Objects;
import java.util.UUID;
import org.ayuvahak.analytics.domain.AnalyticsFinalizationReason;

/** Explicit compare-and-finalize command for one complete current revision. */
public record FinalizeAggregateSnapshotRequest(
    UUID operationId,
    UUID snapshotId,
    int expectedRevisionNumber,
    AnalyticsFinalizationReason reason) {

  public FinalizeAggregateSnapshotRequest {
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(snapshotId, "snapshot id");
    Objects.requireNonNull(reason, "finalization reason");
    requireInternalId(operationId, "operation id");
    requireInternalId(snapshotId, "snapshot id");
    if (expectedRevisionNumber < 1) {
      throw new IllegalArgumentException("expected revision must be positive");
    }
  }

  private static void requireInternalId(UUID id, String name) {
    if (id.version() != 4 || id.variant() != 2) {
      throw new IllegalArgumentException(name + " must be an RFC 4122 UUIDv4");
    }
  }
}
