package org.ayuvahak.platform.exchange.application;

import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;

/** Technical token endpoint operation pinned to configured profile trust and egress controls. */
public interface PartnerTokenEndpointPort {

  PartnerTokenEndpointResponse requestToken(
      PartnerProfileVersion activeProfile, PartnerTokenEndpointRequest request);

  /** Rechecks current trust inside the bounded worker immediately before the token request. */
  default PartnerTokenEndpointResponse requestToken(
      PartnerProfileVersion activeProfile,
      PartnerTokenEndpointRequest request,
      Runnable beforeAttempt) {
    beforeAttempt.run();
    return requestToken(activeProfile, request);
  }
}
