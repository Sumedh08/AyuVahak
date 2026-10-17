package org.ayuvahak.platform.exchange.application;

/** Technical boundary for finite named federated owner operations. */
public interface FederationPeerOperationPort {

  PartnerOutboundOperationResponse execute(FederationPeerOperationRequest request);
}
