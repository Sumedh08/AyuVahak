package org.ayuvahak.platform.exchange.application;

import java.util.Optional;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;

/** Internal transport adapter port; only the exchange application service calls it. */
public interface PartnerHttpTransportPort {

  PartnerOutboundReadResponse execute(
      PartnerProfileVersion activeProfile,
      PartnerOutboundReadRequest request,
      Optional<PartnerBearerToken> bearerToken);

  /** Rechecks current trust inside the bounded worker immediately before the socket request. */
  default PartnerOutboundReadResponse execute(
      PartnerProfileVersion activeProfile,
      PartnerOutboundReadRequest request,
      Optional<PartnerBearerToken> bearerToken,
      Runnable beforeAttempt) {
    beforeAttempt.run();
    return execute(activeProfile, request, bearerToken);
  }

  default PartnerOutboundReadResponse execute(
      PartnerProfileVersion activeProfile, PartnerOutboundReadRequest request) {
    return execute(activeProfile, request, Optional.empty());
  }
}
