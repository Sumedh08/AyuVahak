package org.ayuvahak.analytics.application;

import java.util.Objects;
import java.util.UUID;

/** Stable command identity for establishing revision one of a computed snapshot series. */
public record RegisterAggregateSnapshotRequest(UUID operationId, UUID snapshotId) {

  public RegisterAggregateSnapshotRequest {
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(snapshotId, "snapshot id");
    requireInternalId(operationId, "operation id");
    requireInternalId(snapshotId, "snapshot id");
  }

  private static void requireInternalId(UUID id, String name) {
    if (id.version() != 4 || id.variant() != 2) {
      throw new IllegalArgumentException(name + " must be an RFC 4122 UUIDv4");
    }
  }
}
