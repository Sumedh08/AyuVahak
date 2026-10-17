package org.ayuvahak.matching.application;

import java.time.Clock;
import java.util.Objects;
import org.ayuvahak.matching.domain.RemoteCapacityEvidence;
import org.ayuvahak.matching.domain.RemoteCapacityEvidenceProjector;
import org.ayuvahak.matching.domain.RemoteFacilityReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Retrieves and projects one remote observation under Matching's configured source policy. */
@Service
public final class MatchingRemoteCapacityEvidenceQueries {

  private final FederatedCapacityPeerPolicy peerPolicy;
  private final RemoteCapacityEvidenceExchange exchange;
  private final RemoteCapacityEvidenceProjectionStore projectionStore;
  private final RemoteCapacityEvidenceProjector projector;
  private final Clock clock;

  public MatchingRemoteCapacityEvidenceQueries(
      FederatedCapacityPeerPolicy peerPolicy,
      RemoteCapacityEvidenceExchange exchange,
      RemoteCapacityEvidenceProjectionStore projectionStore,
      RemoteCapacityEvidenceProjector projector,
      @Qualifier("auditClock") Clock clock) {
    this.peerPolicy = Objects.requireNonNull(peerPolicy, "peer policy");
    this.exchange = Objects.requireNonNull(exchange, "remote evidence exchange");
    this.projectionStore = Objects.requireNonNull(projectionStore, "projection store");
    this.projector = Objects.requireNonNull(projector, "evidence projector");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public RemoteCapacityEvidenceQueryResult query(
      RemoteFacilityReference facility, String dimensionCode) {
    Objects.requireNonNull(facility, "remote Facility reference");
    requireDimension(dimensionCode);
    var peer = peerPolicy.resolve(facility, dimensionCode);
    if (peer.isEmpty()) {
      return RemoteCapacityEvidenceQueryResult.unavailable(
          facility, dimensionCode, "PEER_NOT_CONFIGURED");
    }

    final RemoteCapacityEvidence response;
    try {
      response = exchange.request(peer.orElseThrow(), facility, dimensionCode);
    } catch (RemoteCapacityEvidenceExchangeException unavailable) {
      return RemoteCapacityEvidenceQueryResult.unavailable(
          facility, dimensionCode, unavailable.reason().name());
    }
    if (!response.facility().equals(facility) || !response.dimensionCode().equals(dimensionCode)) {
      return RemoteCapacityEvidenceQueryResult.unavailable(
          facility, dimensionCode, "RESPONSE_SCOPE_MISMATCH");
    }

    try {
      projectionStore.record(response);
      var current = projectionStore.find(facility, dimensionCode);
      if (current.isEmpty()) {
        return RemoteCapacityEvidenceQueryResult.unavailable(
            facility, dimensionCode, "PROJECTION_UNAVAILABLE");
      }
      return RemoteCapacityEvidenceQueryResult.from(
          projector.project(
              current.orElseThrow(),
              facility,
              dimensionCode,
              peer.orElseThrow().compatibility(),
              clock.instant()));
    } catch (DataAccessException unavailable) {
      return RemoteCapacityEvidenceQueryResult.unavailable(
          facility, dimensionCode, "PROJECTION_UNAVAILABLE");
    }
  }

  private static void requireDimension(String dimensionCode) {
    if (dimensionCode == null || !dimensionCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("dimension code must be a stable identifier");
    }
  }
}
