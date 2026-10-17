package org.ayuvahak.platform.exchange.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/** Federation-specific identity and facility scope attached to a Phase 9 partner profile. */
public record FederationPeerConfiguration(
    DeploymentId deploymentId,
    ExternalReference organization,
    String protocolProfile,
    List<ExternalReference> facilityScopes) {

  private static final String FEDERATION_PROFILE = "AyuVahak-Federation-v1";

  public FederationPeerConfiguration {
    Objects.requireNonNull(deploymentId, "deploymentId");
    Objects.requireNonNull(organization, "organization");
    if (!"Organization".equals(organization.resourceType())) {
      throw new IllegalArgumentException("federation authority scope must be an Organization");
    }
    if (!FEDERATION_PROFILE.equals(protocolProfile)) {
      throw new IllegalArgumentException("federation protocol profile is unsupported");
    }
    facilityScopes = List.copyOf(Objects.requireNonNull(facilityScopes, "facilityScopes"));
    if (facilityScopes.size() > 100) {
      throw new IllegalArgumentException("federation facility scope is too large");
    }
    if (new HashSet<>(facilityScopes).size() != facilityScopes.size()) {
      throw new IllegalArgumentException("federation facility scope contains duplicates");
    }
    for (ExternalReference scope : facilityScopes) {
      if (!"Facility".equals(scope.resourceType())
          || "*".equals(scope.value())
          || !organization.issuer().equals(scope.issuer())) {
        throw new IllegalArgumentException(
            "federation facility scope must be an explicit reference in the peer namespace");
      }
    }
  }

  public boolean permits(ExternalReference organization, ExternalReference requestedScope) {
    Objects.requireNonNull(organization, "organization");
    Objects.requireNonNull(requestedScope, "requestedScope");
    return this.organization.equals(organization) && permits(requestedScope);
  }

  public boolean permits(ExternalReference requestedScope) {
    Objects.requireNonNull(requestedScope, "requestedScope");
    return organization.equals(requestedScope) || facilityScopes.contains(requestedScope);
  }

  public static String supportedProtocolProfile() {
    return FEDERATION_PROFILE;
  }
}
