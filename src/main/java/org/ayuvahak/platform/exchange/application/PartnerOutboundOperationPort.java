package org.ayuvahak.platform.exchange.application;

import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;

/** Technical outbound command seam; owner contexts retain business retry and outcome meaning. */
public interface PartnerOutboundOperationPort {

  /** Returns the active approved profile version without performing a network operation. */
  default int activeConfigurationVersion(PartnerKey partnerKey, AllowedOperation operation) {
    throw new UnsupportedOperationException("active partner profile lookup is not supported");
  }

  PartnerOutboundOperationResponse execute(PartnerOutboundOperationRequest request);
}
