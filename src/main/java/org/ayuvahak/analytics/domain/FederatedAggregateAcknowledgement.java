package org.ayuvahak.analytics.domain;

import java.util.Objects;
import java.util.UUID;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/** Minimal receiver acknowledgement; it proves receipt classification, not public-health truth. */
public record FederatedAggregateAcknowledgement(
    DeploymentId receivingDeployment,
    UUID operationId,
    UUID sourceSnapshotId,
    String contentFingerprint,
    UUID receiptId,
    FederatedAggregateReceipt.State state) {

  public FederatedAggregateAcknowledgement {
    Objects.requireNonNull(receivingDeployment, "receiving deployment");
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(sourceSnapshotId, "source snapshot id");
    if (contentFingerprint == null || !contentFingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("content fingerprint is invalid");
    }
    Objects.requireNonNull(receiptId, "receipt id");
    Objects.requireNonNull(state, "receipt state");
  }
}
