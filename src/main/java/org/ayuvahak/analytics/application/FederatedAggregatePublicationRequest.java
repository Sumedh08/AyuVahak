package org.ayuvahak.analytics.application;

import java.util.Objects;
import java.util.UUID;

/** Logical publication request; the peer and endpoint are resolved only from admin mapping. */
public record FederatedAggregatePublicationRequest(UUID snapshotId, String mappingKey) {

  public FederatedAggregatePublicationRequest {
    Objects.requireNonNull(snapshotId, "snapshot ID");
    if (mappingKey == null || !mappingKey.matches("[A-Z0-9][A-Z0-9._:-]{0,63}")) {
      throw new IllegalArgumentException("aggregate mapping key is invalid");
    }
  }
}
