package org.ayuvahak.platform.exchange.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.exchange.domain.FederationPeerConfiguration;
import org.ayuvahak.platform.exchange.domain.PartnerProfileState;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.RetryClass;
import org.ayuvahak.platform.federation.application.DeploymentIdentityRegistrationService;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Revalidates configured peer trust around one named, non-retrying exchange attempt. */
@Service
@Lazy
public final class FederationPeerOperationService implements FederationPeerOperationPort {

  private final PartnerTrustProfileStore profiles;
  private final DeploymentIdentityRegistrationService deploymentIdentity;
  private final PartnerAuthorizationPort authorization;
  private final FederationPeerOperationTransportPort transport;

  public FederationPeerOperationService(
      PartnerTrustProfileStore profiles,
      DeploymentIdentityRegistrationService deploymentIdentity,
      PartnerAuthorizationPort authorization,
      FederationPeerOperationTransportPort transport) {
    this.profiles = Objects.requireNonNull(profiles, "partner profiles");
    this.deploymentIdentity = Objects.requireNonNull(deploymentIdentity, "deployment identity");
    this.authorization = Objects.requireNonNull(authorization, "partner authorization");
    this.transport = Objects.requireNonNull(transport, "federation peer transport");
  }

  @Override
  public PartnerOutboundOperationResponse execute(FederationPeerOperationRequest request) {
    Objects.requireNonNull(request, "request");
    PartnerProfileVersion profile;
    try {
      profile = requireActivePeer(request);
    } catch (FederationPeerOperationException denied) {
      if (denied.failure() == FederationPeerOperationFailure.TRUST_DENIED) {
        authorization.invalidate(request.partnerKey());
      }
      throw denied;
    }
    Runnable beforeAttempt = () -> requireSameActivePeer(profile, request);
    Optional<PartnerBearerToken> token;
    try {
      token = authorization.bearerToken(profile, beforeAttempt);
    } catch (FederationPeerOperationException denied) {
      throw denied;
    } catch (PartnerTrustLookupUnavailableException unavailable) {
      throw new FederationPeerOperationException(
          FederationPeerOperationDisposition.NOT_SENT,
          FederationPeerOperationFailure.TRUST_UNAVAILABLE);
    } catch (PartnerTransportException failure) {
      throw classify(failure);
    } catch (RuntimeException failure) {
      throw new FederationPeerOperationException(
          FederationPeerOperationDisposition.NOT_SENT,
          FederationPeerOperationFailure.AUTHENTICATION_FAILED);
    }
    try {
      return transport.execute(profile, request, token, beforeAttempt);
    } catch (PartnerTransportException failure) {
      throw classify(failure);
    } catch (PartnerTrustLookupUnavailableException unavailable) {
      throw new FederationPeerOperationException(
          FederationPeerOperationDisposition.NOT_SENT,
          FederationPeerOperationFailure.TRUST_UNAVAILABLE);
    }
  }

  private void requireSameActivePeer(
      PartnerProfileVersion expected, FederationPeerOperationRequest request) {
    PartnerProfileVersion current;
    try {
      current = requireActivePeer(request);
    } catch (FederationPeerOperationException unverified) {
      authorization.invalidate(request.partnerKey());
      throw new PartnerTransportException(
          unverified.failure() == FederationPeerOperationFailure.TRUST_UNAVAILABLE
              ? PartnerTransportFailure.TRUST_UNAVAILABLE
              : PartnerTransportFailure.PROFILE_NOT_ACTIVE);
    }
    if (!expected.id().equals(current.id()) || expected.version() != current.version()) {
      authorization.invalidate(request.partnerKey());
      throw new PartnerTransportException(PartnerTransportFailure.PROFILE_NOT_ACTIVE);
    }
  }

  private PartnerProfileVersion requireActivePeer(FederationPeerOperationRequest request) {
    AllowedOperation operation = request.operation();
    try {
      var currentIdentity =
          deploymentIdentity
              .current()
              .orElseThrow(
                  () ->
                      new FederationPeerOperationException(
                          FederationPeerOperationDisposition.NOT_SENT,
                          FederationPeerOperationFailure.TRUST_DENIED));
      if (!currentIdentity.deploymentId().equals(request.sourceDeployment())) {
        throw denied();
      }
      PartnerProfileVersion profile =
          profiles
              .findActivePeer(
                  request.partnerKey(), request.peerDeployment(), operation, request.scope())
              .orElseThrow(FederationPeerOperationService::denied);
      FederationPeerConfiguration peer =
          profile
              .configuration()
              .federationPeer()
              .orElseThrow(FederationPeerOperationService::denied);
      if (profile.version() != request.expectedConfigurationVersion()
          || profile.state() != PartnerProfileState.ACTIVE
          || profile.configuration().retryClass() != RetryClass.NEVER
          || !profile.configuration().allowedOperations().contains(operation)
          || !peer.deploymentId().equals(request.peerDeployment())
          || !peer.protocolProfile().equals(operation.profile())
          || !peer.permits(profile.configuration().organization(), request.scope())) {
        throw denied();
      }
      return profile;
    } catch (DataAccessException unavailable) {
      throw new FederationPeerOperationException(
          FederationPeerOperationDisposition.NOT_SENT,
          FederationPeerOperationFailure.TRUST_UNAVAILABLE);
    }
  }

  private static FederationPeerOperationException classify(PartnerTransportException failure) {
    return switch (failure.failure()) {
      case PROFILE_NOT_ACTIVE -> denied();
      case TRUST_UNAVAILABLE ->
          new FederationPeerOperationException(
              FederationPeerOperationDisposition.NOT_SENT,
              FederationPeerOperationFailure.TRUST_UNAVAILABLE);
      case AUTHENTICATION_NOT_SUPPORTED, AUTHENTICATION_FAILURE ->
          new FederationPeerOperationException(
              FederationPeerOperationDisposition.NOT_SENT,
              FederationPeerOperationFailure.AUTHENTICATION_FAILED);
      case DNS_FAILURE,
          DNS_TIMEOUT,
          DNS_CAPACITY_EXCEEDED,
          DESTINATION_REJECTED,
          TLS_FAILURE,
          CONNECT_TIMEOUT ->
          new FederationPeerOperationException(
              FederationPeerOperationDisposition.NOT_SENT,
              FederationPeerOperationFailure.DESTINATION_NOT_REACHED);
      case REMOTE_AUTHENTICATION_FAILURE, REMOTE_AUTHORIZATION_FAILURE, RATE_LIMITED, REMOTE_4XX ->
          new FederationPeerOperationException(
              FederationPeerOperationDisposition.REMOTE_REJECTED,
              FederationPeerOperationFailure.REMOTE_REJECTED);
      case REMOTE_NOT_FOUND ->
          new FederationPeerOperationException(
              FederationPeerOperationDisposition.REMOTE_REJECTED,
              FederationPeerOperationFailure.REMOTE_NOT_FOUND);
      case READ_TIMEOUT,
          OPERATION_TIMEOUT,
          RESPONSE_TOO_LARGE,
          UNSUPPORTED_CONTENT_ENCODING,
          INVALID_RESPONSE,
          PEER_MISMATCH,
          REMOTE_5XX,
          CONNECTION_FAILURE ->
          new FederationPeerOperationException(
              FederationPeerOperationDisposition.OUTCOME_UNKNOWN,
              FederationPeerOperationFailure.RESPONSE_UNCERTAIN);
    };
  }

  private static FederationPeerOperationException denied() {
    return new FederationPeerOperationException(
        FederationPeerOperationDisposition.NOT_SENT, FederationPeerOperationFailure.TRUST_DENIED);
  }
}
