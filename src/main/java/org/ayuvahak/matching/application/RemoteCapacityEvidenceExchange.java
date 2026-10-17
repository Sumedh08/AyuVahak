package org.ayuvahak.matching.application;

import org.ayuvahak.matching.domain.RemoteCapacityEvidence;
import org.ayuvahak.matching.domain.RemoteFacilityReference;

/** Matching-owned request for one named remote Capacity evidence observation. */
public interface RemoteCapacityEvidenceExchange {

  RemoteCapacityEvidence request(
      RemoteCapacityPeerTarget peer, RemoteFacilityReference facility, String dimensionCode);
}
