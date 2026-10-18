package org.ayuvahak.analytics.application;

import java.util.Objects;
import java.util.UUID;

/** Stable owner operation identity for exporting exactly one immutable snapshot revision. */
public record Dhis2ExportRequest(UUID operationId, UUID snapshotId) {
  public Dhis2ExportRequest {
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(snapshotId, "snapshot id");
  }
}
