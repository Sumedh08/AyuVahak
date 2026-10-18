package org.ayuvahak.analytics.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.exchange.domain.PartnerKey;

/** Safe receipt view for one peer aggregate; no source rows or clinical context exist here. */
public record FederatedAggregateReceipt(
    UUID receiptId,
    FederatedAggregateSnapshot snapshot,
    UUID operationId,
    ExternalReference targetOrganization,
    PartnerKey peerKey,
    int peerConfigurationVersion,
    Optional<String> mappingKey,
    Optional<Integer> mappingVersion,
    State state,
    String resultClass,
    Instant receivedAt,
    boolean replayed) {

  public FederatedAggregateReceipt {
    Objects.requireNonNull(receiptId, "receipt id");
    Objects.requireNonNull(snapshot, "aggregate snapshot");
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(targetOrganization, "target organization");
    Objects.requireNonNull(peerKey, "peer key");
    mappingKey = Objects.requireNonNull(mappingKey, "mapping key");
    mappingVersion = Objects.requireNonNull(mappingVersion, "mapping version");
    if (peerConfigurationVersion < 1
        || mappingKey.isPresent() != mappingVersion.isPresent()
        || mappingVersion.filter(version -> version < 1).isPresent()) {
      throw new IllegalArgumentException("federated aggregate receipt metadata is invalid");
    }
    Objects.requireNonNull(state, "receipt state");
    if (resultClass == null || !resultClass.matches("[A-Z][A-Z0-9_]{0,47}")) {
      throw new IllegalArgumentException("receipt result class is invalid");
    }
    Objects.requireNonNull(receivedAt, "received at");
  }

  @Override
  public String toString() {
    return "FederatedAggregateReceipt[receiptId=redacted, peer="
        + snapshot.sourceDeployment()
        + ", indicator="
        + snapshot.indicatorKey()
        + ", state="
        + state
        + ", value=redacted, replayed="
        + replayed
        + "]";
  }

  public enum State {
    COMBINABLE,
    INCOMPATIBLE
  }
}
