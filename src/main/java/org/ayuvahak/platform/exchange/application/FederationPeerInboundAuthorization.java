package org.ayuvahak.platform.exchange.application;

import java.util.Objects;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/**
 * Minimal current-trust evidence for a receiving owner adapter; it contains no payload or grant.
 */
public record FederationPeerInboundAuthorization(
    PartnerKey partnerKey,
    DeploymentId peerDeployment,
    ExternalReference organizationScope,
    InternalId profileId,
    int profileVersion,
    ActorReference authenticatedPeerPrincipal,
    AllowedOperation operation,
    ExternalReference scope) {

  public FederationPeerInboundAuthorization {
    Objects.requireNonNull(partnerKey, "partnerKey");
    Objects.requireNonNull(peerDeployment, "peerDeployment");
    Objects.requireNonNull(organizationScope, "organizationScope");
    if (!"Organization".equals(organizationScope.resourceType())) {
      throw new IllegalArgumentException("peer authorization requires its Organization scope");
    }
    Objects.requireNonNull(profileId, "profileId");
    if (profileVersion < 1) {
      throw new IllegalArgumentException("partner configuration version must be positive");
    }
    Objects.requireNonNull(authenticatedPeerPrincipal, "authenticatedPeerPrincipal");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(scope, "scope");
  }

  @Override
  public String toString() {
    return "FederationPeerInboundAuthorization[partnerKey="
        + partnerKey
        + ", peerDeployment="
        + peerDeployment
        + ", profileVersion="
        + profileVersion
        + ", operation="
        + operation.operationCode()
        + ", scope=redacted]";
  }
}
