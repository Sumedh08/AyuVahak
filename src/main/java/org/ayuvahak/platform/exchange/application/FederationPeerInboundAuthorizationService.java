package org.ayuvahak.platform.exchange.application;

import java.util.Objects;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.exchange.domain.FederationPeerOperations;
import org.ayuvahak.platform.exchange.domain.PartnerProfileState;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Protocol;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.RetryClass;
import org.ayuvahak.platform.federation.application.DeploymentIdentityRegistrationService;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Revalidates a named inbound peer contract before its owner invokes its own application port. */
@Service
@Lazy
public final class FederationPeerInboundAuthorizationService {

  private final PartnerTrustProfileStore profiles;
  private final DeploymentIdentityRegistrationService deploymentIdentity;

  public FederationPeerInboundAuthorizationService(
      PartnerTrustProfileStore profiles, DeploymentIdentityRegistrationService deploymentIdentity) {
    this.profiles = Objects.requireNonNull(profiles, "partner profiles");
    this.deploymentIdentity = Objects.requireNonNull(deploymentIdentity, "deployment identity");
  }

  public FederationPeerInboundAuthorization authorize(
      ActorReference authenticatedPeerPrincipal,
      DeploymentId authenticatedPeerDeployment,
      AllowedOperation operation,
      ExternalReference requestedScope) {
    Objects.requireNonNull(authenticatedPeerPrincipal, "authenticatedPeerPrincipal");
    Objects.requireNonNull(authenticatedPeerDeployment, "authenticatedPeerDeployment");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(requestedScope, "requestedScope");
    if (!FederationPeerOperations.supportsInbound(operation)
        || operation.protocol() != Protocol.JSON) {
      throw denied();
    }
    DeploymentId localDeployment;
    try {
      localDeployment =
          deploymentIdentity
              .current()
              .map(identity -> identity.deploymentId())
              .orElseThrow(FederationPeerInboundAuthorizationService::denied);
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
    if (localDeployment.equals(authenticatedPeerDeployment)) {
      throw denied();
    }
    try {
      PartnerProfileVersion profile =
          profiles
              .findActiveInboundPeer(
                  authenticatedPeerPrincipal,
                  authenticatedPeerDeployment,
                  operation,
                  requestedScope)
              .orElseThrow(FederationPeerInboundAuthorizationService::denied);
      var peer =
          profile
              .configuration()
              .federationPeer()
              .orElseThrow(FederationPeerInboundAuthorizationService::denied);
      if (profile.state() != PartnerProfileState.ACTIVE
          || profile.configuration().retryClass() != RetryClass.NEVER
          || !peer.deploymentId().equals(authenticatedPeerDeployment)
          || !peer.protocolProfile().equals(operation.profile())
          || !profile.configuration().allowedOperations().contains(operation)
          || !peer.permits(profile.configuration().organization(), requestedScope)) {
        throw denied();
      }
      return new FederationPeerInboundAuthorization(
          profile.configuration().partnerKey(),
          authenticatedPeerDeployment,
          profile.configuration().organization(),
          profile.id(),
          profile.version(),
          authenticatedPeerPrincipal,
          operation,
          requestedScope);
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private static FederationPeerInboundAuthorizationException denied() {
    return new FederationPeerInboundAuthorizationException(
        FederationPeerInboundAuthorizationException.Failure.DENIED);
  }

  private static FederationPeerInboundAuthorizationException unavailable() {
    return new FederationPeerInboundAuthorizationException(
        FederationPeerInboundAuthorizationException.Failure.TRUST_UNAVAILABLE);
  }
}
