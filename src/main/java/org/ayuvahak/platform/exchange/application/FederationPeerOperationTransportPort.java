package org.ayuvahak.platform.exchange.application;

import java.util.Optional;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;

/** Fixed Phase 11 peer-operation HTTP seam backed by Phase 9 endpoint and TLS safeguards. */
public interface FederationPeerOperationTransportPort {

  PartnerOutboundOperationResponse execute(
      PartnerProfileVersion activeProfile,
      FederationPeerOperationRequest request,
      Optional<PartnerBearerToken> bearerToken,
      Runnable beforeAttempt);
}
