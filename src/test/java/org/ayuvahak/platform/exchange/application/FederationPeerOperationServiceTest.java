package org.ayuvahak.platform.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.domain.FederationPeerConfiguration;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerProfileState;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.OwnerContext;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Protocol;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.RetryClass;
import org.ayuvahak.platform.federation.application.DeploymentIdentityRegistrationService;
import org.ayuvahak.platform.federation.application.DeploymentIdentityStore;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.platform.federation.domain.DeploymentIdentity;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.junit.jupiter.api.Test;

class FederationPeerOperationServiceTest {

  private static final PartnerKey PARTNER = new PartnerKey("SYNTHETIC-PEER-B");
  private static final DeploymentId SOURCE = new DeploymentId("SYN-DEPLOYMENT-A");
  private static final DeploymentId PEER = new DeploymentId("SYN-DEPLOYMENT-B");
  private static final ExternalReference ORGANIZATION =
      new ExternalReference(new IssuerId("urn:synthetic:authority"), "Organization", "SYN-ORG-001");
  private static final ExternalReference FACILITY =
      new ExternalReference(new IssuerId("urn:synthetic:authority"), "Facility", "SYN-FACILITY-B");
  private static final ExternalReference OTHER_FACILITY =
      new ExternalReference(
          new IssuerId("urn:synthetic:authority"), "Facility", "SYN-FACILITY-OTHER");
  private static final AllowedOperation OUTBOUND = AllowedOperation.transferMovementFact();
  private static final AllowedOperation INBOUND =
      AllowedOperation.transferMovementFact(Direction.INBOUND);
  private static final ActorReference PEER_PRINCIPAL =
      ActorReference.service(new IssuerId("urn:synthetic:peer-auth"), "SYN-PEER-SERVICE");
  private static final byte[] BODY = "{\"movement\":\"ARRIVED\"}".getBytes();

  @Test
  void sendsOnlyTheCurrentConfiguredPeerOperationAndReturnsTransientTechnicalResponse() {
    PartnerProfileVersion profile = profile(1);
    InMemoryProfileStore profiles = new InMemoryProfileStore(profile);
    AtomicReference<PartnerOutboundOperationResponse> seenResponse = new AtomicReference<>();
    AtomicInteger invalidations = new AtomicInteger();
    PartnerAuthorizationPort authorization =
        new PartnerAuthorizationPort() {
          @Override
          public Optional<PartnerBearerToken> bearerToken(PartnerProfileVersion selected) {
            assertThat(selected).isSameAs(profile);
            return Optional.empty();
          }

          @Override
          public Optional<PartnerBearerToken> bearerToken(
              PartnerProfileVersion selected, Runnable beforeAttempt) {
            beforeAttempt.run();
            return Optional.empty();
          }

          @Override
          public void invalidate(PartnerKey partnerKey) {
            assertThat(partnerKey).isEqualTo(PARTNER);
            invalidations.incrementAndGet();
          }
        };
    FederationPeerOperationService service =
        new FederationPeerOperationService(
            profiles,
            identity(SOURCE),
            authorization,
            (selected, request, token, beforeAttempt) -> {
              assertThat(selected).isSameAs(profile);
              assertThat(request.operation()).isEqualTo(OUTBOUND);
              assertThat(request.peerDeployment()).isEqualTo(PEER);
              assertThat(request.fingerprint()).isEqualTo(fingerprint());
              assertThat(token).isEmpty();
              beforeAttempt.run();
              PartnerOutboundOperationResponse response =
                  new PartnerOutboundOperationResponse(
                      202, Optional.empty(), new byte[0], selected.version());
              seenResponse.set(response);
              return response;
            });

    PartnerOutboundOperationResponse response = service.execute(request());

    assertThat(seenResponse.get()).isSameAs(response);
    assertThat(response.status()).isEqualTo(202);
    assertThat(invalidations).hasValue(0);
  }

