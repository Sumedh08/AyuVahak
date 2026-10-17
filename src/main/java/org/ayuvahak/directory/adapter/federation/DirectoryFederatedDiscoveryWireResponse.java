package org.ayuvahak.directory.adapter.federation;

import java.util.List;

/** Narrow JSON wire shape for the named Directory remote-discovery federation operation. */
public record DirectoryFederatedDiscoveryWireResponse(
    String protocolProfile,
    String sourceDeploymentId,
    String requestedScopeIssuer,
    String requestedScopeResourceType,
    String requestedScopeValue,
    String sourcePolicyVersion,
    String observedAt,
    String completeness,
    List<Fact> facts) {

  public record Fact(
      String resourceType,
      String resourceId,
      String organizationResourceId,
      String displayName,
      Boolean active,
      Long sourceVersion,
      String sourceUpdatedAt) {}
}
