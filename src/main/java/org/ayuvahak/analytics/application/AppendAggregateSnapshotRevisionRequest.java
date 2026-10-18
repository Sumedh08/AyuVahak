package org.ayuvahak.analytics.application;

import java.util.Objects;
import java.util.UUID;
import org.ayuvahak.analytics.domain.AnalyticsRevisionCause;

/** Compare-and-append command for a late-data revision or an authorized correction. */
public record AppendAggregateSnapshotRevisionRequest(
    UUID operationId,
    UUID expectedSnapshotId,
    int expectedRevisionNumber,
    UUID replacementSnapshotId,
    AnalyticsRevisionCause cause) {

  public AppendAggregateSnapshotRevisionRequest {
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(expectedSnapshotId, "expected snapshot id");
    Objects.requireNonNull(replacementSnapshotId, "replacement snapshot id");
    Objects.requireNonNull(cause, "revision cause");
    requireInternalId(operationId, "operation id");
    requireInternalId(expectedSnapshotId, "expected snapshot id");
    requireInternalId(replacementSnapshotId, "replacement snapshot id");
    if (expectedRevisionNumber < 1
        || cause == AnalyticsRevisionCause.INITIAL
        || expectedSnapshotId.equals(replacementSnapshotId)) {
      throw new IllegalArgumentException("aggregate revision command is invalid");
    }
  }

  private static void requireInternalId(UUID id, String name) {
    if (id.version() != 4 || id.variant() != 2) {
      throw new IllegalArgumentException(name + " must be an RFC 4122 UUIDv4");
    }
  }
}
