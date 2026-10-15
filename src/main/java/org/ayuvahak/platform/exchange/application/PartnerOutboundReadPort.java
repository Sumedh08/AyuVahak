package org.ayuvahak.platform.exchange.application;

import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;

/** Owner-context port for configured, allowlisted external read operations. */
public interface PartnerOutboundReadPort {

  /** Checks current trust configuration without acquiring credentials or contacting the partner. */
  void requireActive(PartnerKey partnerKey, AllowedOperation operation);

  PartnerOutboundReadResponse read(PartnerOutboundReadRequest request);
}
