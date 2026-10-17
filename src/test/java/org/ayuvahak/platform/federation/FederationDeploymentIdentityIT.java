package org.ayuvahak.platform.federation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.AyuVahakApplication;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.adapter.persistence.JdbcPartnerTrustProfileStore;
import org.ayuvahak.platform.exchange.application.PartnerTrustProfileStore;
import org.ayuvahak.platform.exchange.domain.FederationPeerConfiguration;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Authentication;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AuthenticationMethod;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Limits;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.OwnerContext;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Protocol;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.RetryClass;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.TlsPolicy;
import org.ayuvahak.platform.federation.application.DeploymentIdentityConflictException;
import org.ayuvahak.platform.federation.application.DeploymentIdentityRegistrationService;
import org.ayuvahak.platform.federation.application.DeploymentIdentityStore;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.platform.federation.domain.DeploymentIdentity;
import org.ayuvahak.platform.idempotency.configuration.CommandIdempotencyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    classes = AyuVahakApplication.class,
    properties = {
      "ayuvahak.authentication.issuer=https://identity.example.test/realms/test",
      "ayuvahak.authentication.jwk-set-uri=http://127.0.0.1:9/jwks",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-worker",
      "ayuvahak.authentication.allow-http=true",
      "ayuvahak.eventing.outbox.dispatcher-enabled=false"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FederationDeploymentIdentityIT {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword("local-synthetic-only");

  @Container private static final PostgreSQLContainer CONTAINER = POSTGRES;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private DeploymentIdentityRegistrationService registration;
  @Autowired private PartnerTrustProfileStore partnerProfiles;

  @DynamicPropertySource
  static void localDatabase(DynamicPropertyRegistry registry) {
    registry.add("AYUVAHAK_DATABASE_URL", POSTGRES::getJdbcUrl);
    registry.add("AYUVAHAK_DATABASE_USERNAME", POSTGRES::getUsername);
    registry.add("AYUVAHAK_DATABASE_PASSWORD", POSTGRES::getPassword);
  }

  @BeforeEach
  void clearLocalSyntheticIdentity() {
    jdbc.update("DELETE FROM platform_exchange.partner_profile_peer_facility_scope");
    jdbc.update("DELETE FROM platform_exchange.partner_profile_operation");
    jdbc.update("DELETE FROM platform_exchange.partner_profile_version");
    jdbc.update("DELETE FROM platform_exchange.partner_profile");
    jdbc.update("DELETE FROM platform_federation.deployment_identity_conflict");
    jdbc.update("DELETE FROM platform_federation.deployment_identity");
  }

  @Test
  void registrationIsStableAcrossRepeatedStartsAndStoresOnlyBindingDigest() {
    Instant firstAt = Instant.parse("2026-10-17T10:00:00Z");
    Instant secondAt = Instant.parse("2026-10-17T10:01:00Z");
    DeploymentId configuredId = new DeploymentId("SYN-DEPLOYMENT-A");
    String syntheticBinding = "SYN-INSTALLATION-BINDING-ONLY-LOCAL-0001";

    DeploymentIdentity first = registration.register(configuredId, syntheticBinding, firstAt);
    DeploymentIdentity second = registration.register(configuredId, syntheticBinding, secondAt);

    assertThat(first.deploymentId()).isEqualTo(configuredId);
    assertThat(second.deploymentId()).isEqualTo(configuredId);
    assertThat(second.createdAt()).isEqualTo(firstAt);
    assertThat(second.version()).isEqualTo(first.version());
    assertThat(
            jdbc.queryForObject(
                "SELECT installation_binding_digest FROM platform_federation.deployment_identity WHERE singleton_id = 1",
                String.class))
        .doesNotContain(syntheticBinding)
        .matches("[0-9a-f]{64}");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_federation.deployment_identity", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void mismatchedRestoredInstallationFailsClosedAndRecordsSanitizedConflictEvidence() {
    registration.register(
        new DeploymentId("SYN-DEPLOYMENT-A"),
        "SYN-INSTALLATION-BINDING-ONLY-LOCAL-0001",
        Instant.parse("2026-10-17T10:00:00Z"));

    assertThatThrownBy(
            () ->
                registration.register(
                    new DeploymentId("SYN-DEPLOYMENT-A"),
                    "SYN-INSTALLATION-BINDING-ONLY-LOCAL-0002",
                    Instant.parse("2026-10-17T10:01:00Z")))
        .isInstanceOf(DeploymentIdentityConflictException.class);

    assertThat(
            jdbc.queryForObject(
                "SELECT conflict_code FROM platform_federation.deployment_identity_conflict",
                String.class))
        .isEqualTo("INSTALLATION_BINDING_MISMATCH");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_federation.deployment_identity_conflict "
                    + "WHERE conflict_code LIKE '%SYN-INSTALLATION-BINDING%'",
                Integer.class))
        .isZero();
  }

  @Test
  void aDifferentConfiguredDeploymentIdCannotReuseTheDatabaseIdentity() {
    registration.register(
        new DeploymentId("SYN-DEPLOYMENT-A"),
        "SYN-INSTALLATION-BINDING-ONLY-LOCAL-0001",
        Instant.parse("2026-10-17T10:00:00Z"));

    assertThatThrownBy(
            () ->
                registration.register(
                    new DeploymentId("SYN-DEPLOYMENT-B"),
                    "SYN-INSTALLATION-BINDING-ONLY-LOCAL-0001",
                    Instant.parse("2026-10-17T10:01:00Z")))
        .isInstanceOf(DeploymentIdentityConflictException.class);

    assertThat(
            jdbc.queryForObject(
                "SELECT deployment_id FROM platform_federation.deployment_identity WHERE singleton_id = 1",
                String.class))
        .isEqualTo("SYN-DEPLOYMENT-A");
    assertThat(
            jdbc.queryForObject(
                "SELECT conflict_code FROM platform_federation.deployment_identity_conflict",
                String.class))
        .isEqualTo("DEPLOYMENT_ID_MISMATCH");
  }

  @Test
  void peerTrustIsScopedToConfiguredOperationDirectionAndFacilityAndRevocationIsImmediate() {
    Instant now = Instant.parse("2026-10-17T10:00:00Z");
    registration.register(
        new DeploymentId("SYN-DEPLOYMENT-A"), "SYN-INSTALLATION-BINDING-ONLY-LOCAL-0001", now);
    PartnerTrustConfiguration configuration = syntheticPeerConfiguration();
    PartnerKey partnerKey = configuration.partnerKey();
    DeploymentId peerId = new DeploymentId("SYN-DEPLOYMENT-B");
    ExternalReference facility =
        new ExternalReference(
            new IssuerId("urn:synthetic:directory"), "Facility", "SYN-FACILITY-B");
    AllowedOperation allowed = configuration.allowedOperations().getFirst();

    partnerProfiles.createInitial(configuration, now);
    partnerProfiles.activate(partnerKey, 1, now.plusSeconds(1));

    assertThat(partnerProfiles.findActivePeer(partnerKey, peerId, allowed, facility)).isPresent();
    DeploymentIdentity persistedIdentity = registration.current().orElseThrow();
    DeploymentIdentityRegistrationService unregisteredRuntime =
        new DeploymentIdentityRegistrationService(new PersistedIdentityStore(persistedIdentity));
    PartnerTrustProfileStore unregisteredRuntimeProfiles =
        new JdbcPartnerTrustProfileStore(
            jdbc, new CommandIdempotencyProperties(), unregisteredRuntime);
    assertThat(unregisteredRuntime.current()).isEmpty();
    assertThat(unregisteredRuntimeProfiles.findActivePeer(partnerKey, peerId, allowed, facility))
        .isEmpty();
    assertThat(
            partnerProfiles.findActivePeer(
                partnerKey,
                peerId,
                allowed,
                new ExternalReference(
                    new IssuerId("urn:synthetic:directory"), "Facility", "SYN-FACILITY-OTHER")))
        .isEmpty();
    assertThat(
            partnerProfiles.findActivePeer(
                partnerKey,
                peerId,
                new AllowedOperation(
                    OwnerContext.TRANSFER,
                    "transfer.remote-status",
                    Direction.OUTBOUND,
                    Protocol.JSON,
                    FederationPeerConfiguration.supportedProtocolProfile()),
                facility))
        .isEmpty();
    assertThat(
            partnerProfiles.findActivePeer(
                partnerKey,
                peerId,
                new AllowedOperation(
                    OwnerContext.TRANSFER,
                    allowed.operationCode(),
                    Direction.INBOUND,
                    Protocol.JSON,
                    FederationPeerConfiguration.supportedProtocolProfile()),
                facility))
        .isEmpty();

    partnerProfiles.revoke(partnerKey, now.plusSeconds(2));
    assertThat(partnerProfiles.findActivePeer(partnerKey, peerId, allowed, facility)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT v.peer_deployment_id FROM platform_exchange.partner_profile_version v "
                    + "JOIN platform_exchange.partner_profile p ON p.partner_id = v.partner_id "
                    + "WHERE p.partner_key = ?",
                String.class,
                partnerKey.value()))
        .isEqualTo("SYN-DEPLOYMENT-B");
  }

  private static PartnerTrustConfiguration syntheticPeerConfiguration() {
    IssuerId namespace = new IssuerId("urn:synthetic:directory");
    ExternalReference organization =
        new ExternalReference(namespace, "Organization", "SYN-ORG-001");
    FederationPeerConfiguration peer =
        new FederationPeerConfiguration(
            new DeploymentId("SYN-DEPLOYMENT-B"),
            organization,
            FederationPeerConfiguration.supportedProtocolProfile(),
            List.of(new ExternalReference(namespace, "Facility", "SYN-FACILITY-B")));
    return new PartnerTrustConfiguration(
        new PartnerKey("synthetic-federation-peer"),
        organization,
        "https://peer.example.test",
        "/federation",
        new Authentication(
            AuthenticationMethod.OAUTH_CLIENT_CREDENTIALS,
            Optional.of("secret://synthetic/federation-peer"),
            Optional.of("env:SYNTHETIC_PEER_KEYS"),
            Optional.of("https://auth.example.test"),
            Optional.of("/oauth/token"),
            Optional.of("https://auth.example.test/issuer"),
            Optional.of("ayuvahak-federation"),
            Optional.of("synthetic-federation-client")),
        TlsPolicy.SYSTEM_CA,
        Optional.empty(),
        new Limits(1000, 2000, 5000, 100_000, 200_000, 1, 50, 16, 1),
        RetryClass.NEVER,
        List.of(
            new AllowedOperation(
                OwnerContext.TRANSFER,
                "transfer.remote-request",
                Direction.OUTBOUND,
                Protocol.JSON,
                FederationPeerConfiguration.supportedProtocolProfile())),
        Optional.empty(),
        Optional.of(peer));
  }

  private record PersistedIdentityStore(DeploymentIdentity persisted)
      implements DeploymentIdentityStore {

    @Override
    public DeploymentIdentity registerOrVerify(
        DeploymentId deploymentId, String installationBindingDigest, Instant now) {
      throw new UnsupportedOperationException("test store is read-only");
    }

    @Override
    public Optional<DeploymentIdentity> findCurrent() {
      return Optional.of(persisted);
    }
  }
}
