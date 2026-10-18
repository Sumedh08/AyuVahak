package org.ayuvahak.platform.exchange.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Authentication;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AuthenticationMethod;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Limits;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.OwnerContext;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Protocol;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.RetryClass;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.TlsPolicy;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.junit.jupiter.api.Test;

class PartnerTrustConfigurationTest {

  @Test
  void canonicalizesPartnerOriginAndRejectsUnsafeEndpointForms() {
    assertThat(
            PartnerTrustConfiguration.normalizeHttpsOrigin(
                "HTTPS://EXAMPLE.test:443", "partner endpoint origin"))
        .isEqualTo("https://example.test");

    assertThatThrownBy(
            () ->
                PartnerTrustConfiguration.normalizeHttpsOrigin(
                    "http://example.test", "partner endpoint origin"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PartnerTrustConfiguration.normalizeHttpsOrigin(
                    "https://user:secret@example.test", "partner endpoint origin"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PartnerTrustConfiguration.normalizeHttpsOrigin(
                    "https://example.test/unapproved", "partner endpoint origin"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void operationCodesAreOpaqueToThePlatformTrustBoundary() {
    AllowedOperation futureOwnerOperation =
        new AllowedOperation(
            OwnerContext.DIRECTORY,
            "directory.future.read",
            Direction.OUTBOUND,
            Protocol.FHIR_R4_JSON,
            "DirectoryProfile-1");

    assertThat(futureOwnerOperation.operationCode()).isEqualTo("directory.future.read");
  }

  @Test
  void oauthAndTlsReferencesAreValidatedWithoutAcceptingCredentialMaterial() {
    assertThatThrownBy(
            () ->
                new Authentication(
                    AuthenticationMethod.OAUTH_PRIVATE_KEY_JWT,
                    Optional.empty(),
                    Optional.of("https://auth.example.test"),
                    Optional.of("/oauth/token"),
                    Optional.of("https://auth.example.test/issuer"),
                    Optional.of("ayuvahak"),
                    Optional.of("synthetic-client")))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new Authentication(
                    AuthenticationMethod.MUTUAL_TLS,
                    Optional.of("-----BEGIN PRIVATE KEY-----"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void oauthProfileRequiresASeparateReferenceForTrustedTokenSigningKeys() {
    assertThatThrownBy(
            () ->
                new Authentication(
                    AuthenticationMethod.OAUTH_PRIVATE_KEY_JWT,
                    Optional.of("env:SYN_CLIENT_KEY"),
                    Optional.of("https://auth.example.test"),
                    Optional.of("/oauth/token"),
                    Optional.of("https://auth.example.test/issuer"),
                    Optional.of("ayuvahak"),
                    Optional.of("synthetic-client")))
        .isInstanceOf(IllegalArgumentException.class);

    Authentication configured =
        new Authentication(
            AuthenticationMethod.OAUTH_PRIVATE_KEY_JWT,
            Optional.of("env:SYN_CLIENT_KEY"),
            Optional.of("env:SYN_TOKEN_JWKS"),
            Optional.of("https://auth.example.test"),
            Optional.of("/oauth/token"),
            Optional.of("https://auth.example.test/issuer"),
            Optional.of("ayuvahak"),
            Optional.of("synthetic-client"));
    assertThat(configured.tokenSigningKeysReference()).contains("env:SYN_TOKEN_JWKS");
    assertThat(configured.toString()).doesNotContain("SYN_CLIENT_KEY", "SYN_TOKEN_JWKS");
  }

  @Test
  void inboundProfilesCannotDeclareAutomaticRetry() {
    assertThatThrownBy(
            () ->
                profile(
                    new AllowedOperation(
                        OwnerContext.DIRECTORY,
                        "mcsd.iti130.feed",
                        Direction.INBOUND,
                        Protocol.FHIR_R4_JSON,
                        "IHE-mCSD"),
                    RetryClass.IDEMPOTENT_READ,
                    3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void inboundProfilesRequireOneExplicitServicePrincipal() {
    AllowedOperation inbound =
        new AllowedOperation(
            OwnerContext.DIRECTORY,
            "mcsd.iti130.feed",
            Direction.INBOUND,
            Protocol.FHIR_R4_JSON,
            "IHE-mCSD");

    assertThatThrownBy(() -> profile(inbound, RetryClass.NEVER, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("service principal");

    PartnerTrustConfiguration configured =
        new PartnerTrustConfiguration(
            new PartnerKey("synthetic-directory"),
            new ExternalReference(
                new IssuerId("urn:synthetic:directory"), "Organization", "SYN-ORG-001"),
            "https://directory.example.test",
            "/fhir",
            Authentication.none(),
            TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            new Limits(1_000, 2_000, 5_000, 1_000_000, 2_000_000, 10, 500, 32, 1),
            RetryClass.NEVER,
            List.of(inbound),
            Optional.of(
                ActorReference.service(
                    new IssuerId("https://identity.example.test/realm"), "synthetic-partner")));

    assertThat(configured.inboundPrincipal())
        .contains(
            ActorReference.service(
                new IssuerId("https://identity.example.test/realm"), "synthetic-partner"));
  }

  @Test
  void transportProviderOperationsUseJsonAndNeverExchangeAutomaticRetries() {
    AllowedOperation inbound = AllowedOperation.transportProviderEvidence();
    assertThat(inbound.ownerContext()).isEqualTo(OwnerContext.TRANSPORT);
    assertThat(inbound.direction()).isEqualTo(Direction.INBOUND);
    assertThat(inbound.protocol()).isEqualTo(Protocol.JSON);

    assertThatThrownBy(() -> profile(inbound, RetryClass.IDEMPOTENT_READ, 2))
        .isInstanceOf(IllegalArgumentException.class);

    AllowedOperation outbound =
        new AllowedOperation(
            OwnerContext.TRANSPORT,
            "transport.dispatch",
            Direction.OUTBOUND,
            Protocol.JSON,
            "AyuVahakTransportProvider-v1");
    assertThatThrownBy(() -> profile(outbound, RetryClass.IDEMPOTENT_READ, 2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be automatically retried");
  }

  @Test
  void analyticsTrustAllowsOnlyNamedSynchronousDataValueSetWithoutRetries() {
    AllowedOperation operation = AllowedOperation.analyticsDhis2DataValueSet();
    assertThat(operation.isNamedAnalyticsDhis2DataValueSet()).isTrue();
    assertThat(profile(operation, RetryClass.NEVER, 1).allowedOperations())
        .containsExactly(operation);

    assertThatThrownBy(() -> profile(operation, RetryClass.NEVER, 2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be automatically retried");
    assertThatThrownBy(
            () ->
                new AllowedOperation(
                    OwnerContext.ANALYTICS,
                    "analytics.dhis2.metadata-create",
                    Direction.OUTBOUND,
                    Protocol.JSON,
                    "DHIS2-DataValueSet-2.43"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limited to named aggregate operations");
  }

  @Test
  void federationPeerProfileUsesAuthenticatedScopedJsonOperations() {
    ExternalReference organization =
        new ExternalReference(
            new IssuerId("urn:synthetic:authority"), "Organization", "SYN-ORG-001");
    ExternalReference facility =
        new ExternalReference(
            new IssuerId("urn:synthetic:authority"), "Facility", "SYN-FACILITY-A");
    FederationPeerConfiguration peer =
        new FederationPeerConfiguration(
            new DeploymentId("SYN-DEPLOYMENT-B"),
            organization,
            FederationPeerConfiguration.supportedProtocolProfile(),
            List.of(facility));
    AllowedOperation operation =
        new AllowedOperation(
            OwnerContext.TRANSFER,
            "transfer.remote-receiving-request",
            Direction.OUTBOUND,
            Protocol.JSON,
            FederationPeerConfiguration.supportedProtocolProfile());

    PartnerTrustConfiguration profile =
        new PartnerTrustConfiguration(
            new PartnerKey("synthetic-peer-b"),
            organization,
            "https://peer.example.test",
            "/api/federation",
            new Authentication(
                AuthenticationMethod.MUTUAL_TLS,
                Optional.of("secret-ref:SYN-PEER-CERT"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            new Limits(1_000, 2_000, 5_000, 1_000_000, 2_000_000, 10, 500, 32, 1),
            RetryClass.NEVER,
            List.of(operation),
            Optional.empty(),
            Optional.of(peer));

    assertThat(profile.federationPeer()).contains(peer);
    assertThat(peer.permits(organization, organization)).isTrue();
    assertThat(peer.permits(organization, facility)).isTrue();
    assertThat(
            peer.permits(
                organization,
                new ExternalReference(
                    new IssuerId("urn:synthetic:authority"), "Facility", "SYN-FACILITY-B")))
        .isFalse();

    AllowedOperation aggregateOperation =
        AllowedOperation.analyticsAggregateSnapshot(Direction.OUTBOUND);
    PartnerTrustConfiguration aggregatePeerProfile =
        new PartnerTrustConfiguration(
            new PartnerKey("synthetic-analytics-peer"),
            organization,
            "https://peer.example.test",
            "/api/federation",
            new Authentication(
                AuthenticationMethod.MUTUAL_TLS,
                Optional.of("secret-ref:SYN-PEER-CERT"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            new Limits(1_000, 2_000, 5_000, 100_000, 200_000, 2, 20, 8, 1),
            RetryClass.NEVER,
            List.of(aggregateOperation),
            Optional.empty(),
            Optional.of(peer));
    assertThat(aggregatePeerProfile.allowedOperations()).containsExactly(aggregateOperation);

    assertThatThrownBy(
            () ->
                new PartnerTrustConfiguration(
                    new PartnerKey("synthetic-analytics-peer"),
                    organization,
                    "https://peer.example.test",
                    "/api/federation",
                    new Authentication(
                        AuthenticationMethod.MUTUAL_TLS,
                        Optional.of("secret-ref:SYN-PEER-CERT"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                    TlsPolicy.SYSTEM_CA,
                    Optional.empty(),
                    new Limits(1_000, 2_000, 5_000, 100_000, 200_000, 2, 20, 8, 1),
                    RetryClass.NEVER,
                    List.of(AllowedOperation.analyticsDhis2DataValueSet()),
                    Optional.empty(),
                    Optional.of(peer)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("federation peer profiles allow only scoped JSON owner operations");
  }

  @Test
  void federationProfilesRejectUnauthenticatedOrPatientContextOperations() {
    ExternalReference organization =
        new ExternalReference(
            new IssuerId("urn:synthetic:authority"), "Organization", "SYN-ORG-001");
    FederationPeerConfiguration peer =
        new FederationPeerConfiguration(
            new DeploymentId("SYN-DEPLOYMENT-B"),
            organization,
            FederationPeerConfiguration.supportedProtocolProfile(),
            List.of());
    AllowedOperation operation =
        new AllowedOperation(
            OwnerContext.TRANSFER,
            "transfer.remote-receiving-request",
            Direction.OUTBOUND,
            Protocol.JSON,
            FederationPeerConfiguration.supportedProtocolProfile());

    assertThatThrownBy(
            () -> federationProfile(organization, Authentication.none(), List.of(operation), peer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authenticated");

    AllowedOperation referral =
        new AllowedOperation(
            OwnerContext.REFERRAL,
            "referral.remote-context",
            Direction.OUTBOUND,
            Protocol.FHIR_R4_JSON,
            "IHE-IPS");
    assertThatThrownBy(
            () -> federationProfile(organization, peerAuthentication(), List.of(referral), peer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owner operations");
  }

  @Test
  void federationProfileMustBindToTheConfiguredPartnerOrganization() {
    ExternalReference partnerOrganization =
        new ExternalReference(
            new IssuerId("urn:synthetic:authority"), "Organization", "SYN-ORG-001");
    ExternalReference otherOrganization =
        new ExternalReference(
            new IssuerId("urn:synthetic:authority"), "Organization", "SYN-ORG-002");
    FederationPeerConfiguration peer =
        new FederationPeerConfiguration(
            new DeploymentId("SYN-DEPLOYMENT-B"),
            otherOrganization,
            FederationPeerConfiguration.supportedProtocolProfile(),
            List.of());
    AllowedOperation operation =
        new AllowedOperation(
            OwnerContext.DIRECTORY,
            "directory.remote-query",
            Direction.OUTBOUND,
            Protocol.JSON,
            FederationPeerConfiguration.supportedProtocolProfile());

    assertThatThrownBy(
            () ->
                federationProfile(
                    partnerOrganization, peerAuthentication(), List.of(operation), peer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("partner organization");
  }

  private static PartnerTrustConfiguration federationProfile(
      ExternalReference organization,
      Authentication authentication,
      List<AllowedOperation> operations,
      FederationPeerConfiguration peer) {
    return new PartnerTrustConfiguration(
        new PartnerKey("synthetic-peer-b"),
        organization,
        "https://peer.example.test",
        "/api/federation",
        authentication,
        TlsPolicy.SYSTEM_CA,
        Optional.empty(),
        new Limits(1_000, 2_000, 5_000, 1_000_000, 2_000_000, 10, 500, 32, 1),
        RetryClass.NEVER,
        operations,
        Optional.empty(),
        Optional.of(peer));
  }

  private static Authentication peerAuthentication() {
    return new Authentication(
        AuthenticationMethod.MUTUAL_TLS,
        Optional.of("secret-ref:SYN-PEER-CERT"),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private static PartnerTrustConfiguration profile(
      AllowedOperation operation, RetryClass retryClass, int maxAttempts) {
    return new PartnerTrustConfiguration(
        new PartnerKey("synthetic-directory"),
        new ExternalReference(
            new IssuerId("urn:synthetic:directory"), "Organization", "SYN-ORG-001"),
        "https://directory.example.test",
        "/fhir",
        Authentication.none(),
        TlsPolicy.SYSTEM_CA,
        Optional.empty(),
        new Limits(1_000, 2_000, 5_000, 1_000_000, 2_000_000, 10, 500, 32, maxAttempts),
        retryClass,
        List.of(operation));
  }
}
