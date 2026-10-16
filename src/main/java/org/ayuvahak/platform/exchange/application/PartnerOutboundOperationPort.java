package org.ayuvahak.platform.exchange.application;

/** Technical outbound command seam; owner contexts retain business retry and outcome meaning. */
public interface PartnerOutboundOperationPort {

  PartnerOutboundOperationResponse execute(PartnerOutboundOperationRequest request);
}
