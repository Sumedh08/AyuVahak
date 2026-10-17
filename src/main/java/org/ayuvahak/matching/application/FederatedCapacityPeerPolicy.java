package org.ayuvahak.matching.application;

import java.util.Optional;
import org.ayuvahak.matching.domain.RemoteFacilityReference;

/** Resolves only administrator-approved peer and source-version bindings. */
public interface FederatedCapacityPeerPolicy {

  Optional<RemoteCapacityPeerTarget> resolve(
      RemoteFacilityReference facility, String dimensionCode);
}