  @Test
  void localIdentityMismatchAndChangedOrRevokedPeerProfileFailBeforeTransport() {
    PartnerProfileVersion original = profile(1);
    PartnerProfileVersion changed = profile(2);
    InMemoryProfileStore store = new InMemoryProfileStore(original);
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger invalidations = new AtomicInteger();
    PartnerAuthorizationPort authorization =
        new PartnerAuthorizationPort() {
          @Override
          public Optional<PartnerBearerToken> bearerToken(PartnerProfileVersion activeProfile) {
            return Optional.empty();
          }

          @Override
          public void invalidate(PartnerKey partnerKey) {
            invalidations.incrementAndGet();
          }
        };
    FederationPeerOperationService changedDuringToken =
        new FederationPeerOperationService(
            store,
            identity(SOURCE),
            new PartnerAuthorizationPort() {
              @Override
              public Optional<PartnerBearerToken> bearerToken(PartnerProfileVersion profile) {
                return Optional.empty();
              }

              @Override
              public Optional<PartnerBearerToken> bearerToken(
                  PartnerProfileVersion profile, Runnable beforeAttempt) {
                store.active.set(Optional.of(changed));
                beforeAttempt.run();
                return Optional.empty();
              }

              @Override
              public void invalidate(PartnerKey partnerKey) {
                invalidations.incrementAndGet();
              }
            },
            (profile, request, token, beforeAttempt) -> {
              attempts.incrementAndGet();
              return new PartnerOutboundOperationResponse(
                  202, Optional.empty(), new byte[0], profile.version());
            });

    assertThatThrownBy(() -> changedDuringToken.execute(request()))
        .isInstanceOfSatisfying(
            FederationPeerOperationException.class,
            failure -> {
              assertThat(failure.disposition())
                  .isEqualTo(FederationPeerOperationDisposition.NOT_SENT);
              assertThat(failure.failure()).isEqualTo(FederationPeerOperationFailure.TRUST_DENIED);
            });
    assertThat(attempts).hasValue(0);
    assertThat(invalidations).hasValue(1);

    FederationPeerOperationService wrongLocalIdentity =
        new FederationPeerOperationService(
            new InMemoryProfileStore(original),
            identity(new DeploymentId("SYN-DEPLOYMENT-WRONG")),
            authorization,
            (profile, request, token, beforeAttempt) -> {
              attempts.incrementAndGet();
              return new PartnerOutboundOperationResponse(
                  202, Optional.empty(), new byte[0], profile.version());
            });
    assertThatThrownBy(() -> wrongLocalIdentity.execute(request()))
        .isInstanceOf(FederationPeerOperationException.class);
    assertThat(attempts).hasValue(0);

    FederationPeerOperationService wrongConfiguredPeer =
        new FederationPeerOperationService(
            new InMemoryProfileStore(profileForPeer(1, new DeploymentId("SYN-DEPLOYMENT-C"))),
            identity(SOURCE),
            authorizationWithoutToken(),
            (profile, request, token, beforeAttempt) -> {
              attempts.incrementAndGet();
              return new PartnerOutboundOperationResponse(
                  202, Optional.empty(), new byte[0], profile.version());
            });
    assertThatThrownBy(() -> wrongConfiguredPeer.execute(request()))
        .isInstanceOf(FederationPeerOperationException.class);
    assertThat(attempts).hasValue(0);
  }

  @Test
  void classifiesRemoteRejectionAndPossibleLostResponseWithoutAutomaticRetry() {
    PartnerProfileVersion profile = profile(1);
    AtomicInteger attempts = new AtomicInteger();
    FederationPeerOperationService service =
        new FederationPeerOperationService(
            new InMemoryProfileStore(profile),
            identity(SOURCE),
            authorizationWithoutToken(),
            (selected, request, token, beforeAttempt) -> {
              attempts.incrementAndGet();
              throw new PartnerTransportException(PartnerTransportFailure.READ_TIMEOUT);
            });
    assertThatThrownBy(() -> service.execute(request()))
        .isInstanceOfSatisfying(
            FederationPeerOperationException.class,
            failure -> {
              assertThat(failure.disposition())
                  .isEqualTo(FederationPeerOperationDisposition.OUTCOME_UNKNOWN);
              assertThat(failure.failure())
                  .isEqualTo(FederationPeerOperationFailure.RESPONSE_UNCERTAIN);
              assertThat(failure.toString()).doesNotContain("ARRIVED");
            });
    assertThat(attempts).hasValue(1);

    FederationPeerOperationService rejected =
        new FederationPeerOperationService(
            new InMemoryProfileStore(profile),
            identity(SOURCE),
            authorizationWithoutToken(),
            (selected, request, token, beforeAttempt) -> {
              throw new PartnerTransportException(PartnerTransportFailure.REMOTE_4XX);
            });
    assertThatThrownBy(() -> rejected.execute(request()))
        .isInstanceOfSatisfying(
            FederationPeerOperationException.class,
            failure ->
                assertThat(failure.disposition())
                    .isEqualTo(FederationPeerOperationDisposition.REMOTE_REJECTED));
  }

