package org.ayuvahak.platform.exchange.application;

import java.util.Optional;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;

/** Internal bounded POST transport; only the exchange application service calls it. */
public interface PartnerHttpOperationTransportPort {

  PartnerOutboundOperationResponse execute(
      PartnerProfileVersion activeProfile,
      PartnerOutboundOperationRequest request,
      Optional<PartnerBearerToken> bearerToken,
      Runnable beforeAttempt);
}
