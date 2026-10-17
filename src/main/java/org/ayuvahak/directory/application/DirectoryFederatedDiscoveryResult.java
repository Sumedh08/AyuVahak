package org.ayuvahak.directory.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded outcome of a remote discovery and local projection reconciliation. */
public record DirectoryFederatedDiscoveryResult(
    Status status,
    int factsReceived,
    List<DirectoryFederatedProjectionPolicy.Result> reconciliations,
    Optional<Instant> sourceObservedAt) {

  public DirectoryFederatedDiscoveryResult {
    Objects.requireNonNull(status, "status");
    reconciliations = List.copyOf(Objects.requireNonNull(reconciliations, "reconciliations"));
    sourceObservedAt = Objects.requireNonNull(sourceObservedAt, "sourceObservedAt");
    if (factsReceived < 0 || factsReceived != reconciliations.size()) {
      throw new IllegalArgumentException("discovery result count is invalid");
    }
    if (status == Status.PEER_UNAVAILABLE && (factsReceived != 0 || sourceObservedAt.isPresent())) {
      throw new IllegalArgumentException("unavailable discovery cannot contain source facts");
    }
  }

  public enum Status {
    COMPLETE,
    PARTIAL,
    PEER_UNAVAILABLE
  }
}