  @Test
  void inboundAuthorizationRequiresCurrentPeerOperationAndScopeAndReturnsNoPayload() {
    PartnerProfileVersion inboundProfile = profileWithOperation(INBOUND, 1);
    InMemoryProfileStore store = new InMemoryProfileStore(inboundProfile);
    FederationPeerInboundAuthorizationService service =
        new FederationPeerInboundAuthorizationService(
            store, identity(new DeploymentId("SYN-DEPLOYMENT-C")));

    FederationPeerInboundAuthorization authorization =
        service.authorize(PEER_PRINCIPAL, PEER, INBOUND, FACILITY);

    assertThat(authorization.operation()).isEqualTo(INBOUND);
    assertThat(authorization.peerDeployment()).isEqualTo(PEER);
    assertThat(authorization.profileVersion()).isEqualTo(1);
    assertThat(authorization.toString()).doesNotContain("SYN-FACILITY-B", "ARRIVED");
    assertThatThrownBy(() -> service.authorize(PEER_PRINCIPAL, PEER, INBOUND, OTHER_FACILITY))
        .isInstanceOf(FederationPeerInboundAuthorizationException.class);
    assertThatThrownBy(
            () ->
                service.authorize(
                    PEER_PRINCIPAL,
                    PEER,
                    new AllowedOperation(
                        OwnerContext.TRANSFER,
                        "transfer.remote-receiving-request",
                        Direction.INBOUND,
                        Protocol.JSON,
                        FederationPeerConfiguration.supportedProtocolProfile()),
                    FACILITY))
        .isInstanceOf(FederationPeerInboundAuthorizationException.class);

    store.active.set(Optional.empty());
    assertThatThrownBy(() -> service.authorize(PEER_PRINCIPAL, PEER, INBOUND, FACILITY))
        .isInstanceOf(FederationPeerInboundAuthorizationException.class);
  }

  private static FederationPeerOperationRequest request() {
    return new FederationPeerOperationRequest(
        PARTNER,
        SOURCE,
        PEER,
        FACILITY,
        OUTBOUND,
        InternalId.generate(),
        1,
        BODY,
        fingerprint(),
        Optional.empty());
  }

  private static FederationOperationFingerprint fingerprint() {
    return FederationOperationFingerprint.calculate(SOURCE, PEER, FACILITY, OUTBOUND, BODY);
  }

  private static PartnerAuthorizationPort authorizationWithoutToken() {
    return new PartnerAuthorizationPort() {
      @Override
      public Optional<PartnerBearerToken> bearerToken(PartnerProfileVersion profile) {
        return Optional.empty();
      }

      @Override
      public void invalidate(PartnerKey partnerKey) {}
    };
  }

  private static DeploymentIdentityRegistrationService identity(DeploymentId id) {
    Instant now = Instant.parse("2026-10-17T00:00:00Z");
    DeploymentIdentityStore store =
        new DeploymentIdentityStore() {
          private DeploymentIdentity identity;

          @Override
          public DeploymentIdentity registerOrVerify(
              DeploymentId deploymentId, String installationBindingDigest, Instant now) {
            identity = new DeploymentIdentity(deploymentId, 1, now, now);
            return identity;
          }

          @Override
          public Optional<DeploymentIdentity> findCurrent() {
            return Optional.ofNullable(identity);
          }
        };
    DeploymentIdentityRegistrationService registration =
        new DeploymentIdentityRegistrationService(store);
    registration.register(id, "SYNTHETIC-INSTALLATION-BINDING-0000001", now);
    return registration;
  }

  private static PartnerProfileVersion profile(int version) {
    return profileWithOperation(OUTBOUND, version);
  }

  private static PartnerProfileVersion profileForPeer(int version, DeploymentId peerDeployment) {
    return profileWithOperation(OUTBOUND, version, peerDeployment);
  }

  private static PartnerProfileVersion profileWithOperation(
      AllowedOperation operation, int version) {
    return profileWithOperation(operation, version, PEER);
  }

  private static PartnerProfileVersion profileWithOperation(
      AllowedOperation operation, int version, DeploymentId peerDeployment) {
    Instant now = Instant.parse("2026-10-17T00:00:00Z");
    PartnerTrustConfiguration configuration =
        new PartnerTrustConfiguration(
            PARTNER,
            ORGANIZATION,
            "https://peer.example.test",
            "/api/federation",
            new PartnerTrustConfiguration.Authentication(
                PartnerTrustConfiguration.AuthenticationMethod.OAUTH_CLIENT_CREDENTIALS,
                Optional.of("env:SYNTHETIC-CREDENTIAL"),
                Optional.of("env:SYNTHETIC-KEYS"),
                Optional.of("https://identity.example.test"),
                Optional.of("/oauth/token"),
                Optional.of("https://identity.example.test/issuer"),
                Optional.of("synthetic-audience"),
                Optional.of("synthetic-client")),
            PartnerTrustConfiguration.TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            new PartnerTrustConfiguration.Limits(
                1_000, 2_000, 5_000, 100_000, 200_000, 2, 20, 8, 1),
            RetryClass.NEVER,
            List.of(operation),
            operation.direction() == Direction.INBOUND
                ? Optional.of(PEER_PRINCIPAL)
                : Optional.empty(),
            Optional.of(
                new FederationPeerConfiguration(
                    peerDeployment,
                    ORGANIZATION,
                    FederationPeerConfiguration.supportedProtocolProfile(),
                    List.of(FACILITY))));
    return new PartnerProfileVersion(
        InternalId.generate(), version, PartnerProfileState.ACTIVE, configuration, now, now);
  }

  private static final class InMemoryProfileStore implements PartnerTrustProfileStore {

    private final AtomicReference<Optional<PartnerProfileVersion>> active;

    private InMemoryProfileStore(PartnerProfileVersion profile) {
      active = new AtomicReference<>(Optional.of(profile));
    }

    @Override
    public Optional<PartnerProfileVersion> findActivePeer(
        PartnerKey partnerKey,
        DeploymentId peerDeploymentId,
        AllowedOperation operation,
        ExternalReference requestedScope) {
      return active
          .get()
          .filter(profile -> profile.configuration().partnerKey().equals(partnerKey))
          .filter(profile -> profile.configuration().allowedOperations().contains(operation))
          .filter(
              profile ->
                  profile.configuration().federationPeer().stream()
                      .anyMatch(
                          peer ->
                              peer.deploymentId().equals(peerDeploymentId)
                                  && peer.permits(
                                      profile.configuration().organization(), requestedScope)));
    }

    @Override
    public Optional<PartnerProfileVersion> findActiveInboundPeer(
        ActorReference principal,
        DeploymentId peerDeploymentId,
        AllowedOperation operation,
        ExternalReference requestedScope) {
      return active
          .get()
          .filter(
              profile -> profile.configuration().inboundPrincipal().equals(Optional.of(principal)))
          .filter(profile -> profile.configuration().allowedOperations().contains(operation))
          .filter(
              profile ->
                  profile.configuration().federationPeer().stream()
                      .anyMatch(
                          peer ->
                              peer.deploymentId().equals(peerDeploymentId)
                                  && peer.permits(
                                      profile.configuration().organization(), requestedScope)));
    }

    @Override
    public Optional<PartnerProfileVersion> findActive(
        PartnerKey partnerKey, AllowedOperation operation) {
      throw unexpected();
    }

    @Override
    public Optional<PartnerProfileVersion> findActiveInbound(
        ActorReference principal, AllowedOperation operation) {
      throw unexpected();
    }

    @Override
    public void configureBoundedReceiptWait() {
      throw unexpected();
    }

    @Override
    public PartnerProfileVersion createInitial(
        PartnerTrustConfiguration configuration, Instant now) {
      throw unexpected();
    }

    @Override
    public PartnerProfileVersion createNextVersion(
        PartnerTrustConfiguration configuration, int expectedCurrentVersion, Instant now) {
      throw unexpected();
    }

    @Override
    public PartnerProfileVersion activate(PartnerKey partnerKey, int version, Instant now) {
      throw unexpected();
    }

    @Override
    public PartnerProfileVersion suspend(PartnerKey partnerKey, Instant now) {
      throw unexpected();
    }

    @Override
    public PartnerProfileVersion revoke(PartnerKey partnerKey, Instant now) {
      throw unexpected();
    }

    @Override
    public List<PartnerProfileVersion> versions(PartnerKey partnerKey) {
      throw unexpected();
    }

    @Override
    public Optional<PartnerProfileVersion> findById(InternalId versionId) {
      throw unexpected();
    }

    @Override
    public PartnerTrustProfileStore.ReceiptClaim claimReceipt(
        CommandReceiptKey key, RequestFingerprint fingerprint, Instant now) {
      throw unexpected();
    }

    @Override
    public void completeReceipt(
        CommandReceiptKey key, int responseStatus, InternalId resultVersion, Instant now) {
      throw unexpected();
    }

    private static AssertionError unexpected() {
      return new AssertionError("unexpected trust store operation");
    }
  }
}
