package org.ayuvahak.platform.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.uhn.fhir.context.FhirContext;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.ayuvahak.directory.application.DirectoryMcsdFeedEntry;
import org.ayuvahak.directory.application.DirectoryMcsdFeedImportPort;
import org.ayuvahak.directory.application.DirectoryMcsdFeedImportService;
import org.ayuvahak.directory.application.DirectoryMcsdFeedProjectionImportPort;
import org.ayuvahak.directory.application.DirectoryMcsdFeedReceiptStore;
import org.ayuvahak.directory.application.DirectoryMcsdSynchronizationResult;
import org.ayuvahak.directory.application.DirectoryMcsdSynchronizationService;
import org.ayuvahak.directory.application.DirectorySourceProjection;
import org.ayuvahak.directory.application.DirectorySourceProjectionImportPort;
import org.ayuvahak.directory.application.DirectorySourceProjectionReconciliation;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.authorization.adapter.api.PlatformAccessGrantController;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.application.BootstrapGrantProperties;
import org.ayuvahak.platform.authorization.application.BootstrapGrantService;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.adapter.http.LocalPartnerExchangeTestConfiguration;
import org.ayuvahak.platform.exchange.application.PartnerTrustProfileStore;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.referral.application.PatientContextCategory;
import org.ayuvahak.referral.application.PatientContextDisclosureDecision;
import org.ayuvahak.referral.application.PatientContextDisclosurePolicy;
import org.ayuvahak.referral.application.PatientContextResult;
import org.ayuvahak.referral.application.ReferralDraftStore;
import org.ayuvahak.referral.application.ReferralPatientContextQueries;
import org.ayuvahak.referral.application.ReferralPatientContextRequest;
import org.ayuvahak.referral.application.ReferralPatientContextSourcePort;
import org.ayuvahak.referral.application.ReferralTransferParticipation;
import org.ayuvahak.referral.application.TransferPatientContextParticipationPort;
import org.ayuvahak.referral.domain.CareRequirement;
import org.ayuvahak.referral.domain.PatientReference;
import org.ayuvahak.referral.domain.ReferralAuthorities;
import org.ayuvahak.referral.domain.ReferralDraft;
import org.ayuvahak.referral.domain.ReferralState;
import org.ayuvahak.referral.domain.SubmittedReferralRevision;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    properties = {
      "management.tracing.sampling.probability=0.0",
      "ayuvahak.test.partner.enabled=true",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-worker,synthetic-directory-feed,synthetic-directory-curator,synthetic-transport-provider",
      "ayuvahak.transport.provider-partner-key=SYNTHETIC-TRANSPORT-PROVIDER",
      "ayuvahak.authentication.allow-http=true"
    })
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@Import(LocalPartnerExchangeTestConfiguration.class)
class PartnerTrustAdministrationIT {

  private static final String ISSUER = "https://identity.example.test/realms/ayuvahak";
  private static final String AUDIENCE = "ayuvahak-api";
  private static final String ADMIN = "synthetic-platform-admin";
  private static final String PARTNER_ADMIN = "synthetic-exchange-admin";
  private static final String TEST_PASSWORD = "local-test-only";
  private static final String SECRET_REFERENCE = "secret://synthetic/partner-key";
  private static final String FEED_SOURCE_NAMESPACE = "https://directory.example.test/issuer";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String MCSD_ORGANIZATION_PROFILE =
      "https://profiles.ihe.net/ITI/mCSD/StructureDefinition/IHE.mCSD.Organization|4.0.0";
  private static final String IPS_BUNDLE_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Bundle-uv-ips|2.0.1";
  private static final String IPS_PATIENT_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Patient-uv-ips|2.0.1";
  private static final String IPS_COMPOSITION_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips|2.0.1";
  private static final String IPS_CONDITION_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Condition-uv-ips|2.0.1";
  private static final RSAKey SIGNING_KEY = newSigningKey("exchange-trust-fixture");
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(TEST_PASSWORD);

  @Container private static final PostgreSQLContainer CONTAINER = POSTGRES;
  private static HttpServer jwksServer;
  private static HttpsServer mcsdServer;
  private static final AtomicInteger MCSD_REQUESTS = new AtomicInteger();
  private static final AtomicReference<String> MCSD_REQUEST_TARGET = new AtomicReference<>();
  private static final AtomicReference<byte[]> MCSD_RESPONSE_BODY =
      new AtomicReference<>(new byte[0]);
  private static final AtomicInteger TRANSPORT_PROVIDER_REQUESTS = new AtomicInteger();
  private static final AtomicReference<String> TRANSPORT_PROVIDER_REQUEST_TARGET =
      new AtomicReference<>();
  private static final AtomicReference<String> TRANSPORT_PROVIDER_REQUEST_BODY =
      new AtomicReference<>();
  private static final AtomicReference<String> TRANSPORT_PROVIDER_OPERATION_REFERENCE =
      new AtomicReference<>();
  private static final java.util.concurrent.atomic.AtomicBoolean DROP_TRANSPORT_PROVIDER_RESPONSE =
      new java.util.concurrent.atomic.AtomicBoolean();

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private BootstrapGrantService bootstrapService;
  @Autowired private PartnerTrustProfileStore trustProfileStore;

  @Autowired
  private org.ayuvahak.transport.application.TransportProviderSubmissionCommands
      transportProviderSubmissions;

  @Autowired private org.ayuvahak.transport.application.TransportRequestStore transportRequests;
  @Autowired private DirectorySourceProjectionImportPort directoryProjectionImporter;
  @Autowired private ExecutionContextFactory executionContextFactory;
  @Autowired private AuthorizationService authorizationService;
  @Autowired private DirectoryMcsdSynchronizationService directoryMcsdSynchronization;
  @Autowired private DirectoryMcsdFeedProjectionImportPort feedProjectionImporter;
  @Autowired private DirectoryMcsdFeedReceiptStore feedReceiptStore;
  @Autowired private ReferralPatientContextSourcePort referralPatientContextSource;
  @Autowired private AuditWriter auditWriter;
  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired
  @Qualifier("auditClock")
  private Clock auditClock;

  @BeforeAll
  static void startLocalJwksServer() throws Exception {
    jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    jwksServer.createContext(
        "/jwks",
        exchange -> {
          byte[] body =
              new JWKSet(SIGNING_KEY.toPublicJWK())
                  .toString()
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (var response = exchange.getResponseBody()) {
            response.write(body);
          }
        });
    jwksServer.start();

    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    char[] password = "local-test-only".toCharArray();
    try (InputStream input =
        PartnerTrustAdministrationIT.class.getResourceAsStream("/exchange/synthetic-partner.p12")) {
      if (input == null) {
        throw new IllegalStateException("Synthetic local TLS fixture is missing.");
      }
      keyStore.load(input, password);
    }
    KeyManagerFactory keyManagers =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(keyStore, password);
    SSLContext serverContext = SSLContext.getInstance("TLS");
    serverContext.init(keyManagers.getKeyManagers(), null, null);
    mcsdServer =
        HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    mcsdServer.setHttpsConfigurator(new HttpsConfigurator(serverContext));
    mcsdServer.createContext(
        "/",
        exchange -> {
          if ("POST".equals(exchange.getRequestMethod())) {
            TRANSPORT_PROVIDER_REQUESTS.incrementAndGet();
            TRANSPORT_PROVIDER_REQUEST_TARGET.set(
                exchange.getRequestMethod() + " " + exchange.getRequestURI());
            TRANSPORT_PROVIDER_REQUEST_BODY.set(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            TRANSPORT_PROVIDER_OPERATION_REFERENCE.set(
                exchange.getRequestHeaders().getFirst("X-AyuVahak-Operation-Reference"));
            if (DROP_TRANSPORT_PROVIDER_RESPONSE.get()) {
              exchange.close();
              return;
            }
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
          }
          MCSD_REQUESTS.incrementAndGet();
          MCSD_REQUEST_TARGET.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
          byte[] response = MCSD_RESPONSE_BODY.get();
          exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
          exchange.sendResponseHeaders(200, response.length);
          try (var output = exchange.getResponseBody()) {
            output.write(response);
          }
        });
    mcsdServer.start();
  }

  @AfterAll
  static void stopLocalJwksServer() {
    if (mcsdServer != null) {
      mcsdServer.stop(0);
    }
    if (jwksServer != null) {
      jwksServer.stop(0);
    }
  }

  @DynamicPropertySource
  static void localInfrastructure(DynamicPropertyRegistry registry) {
    registry.add("AYUVAHAK_DATABASE_URL", POSTGRES::getJdbcUrl);
    registry.add("AYUVAHAK_DATABASE_USERNAME", POSTGRES::getUsername);
    registry.add("AYUVAHAK_DATABASE_PASSWORD", POSTGRES::getPassword);
    registry.add("ayuvahak.authentication.issuer", () -> ISSUER);
    registry.add(
        "ayuvahak.authentication.jwk-set-uri",
        () -> "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks");
    registry.add(
        "ayuvahak.directory.mcsd.source-bindings[0].source-namespace", () -> FEED_SOURCE_NAMESPACE);
    registry.add(
        "ayuvahak.directory.mcsd.source-bindings[0].partner-key", () -> "synthetic-directory-feed");
    registry.add(
        "ayuvahak.directory.mcsd.ordered-last-updated-profiles[0].resource-type",
        () -> "Organization");
    registry.add(
        "ayuvahak.directory.mcsd.ordered-last-updated-profiles[0].profile-uri",
        () -> MCSD_ORGANIZATION_PROFILE);
    registry.add(
        "ayuvahak.referral.ips.source-bindings[0].patient-identifier-system",
        () -> "urn:synthetic:clinical-source:patients");
    registry.add(
        "ayuvahak.referral.ips.source-bindings[0].partner-key", () -> "synthetic-directory-feed");
    registry.add(
        "ayuvahak.test.partner.port", () -> Integer.toString(mcsdServer.getAddress().getPort()));
  }

  @BeforeEach
  void resetAndAuthorizeSyntheticOperator() throws Exception {
    jdbc.update("DELETE FROM platform_exchange.partner_admin_command_receipt");
    jdbc.update("DELETE FROM platform_exchange.partner_profile_operation");
    jdbc.update("DELETE FROM platform_exchange.partner_profile_version");
    jdbc.update("DELETE FROM platform_exchange.partner_profile");
    jdbc.update("DELETE FROM identity_access.grant_command_receipt");
    jdbc.update("DELETE FROM identity_access.bootstrap_marker");
    jdbc.update("DELETE FROM audit.audit_record");
    jdbc.update("DELETE FROM platform_eventing.outbox_delivery");
    jdbc.update("DELETE FROM platform_eventing.internal_event");
    jdbc.update("DELETE FROM identity_access.authorization_grant");
    jdbc.execute("DROP TRIGGER IF EXISTS test_fail_audit_insert ON audit.audit_record");
    jdbc.execute("DROP FUNCTION IF EXISTS test_fail_audit_insert()");
    TRANSPORT_PROVIDER_REQUESTS.set(0);
    TRANSPORT_PROVIDER_REQUEST_TARGET.set(null);
    TRANSPORT_PROVIDER_REQUEST_BODY.set(null);
    TRANSPORT_PROVIDER_OPERATION_REFERENCE.set(null);
    DROP_TRANSPORT_PROVIDER_RESPONSE.set(false);

    bootstrapService.provisionFirstGrant(bootstrapProperties(), "synthetic-host");
    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN)))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                .header("Idempotency-Key", "exchange-admin-grant-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"principal\":{\"kind\":\"HUMAN\",\"issuer\":\""
                        + ISSUER
                        + "\",\"subject\":\""
                        + PARTNER_ADMIN
                        + "\"},\"authority\":\"ExchangeTrustAdministration\","
                        + "\"scope\":{\"kind\":\"PLATFORM\"},\"reasonCode\":\"ACCESS_SETUP\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void createsReviewsActivatesVersionsSuspendsRevokesAndRecoversIdempotentCommands()
      throws Exception {
    String create = createBody("synthetic-directory", "/fhir");
    MvcResult created =
        mockMvc
            .perform(
                partnerRequest(
                    post("/api/v1/platform/exchange-partners"), PARTNER_ADMIN, "create-v1", create))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.partnerKey").value("SYNTHETIC-DIRECTORY"))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.state").value("DRAFT"))
            .andExpect(jsonPath("$.tokenSigningKeysConfigured").value(true))
            .andExpect(jsonPath("$.tokenSigningKeysReference").doesNotExist())
            .andExpect(jsonPath("$.credentialReference").doesNotExist())
            .andReturn();
    String versionOneId =
        com.jayway.jsonpath.JsonPath.read(
            created.getResponse().getContentAsString(), "$.profileVersionId");

    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners"), PARTNER_ADMIN, "create-v1", create))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.profileVersionId").value(versionOneId));
    assertThat(count("SELECT COUNT(*) FROM platform_exchange.partner_profile_version"))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM platform_exchange.partner_admin_command_receipt"))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT credential_reference FROM platform_exchange.partner_profile_version WHERE version_number = 1",
                String.class))
        .isEqualTo(SECRET_REFERENCE);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'EXCHANGE_PARTNER_CREATE' "
                    + "AND target_id = ? AND target_type = 'PartnerTrustProfile'",
                Integer.class,
                java.util.UUID.fromString(versionOneId)))
        .isEqualTo(1);

    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners/SYNTHETIC-DIRECTORY/versions/1/activate"),
                PARTNER_ADMIN,
                "activate-v1",
                "{\"reasonCode\":\"PARTNER_ACTIVATION\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ACTIVE"));

    String versionTwo =
        "{\"expectedCurrentVersion\":1,\"configuration\":"
            + configurationJson("/fhir/v2")
            + ",\"reasonCode\":\"PARTNER_CHANGE\"}";
    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners/SYNTHETIC-DIRECTORY/versions"),
                PARTNER_ADMIN,
                "create-v2",
                versionTwo))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.state").value("DRAFT"));
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM platform_exchange.partner_profile_version WHERE version_number = 1",
                String.class))
        .isEqualTo("ACTIVE");

    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners/SYNTHETIC-DIRECTORY/versions/2/activate"),
                PARTNER_ADMIN,
                "activate-v2",
                "{\"reasonCode\":\"PARTNER_ACTIVATION\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ACTIVE"));
    assertThat(
            jdbc.queryForObject(
                "SELECT string_agg(state, ',' ORDER BY version_number) "
                    + "FROM platform_exchange.partner_profile_version",
                String.class))
        .isEqualTo("SUPERSEDED,ACTIVE");
    assertThat(
            count(
                "SELECT COUNT(*) FROM platform_exchange.partner_profile_version WHERE state = 'ACTIVE'"))
        .isEqualTo(1);

    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners/SYNTHETIC-DIRECTORY/suspend"),
                PARTNER_ADMIN,
                "suspend-v2",
                "{\"reasonCode\":\"PARTNER_SUSPENSION\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("SUSPENDED"));
    assertThat(
            count(
                "SELECT COUNT(*) FROM platform_exchange.partner_profile_version WHERE state = 'ACTIVE'"))
        .isZero();

    String versionThree =
        "{\"expectedCurrentVersion\":2,\"configuration\":"
            + configurationJson("/fhir/v3")
            + ",\"reasonCode\":\"PARTNER_CHANGE\"}";
    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners/SYNTHETIC-DIRECTORY/versions"),
                PARTNER_ADMIN,
                "create-v3",
                versionThree))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(3));
    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners/SYNTHETIC-DIRECTORY/versions/3/activate"),
                PARTNER_ADMIN,
                "activate-v3",
                "{\"reasonCode\":\"PARTNER_ACTIVATION\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ACTIVE"));
    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners/SYNTHETIC-DIRECTORY/revoke"),
                PARTNER_ADMIN,
                "revoke-v3",
                "{\"reasonCode\":\"SECURITY_RESPONSE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("REVOKED"));
    assertThat(
            count(
                "SELECT COUNT(*) FROM platform_exchange.partner_profile_version WHERE state = 'ACTIVE'"))
        .isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code LIKE 'EXCHANGE_PARTNER_%'"))
        .isEqualTo(8);

    mockMvc
        .perform(
            partnerRequest(
                get("/api/v1/platform/exchange-partners/SYNTHETIC-DIRECTORY/versions"),
                PARTNER_ADMIN,
                null,
                null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[0].credentialReference").doesNotExist());
  }

  @Test
  void suspensionAndRevocationTargetActiveVersionWhileNewDraftIsPending() throws Exception {
    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners"),
                PARTNER_ADMIN,
                "draft-revocation-create-v1",
                createBody("synthetic-directory", "/fhir")))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners/SYNTHETIC-DIRECTORY/versions/1/activate"),
                PARTNER_ADMIN,
                "draft-revocation-activate-v1",
                "{\"reasonCode\":\"PARTNER_ACTIVATION\"}"))
        .andExpect(status().isOk());

    String draft =
        "{\"expectedCurrentVersion\":1,\"configuration\":"
            + configurationJson("/fhir/v2")
            + ",\"reasonCode\":\"PARTNER_CHANGE\"}";
    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners/SYNTHETIC-DIRECTORY/versions"),
                PARTNER_ADMIN,
                "draft-revocation-create-v2",
                draft))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.state").value("DRAFT"));

    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners/SYNTHETIC-DIRECTORY/suspend"),
                PARTNER_ADMIN,
                "draft-revocation-suspend-v1",
                "{\"reasonCode\":\"SECURITY_RESPONSE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.state").value("SUSPENDED"));

    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners/SYNTHETIC-DIRECTORY/revoke"),
                PARTNER_ADMIN,
                "draft-revocation-revoke-v1",
                "{\"reasonCode\":\"SECURITY_RESPONSE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.state").value("REVOKED"));

    assertThat(
            jdbc.queryForObject(
                "SELECT string_agg(state, ',' ORDER BY version_number) "
                    + "FROM platform_exchange.partner_profile_version",
                String.class))
        .isEqualTo("REVOKED,SUPERSEDED");
  }

  @Test
  void activeInboundFeedProfileBindsExactVerifiedServicePrincipal() throws Exception {
    String configuration = inboundFeedConfigurationJson();
    String create =
        "{\"partnerKey\":\"synthetic-directory-feed\","
            + configuration.substring(1, configuration.length() - 1)
            + ",\"reasonCode\":\"PARTNER_SETUP\"}";
    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners"),
                PARTNER_ADMIN,
                "inbound-feed-create",
                create))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.inboundPrincipalClientId").value("synthetic-directory-feed"));
    mockMvc
        .perform(
            partnerRequest(
                post(
                    "/api/v1/platform/exchange-partners/synthetic-directory-feed/versions/1/activate"),
                PARTNER_ADMIN,
                "inbound-feed-activate",
                "{\"reasonCode\":\"PARTNER_ACTIVATION\"}"))
        .andExpect(status().isOk());

    var operation = AllowedOperation.directoryIti130Feed();
    var trustedPrincipal = ActorReference.service(new IssuerId(ISSUER), "synthetic-directory-feed");
    assertThat(trustProfileStore.findActiveInbound(trustedPrincipal, operation)).isPresent();
    assertThat(
            trustProfileStore.findActiveInbound(
                ActorReference.service(new IssuerId(ISSUER), "another-service"), operation))
        .isEmpty();
  }

  @Test
  void activeTransportProviderProfileStoresOnlyNamedJsonOperations() throws Exception {
    String configuration = transportProviderConfigurationJson();
    String create =
        "{\"partnerKey\":\"synthetic-transport-provider\","
            + configuration.substring(1, configuration.length() - 1)
            + ",\"reasonCode\":\"PARTNER_SETUP\"}";
    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners"),
                PARTNER_ADMIN,
                "transport-provider-create",
                create))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.inboundPrincipalClientId").value("synthetic-transport-provider"));
    mockMvc
        .perform(
            partnerRequest(
                post(
                    "/api/v1/platform/exchange-partners/synthetic-transport-provider/versions/1/activate"),
                PARTNER_ADMIN,
                "transport-provider-activate",
                "{\"reasonCode\":\"PARTNER_ACTIVATION\"}"))
        .andExpect(status().isOk());

    var providerPrincipal =
        ActorReference.service(new IssuerId(ISSUER), "synthetic-transport-provider");
    assertThat(
            trustProfileStore.findActiveInbound(
                providerPrincipal, AllowedOperation.transportProviderEvidence()))
        .isPresent();
    assertThat(
            jdbc.queryForList(
                "SELECT owner_context, operation_code, direction, protocol "
                    + "FROM platform_exchange.partner_profile_operation "
                    + "WHERE profile_version_id = (SELECT profile_version_id "
                    + "FROM platform_exchange.partner_profile_version v "
                    + "JOIN platform_exchange.partner_profile p USING (partner_id) "
                    + "WHERE partner_key = 'SYNTHETIC-TRANSPORT-PROVIDER' AND version_number = 1) "
                    + "ORDER BY operation_code"))
        .containsExactly(
            java.util.Map.of(
                "owner_context", "TRANSPORT",
                "operation_code", "transport.destination-change",
                "direction", "OUTBOUND",
                "protocol", "JSON"),
            java.util.Map.of(
                "owner_context", "TRANSPORT",
                "operation_code", "transport.dispatch",
                "direction", "OUTBOUND",
                "protocol", "JSON"),
            java.util.Map.of(
                "owner_context", "TRANSPORT",
                "operation_code", "transport.provider.evidence",
                "direction", "INBOUND",
                "protocol", "JSON"),
            java.util.Map.of(
                "owner_context", "TRANSPORT",
                "operation_code", "transport.request-cancellation",
                "direction", "OUTBOUND",
                "protocol", "JSON"));
  }

  @Test
  void transportProviderDispatchUsesLocalTrustedExchangeOnceAndPersistsAcknowledgement()
      throws Exception {
    DirectoryFacility origin = createReceivingFacility();
    InternalId organizationId = InternalId.parse(origin.organizationId());
    InternalId facilityId = InternalId.parse(origin.facilityId());
    InternalId requestId = InternalId.parse("20000000-0000-4000-8000-000000000003");
    activateLocalTransportProvider();
    createHumanGrant(
        "transport-provider-dispatch-authority",
        "synthetic-transport-operator",
        "TransportDispatchCoordinationAuthority",
        facilityScopeJson(organizationId, facilityId));
    var request =
        syntheticTransportRequest(
            requestId,
            InternalId.parse("20000000-0000-4000-8000-000000000004"),
            organizationId,
            facilityId);
    assertThat(
            transportRequests.insert(
                request,
                request.initiatedBy().identity().issuer().value(),
                request.initiatedBy().identity().resourceType(),
                request.recordedBy().identity().issuer().value(),
                request.recordedBy().identity().resourceType()))
        .isTrue();
    var operator = ActorReference.human(new IssuerId(ISSUER), "synthetic-transport-operator");
    var context =
        executionContextFactory.forInteractiveRequest(
            operator,
            RequestedExecutionScope.facility(
                new ExternalReference(
                    new IssuerId("urn:ayuvahak:directory"),
                    "Organization",
                    organizationId.toString()),
                request.originFacility()),
            new CorrelationId("synthetic-provider-dispatch"));

    var sent =
        transportProviderSubmissions
            .submit(
                context,
                requestId,
                org.ayuvahak.transport.domain.TransportProviderSubmission.OperationKind.DISPATCH,
                requestId)
            .orElseThrow();
    assertThat(sent.submission().state())
        .as(
            "provider failure=%s, local requests=%s",
            sent.submission().failureCode(), TRANSPORT_PROVIDER_REQUESTS.get())
        .isEqualTo(org.ayuvahak.transport.domain.TransportProviderSubmission.State.ACKNOWLEDGED);
    assertThat(sent.submission().responseStatus()).contains(202);
    assertThat(sent.submission().configurationVersion()).contains(1);
    assertThat(TRANSPORT_PROVIDER_REQUESTS).hasValue(1);
    assertThat(TRANSPORT_PROVIDER_REQUEST_TARGET)
        .hasValue("POST /api/transport-requests/" + requestId + "/dispatches");
    assertThat(TRANSPORT_PROVIDER_OPERATION_REFERENCE).hasValue(requestId.toString());
    assertThat(TRANSPORT_PROVIDER_REQUEST_BODY.get())
        .contains(requestId.toString(), request.transferId().toString())
        .doesNotContain("Patient", "SYN-PATIENT", "diagnosis", "clinical");

    var replay =
        transportProviderSubmissions
            .submit(
                context,
                requestId,
                org.ayuvahak.transport.domain.TransportProviderSubmission.OperationKind.DISPATCH,
                requestId)
            .orElseThrow();
    assertThat(replay.replayed()).isTrue();
    assertThat(TRANSPORT_PROVIDER_REQUESTS).hasValue(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record "
                    + "WHERE action_code = 'TRANSPORT_PROVIDER_SUBMISSION_ACKNOWLEDGED'"))
        .isEqualTo(1);
  }

  @Test
  void transportProviderLostResponseAndStaleAttemptAreUnknownAndNeverRepeated() throws Exception {
    DirectoryFacility origin = createReceivingFacility();
    InternalId organizationId = InternalId.parse(origin.organizationId());
    InternalId facilityId = InternalId.parse(origin.facilityId());
    InternalId requestId = InternalId.parse("20000000-0000-4000-8000-000000000013");
    activateLocalTransportProvider();
    createHumanGrant(
        "transport-provider-lost-response-authority",
        "synthetic-transport-operator",
        "TransportDispatchCoordinationAuthority",
        facilityScopeJson(organizationId, facilityId));
    var request =
        syntheticTransportRequest(
            requestId,
            InternalId.parse("20000000-0000-4000-8000-000000000014"),
            organizationId,
            facilityId);
    assertThat(
            transportRequests.insert(
                request,
                request.initiatedBy().identity().issuer().value(),
                request.initiatedBy().identity().resourceType(),
                request.recordedBy().identity().issuer().value(),
                request.recordedBy().identity().resourceType()))
        .isTrue();
    var operator = ActorReference.human(new IssuerId(ISSUER), "synthetic-transport-operator");
    var context =
        executionContextFactory.forInteractiveRequest(
            operator,
            RequestedExecutionScope.facility(
                new ExternalReference(
                    new IssuerId("urn:ayuvahak:directory"),
                    "Organization",
                    organizationId.toString()),
                request.originFacility()),
            new CorrelationId("synthetic-provider-lost-response"));
    DROP_TRANSPORT_PROVIDER_RESPONSE.set(true);

    var unknown =
        transportProviderSubmissions
            .submit(
                context,
                requestId,
                org.ayuvahak.transport.domain.TransportProviderSubmission.OperationKind.DISPATCH,
                requestId)
            .orElseThrow();
    assertThat(unknown.submission().state())
        .isEqualTo(org.ayuvahak.transport.domain.TransportProviderSubmission.State.OUTCOME_UNKNOWN);
    assertThat(unknown.submission().failureCode()).isPresent();
    assertThat(TRANSPORT_PROVIDER_REQUESTS).hasValue(1);
    assertThat(TRANSPORT_PROVIDER_REQUEST_BODY.get()).contains(requestId.toString());

    var replay =
        transportProviderSubmissions
            .submit(
                context,
                requestId,
                org.ayuvahak.transport.domain.TransportProviderSubmission.OperationKind.DISPATCH,
                requestId)
            .orElseThrow();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.submission().state())
        .isEqualTo(org.ayuvahak.transport.domain.TransportProviderSubmission.State.OUTCOME_UNKNOWN);
    assertThat(TRANSPORT_PROVIDER_REQUESTS).hasValue(1);
    assertThat(transportRequests.find(requestId))
        .get()
        .extracting(value -> value.status())
        .isEqualTo(org.ayuvahak.transport.domain.TransportRequest.Status.REQUESTED);

    // Recreate the durable state left if the process stopped after sending but before persisting
    // the response. The local provider has already observed exactly one operation.
    java.time.Instant staleSince = java.time.Instant.now().minusSeconds(7 * 60);
    jdbc.update(
        "UPDATE transport.provider_submission SET submission_state = 'ATTEMPTING', "
            + "response_status = NULL, failure_code = NULL, created_at = ?, updated_at = ?, "
            + "version = version + 1 WHERE operation_kind = 'DISPATCH' AND operation_reference = ?",
        java.sql.Timestamp.from(staleSince),
        java.sql.Timestamp.from(staleSince),
        requestId.value());
    var recoveredAfterCrash =
        transportProviderSubmissions
            .submit(
                context,
                requestId,
                org.ayuvahak.transport.domain.TransportProviderSubmission.OperationKind.DISPATCH,
                requestId)
            .orElseThrow();
    assertThat(recoveredAfterCrash.submission().state())
        .isEqualTo(org.ayuvahak.transport.domain.TransportProviderSubmission.State.OUTCOME_UNKNOWN);
    assertThat(recoveredAfterCrash.submission().failureCode()).contains("ATTEMPT_EXPIRED_UNKNOWN");
    assertThat(TRANSPORT_PROVIDER_REQUESTS).hasValue(1);
  }

  @Test
  void inboundDirectoryFeedAppliesOwnerScopedChangesAndRecoversReplayOutcome() throws Exception {
    createGrant(
        "directory-curator-grant",
        "synthetic-directory-curator",
        "DirectoryCuration",
        "{\"kind\":\"PLATFORM\"}");
    var curator =
        executionContextFactory.forIntegrationRequest(
            ActorReference.service(new IssuerId(ISSUER), "synthetic-directory-curator"),
            RequestedExecutionScope.platform(),
            new CorrelationId("feed-fixture-organization"));
    var organizationCreated =
        directoryProjectionImporter.importSourceProjection(
            curator,
            new DirectorySourceProjection.OrganizationFact(
                new DirectorySourceProjection.Metadata(
                    new ExternalReference(
                        new IssuerId(FEED_SOURCE_NAMESPACE), "Organization", "SYN-ORG-001"),
                    java.util.Optional.of("1"),
                    java.util.Optional.of(Instant.parse("2026-10-14T10:00:00Z")),
                    true,
                    MCSD_ORGANIZATION_PROFILE,
                    fingerprint("synthetic-directory-feed-initial")),
                "Synthetic Directory Authority",
                java.util.Optional.empty(),
                true));
    String directoryOrganizationId = organizationCreated.conceptId().orElseThrow().toString();
    directoryProjectionImporter.importSourceProjection(
        curator,
        new DirectorySourceProjection.OrganizationFact(
            new DirectorySourceProjection.Metadata(
                new ExternalReference(
                    new IssuerId(FEED_SOURCE_NAMESPACE), "Organization", "SYN-ORG-OTHER"),
                java.util.Optional.of("1"),
                java.util.Optional.of(Instant.parse("2026-10-14T10:00:00Z")),
                true,
                MCSD_ORGANIZATION_PROFILE,
                fingerprint("synthetic-directory-feed-other-organization")),
            "Synthetic Other Authority",
            java.util.Optional.empty(),
            true));

    createGrant(
        "directory-feed-scope-grant",
        "synthetic-directory-feed",
        "DirectoryMcsdSourceFeedImport",
        "{\"kind\":\"ORGANIZATION\",\"organization\":{\"issuer\":\""
            + DIRECTORY_ISSUER
            + "\",\"identifier\":\""
            + directoryOrganizationId
            + "\"}}");

    var feedActor = ActorReference.service(new IssuerId(ISSUER), "synthetic-directory-feed");
    var feedScope =
        RequestedExecutionScope.organization(
            new ExternalReference(
                new IssuerId(DIRECTORY_ISSUER), "Organization", directoryOrganizationId));
    var feedContext =
        executionContextFactory.forIntegrationRequest(
            feedActor, feedScope, new CorrelationId("feed-authority-check"));
    assertThat(
            authorizationService
                .evaluate(feedContext, "DirectoryMcsdSourceFeedImport")
                .denialReason())
        .isEmpty();

    String configuration = inboundFeedConfigurationJson(DIRECTORY_ISSUER, directoryOrganizationId);
    String create =
        "{\"partnerKey\":\"synthetic-directory-feed\","
            + configuration.substring(1, configuration.length() - 1)
            + ",\"reasonCode\":\"PARTNER_SETUP\"}";
    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners"),
                PARTNER_ADMIN,
                "inbound-apply-create",
                create))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            partnerRequest(
                post(
                    "/api/v1/platform/exchange-partners/synthetic-directory-feed/versions/1/activate"),
                PARTNER_ADMIN,
                "inbound-apply-activate",
                "{\"reasonCode\":\"PARTNER_ACTIVATION\"}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            directoryFeedRequest(
                "feed-out-of-scope-001",
                organizationFeedBundle("SYN-ORG-OTHER", "Out of Scope Authority Update", "2")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));
    assertThat(
            count(
                "SELECT COUNT(*) FROM directory.mcsd_feed_receipt "
                    + "WHERE external_message_id = 'feed-out-of-scope-001'"))
        .isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record "
                    + "WHERE action_code = 'DIRECTORY_MCSD_INBOUND_FEED' "
                    + "AND outcome = 'DENIED' "
                    + "AND reason_code = 'TARGET_SCOPE_MISMATCH'"))
        .isEqualTo(1);

    String body = organizationFeedBundle("Synthetic Directory Authority Updated", "2");
    long sourceImportAuditsBeforeFeed =
        count(
            "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'DIRECTORY_MCSD_SOURCE_IMPORT'");
    var applied =
        mockMvc
            .perform(directoryFeedRequest("feed-message-001", body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resourceType").value("Bundle"))
            .andExpect(jsonPath("$.type").value("transaction-response"))
            .andExpect(jsonPath("$.entry[0].response.status").value("200 OK"))
            .andReturn();
    assertThat(applied.getResponse().getHeader("X-Correlation-ID")).isNotBlank();
    assertThat(
            jdbc.queryForObject(
                "SELECT name FROM directory.organization "
                    + "WHERE source_issuer = ? AND source_resource_type = 'Organization' "
                    + "AND source_reference = 'SYN-ORG-001'",
                String.class,
                FEED_SOURCE_NAMESPACE))
        .isEqualTo("Synthetic Directory Authority Updated");
    assertThat(count("SELECT COUNT(*) FROM directory.mcsd_feed_receipt")).isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'DIRECTORY_MCSD_SOURCE_IMPORT'"))
        .isEqualTo(sourceImportAuditsBeforeFeed + 1);

    // A new application service instance models recovery after process loss. Its only replay state
    // comes from the owner receipt committed with the Directory projection above.
    DirectoryMcsdFeedImportPort restartedFeedImporter =
        new DirectoryMcsdFeedImportService(
            feedProjectionImporter, feedReceiptStore, auditWriter, transactionManager, auditClock);
    var restartedResult =
        restartedFeedImporter.importFeed(
            executionContextFactory.forIntegrationRequest(
                feedActor, feedScope, new CorrelationId("feed-restart-recovery")),
            new IssuerId(FEED_SOURCE_NAMESPACE),
            "feed-message-001",
            fingerprint(body),
            List.of(
                new DirectoryMcsdFeedEntry(
                    0,
                    new DirectorySourceProjection.OrganizationFact(
                        new DirectorySourceProjection.Metadata(
                            new ExternalReference(
                                new IssuerId(FEED_SOURCE_NAMESPACE), "Organization", "SYN-ORG-001"),
                            Optional.of("2"),
                            Optional.of(Instant.parse("2026-10-15T10:00:00Z")),
                            true,
                            MCSD_ORGANIZATION_PROFILE,
                            fingerprint(body)),
                        "Synthetic Directory Authority Updated",
                        Optional.empty(),
                        true))));
    assertThat(restartedResult.replayed()).isTrue();
    assertThat(restartedResult.outcomes())
        .containsExactly(DirectorySourceProjectionReconciliation.Outcome.UPDATE);
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'DIRECTORY_MCSD_SOURCE_IMPORT'"))
        .isEqualTo(sourceImportAuditsBeforeFeed + 1);

    mockMvc
        .perform(directoryFeedRequest("feed-message-001", body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entry[0].response.status").value("200 OK"));
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'DIRECTORY_MCSD_SOURCE_IMPORT'"))
        .isEqualTo(sourceImportAuditsBeforeFeed + 1);

    mockMvc
        .perform(
            directoryFeedRequest(
                "feed-message-001",
                organizationFeedBundle("Conflicting Synthetic Directory Name", "2")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EXCHANGE_REPLAY_CONFLICT"));
    assertThat(
            jdbc.queryForObject(
                "SELECT name FROM directory.organization "
                    + "WHERE source_issuer = ? AND source_resource_type = 'Organization' "
                    + "AND source_reference = 'SYN-ORG-001'",
                String.class,
                FEED_SOURCE_NAMESPACE))
        .isEqualTo("Synthetic Directory Authority Updated");
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'DIRECTORY_MCSD_FEED_REPLAY_CONFLICT' AND outcome = 'DENIED'"))
        .isEqualTo(1);

    long statusAuditsBeforeDelete =
        count(
            "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'DIRECTORY_MCSD_SOURCE_IMPORT' "
                + "AND reason_code = 'SOURCE_STATUS_OBSERVED'");
    mockMvc
        .perform(
            directoryFeedRequest(
                "feed-delete-001", organizationDeleteFeedBundle("SYN-ORG-001", "2")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entry[0].response.status").value("202 Accepted"));
    assertThat(
            jdbc.queryForMap(
                "SELECT source_active, last_outcome FROM directory.mcsd_source_projection "
                    + "WHERE source_issuer = ? AND source_resource_type = 'Organization' "
                    + "AND source_reference = 'SYN-ORG-001'",
                FEED_SOURCE_NAMESPACE))
        .containsEntry("source_active", false)
        .containsEntry("last_outcome", "RECONCILIATION_REQUIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT lifecycle_status FROM directory.organization "
                    + "WHERE source_issuer = ? AND source_resource_type = 'Organization' "
                    + "AND source_reference = 'SYN-ORG-001'",
                String.class,
                FEED_SOURCE_NAMESPACE))
        .isEqualTo("ACTIVE");
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'DIRECTORY_MCSD_SOURCE_IMPORT' "
                    + "AND reason_code = 'SOURCE_STATUS_OBSERVED'"))
        .isEqualTo(statusAuditsBeforeDelete + 1);
  }

  @Test
  void directoryMcsdDiscoveryUsesConfiguredPartnerAndOwnerImportContract() throws Exception {
    MCSD_REQUESTS.set(0);
    MCSD_REQUEST_TARGET.set(null);
    MCSD_RESPONSE_BODY.set(mcsdOrganizationSearchBundle());

    createGrant(
        "directory-outbound-curator-grant",
        "synthetic-directory-curator",
        "DirectoryCuration",
        "{\"kind\":\"PLATFORM\"}");

    String configuration = localOutboundDirectoryConfigurationJson();
    String create =
        "{\"partnerKey\":\"synthetic-directory-feed\","
            + configuration.substring(1, configuration.length() - 1)
            + ",\"reasonCode\":\"PARTNER_SETUP\"}";
    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners"),
                PARTNER_ADMIN,
                "mcsd-outbound-create",
                create))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            partnerRequest(
                post(
                    "/api/v1/platform/exchange-partners/synthetic-directory-feed/versions/1/activate"),
                PARTNER_ADMIN,
                "mcsd-outbound-activate",
                "{\"reasonCode\":\"PARTNER_ACTIVATION\"}"))
        .andExpect(status().isOk());

    var context =
        executionContextFactory.forIntegrationRequest(
            ActorReference.service(new IssuerId(ISSUER), "synthetic-directory-curator"),
            RequestedExecutionScope.platform(),
            new CorrelationId("mcsd-local-discovery"));
    DirectoryMcsdSynchronizationResult result =
        directoryMcsdSynchronization.discover(context, new IssuerId(FEED_SOURCE_NAMESPACE), 1);

    assertThat(result.status()).isEqualTo(DirectoryMcsdSynchronizationResult.Status.COMPLETE);
    assertThat(result.pagesRead()).isEqualTo(1);
    assertThat(result.factsImported()).isEqualTo(1);
    assertThat(MCSD_REQUESTS).hasValue(1);
    assertThat(MCSD_REQUEST_TARGET.get())
        .startsWith("GET /fhir?")
        .contains("_count=200")
        .contains("_type=Endpoint%2CHealthcareService%2CLocation%2COrganization");
    assertThat(
            jdbc.queryForObject(
                "SELECT name FROM directory.organization "
                    + "WHERE source_issuer = ? AND source_resource_type = 'Organization' "
                    + "AND source_reference = 'SYN-ORG-MCSD-OUTBOUND-001'",
                String.class,
                FEED_SOURCE_NAMESPACE))
        .isEqualTo("Synthetic Outbound Directory Authority");
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record "
                    + "WHERE action_code = 'DIRECTORY_MCSD_SOURCE_IMPORT' "
                    + "AND reason_code = 'SOURCE_FACT_IMPORTED'"))
        .isEqualTo(1);
  }

  @Test
  void referralIpsSourceUsesConfiguredPartnerAndReturnsOnlyBoundedProjection(CapturedOutput output)
      throws Exception {
    String marker = "SYN-PRIVATE-PATIENT-MARKER-8F93";
    MCSD_REQUESTS.set(0);
    MCSD_REQUEST_TARGET.set(null);
    MCSD_RESPONSE_BODY.set(syntheticIpsDocument("urn:synthetic:clinical-source:patients", marker));

    String configuration = localIpsConfigurationJson();
    String create =
        "{\"partnerKey\":\"synthetic-directory-feed\","
            + configuration.substring(1, configuration.length() - 1)
            + ",\"reasonCode\":\"PARTNER_SETUP\"}";
    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners"),
                PARTNER_ADMIN,
                "ips-outbound-create",
                create))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            partnerRequest(
                post(
                    "/api/v1/platform/exchange-partners/synthetic-directory-feed/versions/1/activate"),
                PARTNER_ADMIN,
                "ips-outbound-activate",
                "{\"reasonCode\":\"PARTNER_ACTIVATION\"}"))
        .andExpect(status().isOk());

    PatientReference patientReference =
        new PatientReference(
            new ExternalReference(
                new IssuerId("urn:synthetic:clinical-source:patients"), "Patient", marker),
            java.util.Optional.empty());
    InternalId referralId = InternalId.generate();
    InternalId transferId = InternalId.generate();
    InternalId attemptId = InternalId.generate();
    DirectoryFacility receivingRefs = createReceivingFacility();
    String receivingOrganizationId = receivingRefs.organizationId();
    String receivingFacilityId = receivingRefs.facilityId();
    String recipientSubject = "synthetic-ips-recipient";
    ActorReference recipient = ActorReference.human(new IssuerId(ISSUER), recipientSubject);
    createHumanGrant(
        "ips-recipient-grant",
        recipientSubject,
        ReferralAuthorities.patientContextDisclosure().code(),
        "{\"kind\":\"FACILITY\",\"organization\":{\"issuer\":\""
            + DIRECTORY_ISSUER
            + "\",\"identifier\":\""
            + receivingOrganizationId
            + "\"},\"facility\":{\"issuer\":\""
            + DIRECTORY_ISSUER
            + "\",\"identifier\":\""
            + receivingFacilityId
            + "\"}}");

    ExternalReference originOrganization =
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Organization", "SYN-ORG-ORIGIN-001");
    ExternalReference receivingOrganization =
        new ExternalReference(
            new IssuerId(DIRECTORY_ISSUER), "Organization", receivingOrganizationId);
    ExternalReference receivingFacility =
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", receivingFacilityId);
    Instant submittedAt = Instant.parse("2026-10-15T08:00:00Z");
    CareRequirement careRequirement =
        new CareRequirement(
            List.of("SYN-CARE-COORDINATION"),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            Optional.empty(),
            Optional.empty());
    ReferralDraft current =
        new ReferralDraft(
            referralId,
            originOrganization,
            Optional.empty(),
            patientReference,
            careRequirement,
            recipient,
            recipient,
            ReferralState.SUBMITTED,
            2,
            submittedAt.minusSeconds(120),
            submittedAt);
    SubmittedReferralRevision revision =
        new SubmittedReferralRevision(
            referralId,
            1,
            2,
            originOrganization,
            Optional.empty(),
            patientReference,
            careRequirement,
            recipient,
            recipient,
            submittedAt,
            Optional.empty());
    ReferralDraftStore referralStore =
        (ReferralDraftStore)
            java.lang.reflect.Proxy.newProxyInstance(
                ReferralDraftStore.class.getClassLoader(),
                new Class<?>[] {ReferralDraftStore.class},
                (proxy, method, arguments) ->
                    switch (method.getName()) {
                      case "findDraft" -> Optional.of(current);
                      case "findLatestSubmittedRevision" -> Optional.of(revision);
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    TransferPatientContextParticipationPort transferParticipation =
        (context, requestedReferral, requestedTransfer, requestedAttempt) ->
            requestedReferral.equals(referralId)
                    && requestedTransfer.equals(transferId)
                    && requestedAttempt.equals(attemptId)
                ? Optional.of(
                    new ReferralTransferParticipation(
                        referralId, transferId, attemptId, 1, receivingFacility))
                : Optional.empty();
    PatientContextDisclosurePolicy disclosurePolicy =
        request ->
            new PatientContextDisclosureDecision(
                PatientContextDisclosureDecision.Outcome.ALLOWED,
                Set.of(PatientContextCategory.PROBLEMS));
    ReferralPatientContextQueries patientContextQueries =
        new ReferralPatientContextQueries(
            referralStore,
            transferParticipation,
            authorizationService,
            auditWriter,
            Optional.of(disclosurePolicy),
            Optional.of(referralPatientContextSource),
            transactionManager,
            auditClock);
    CorrelationId correlationId = new CorrelationId("synthetic-ips-read");
    ExecutionContextFactory testScopeFactory = new ExecutionContextFactory((actor, scope) -> true);
    var recipientContext =
        testScopeFactory.forInteractiveRequest(
            recipient,
            RequestedExecutionScope.facility(receivingOrganization, receivingFacility),
            correlationId);
    PatientContextResult result =
        patientContextQueries.retrieve(
            recipientContext,
            referralId,
            new ReferralPatientContextRequest(transferId, attemptId, "CARE_COORDINATION"));

    assertThat(result.status()).isEqualTo(PatientContextResult.Status.AVAILABLE);
    assertThat(result.sections())
        .singleElement()
        .satisfies(
            section ->
                assertThat(section.codes())
                    .extracting("code")
                    .containsExactly("SYN-CODED-PROBLEM-001"));
    assertThat(MCSD_REQUESTS).hasValue(1);
    assertThat(MCSD_REQUEST_TARGET.get())
        .startsWith("GET /fhir/Patient/$summary?")
        .contains("SYN-PRIVATE-PATIENT-MARKER-8F93");
    assertThat(output.getOut() + output.getErr()).doesNotContain(marker);
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record "
                    + "WHERE action_code = 'REFERRAL_PATIENT_CONTEXT_DISCLOSURE' "
                    + "AND target_id = ? AND correlation_id = ? AND outcome = 'SUCCEEDED'",
                referralId.value(),
                correlationId.value()))
        .isEqualTo(2);
    assertThat(
            count(
                "SELECT COUNT(*) FROM platform_exchange.partner_profile_version "
                    + "WHERE credential_reference LIKE '%' || 'SYN-PRIVATE-PATIENT-MARKER-8F93' || '%'"))
        .isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record "
                    + "WHERE action_code LIKE '%IPS%' "
                    + "AND target_id::text LIKE '%' || 'SYN-PRIVATE-PATIENT-MARKER-8F93' || '%'"))
        .isZero();
    assertThat(result.toString()).doesNotContain(marker);

    var wrongFacilityContext =
        testScopeFactory.forInteractiveRequest(
            recipient,
            RequestedExecutionScope.facility(
                receivingOrganization,
                new ExternalReference(
                    new IssuerId(DIRECTORY_ISSUER), "Facility", "SYN-FACILITY-OTHER-001")),
            new CorrelationId("synthetic-ips-wrong-recipient"));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                patientContextQueries.retrieve(
                    wrongFacilityContext,
                    referralId,
                    new ReferralPatientContextRequest(transferId, attemptId, "CARE_COORDINATION")))
        .isInstanceOf(org.ayuvahak.platform.api.PlatformProblemException.class);
    assertThat(MCSD_REQUESTS).hasValue(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record "
                    + "WHERE action_code = 'REFERRAL_PATIENT_CONTEXT_DISCLOSURE' "
                    + "AND reason_code = 'TRANSFER_PARTICIPATION_MISMATCH' "
                    + "AND outcome = 'DENIED'"))
        .isEqualTo(1);
  }

  @Test
  void authorizationAndRequiredAuditFailClosed() throws Exception {
    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners"),
                "synthetic-ungranted-actor",
                "unauthorized-create",
                createBody("denied-partner", "/fhir")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));
    assertThat(count("SELECT COUNT(*) FROM platform_exchange.partner_profile")).isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'EXCHANGE_PARTNER_CREATE' "
                    + "AND outcome = 'DENIED'"))
        .isEqualTo(1);

    jdbc.execute(
        "CREATE FUNCTION test_fail_audit_insert() RETURNS trigger LANGUAGE plpgsql AS "
            + "'BEGIN RAISE EXCEPTION ''synthetic audit failure''; END'");
    jdbc.execute(
        "CREATE TRIGGER test_fail_audit_insert BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION test_fail_audit_insert()");

    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners"),
                PARTNER_ADMIN,
                "audit-failure-create",
                createBody("audit-failure-partner", "/fhir")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));
    assertThat(count("SELECT COUNT(*) FROM platform_exchange.partner_profile")).isZero();
    assertThat(count("SELECT COUNT(*) FROM platform_exchange.partner_admin_command_receipt"))
        .isZero();
  }

  @Test
  void changedRequestCannotReuseAnIdempotencyKey() throws Exception {
    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners"),
                PARTNER_ADMIN,
                "same-command-key",
                createBody("synthetic-partner-a", "/fhir")))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners"),
                PARTNER_ADMIN,
                "same-command-key",
                createBody("synthetic-partner-a", "/fhir/v2")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    assertThat(count("SELECT COUNT(*) FROM platform_exchange.partner_profile")).isEqualTo(1);
  }

  private MockHttpServletRequestBuilder partnerRequest(
      MockHttpServletRequestBuilder request, String subject, String idempotencyKey, String body)
      throws Exception {
    request
        .header("Authorization", bearer(token(subject)))
        .header("X-AyuVahak-Execution-Scope", "PLATFORM");
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return request;
  }

  private void createGrant(
      String idempotencyKey, String clientId, String authority, String scopeJson) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN)))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"principal\":{\"kind\":\"SERVICE\",\"issuer\":\""
                        + ISSUER
                        + "\",\"clientId\":\""
                        + clientId
                        + "\"},\"authority\":\""
                        + authority
                        + "\",\"scope\":"
                        + scopeJson
                        + ",\"reasonCode\":\"ACCESS_SETUP\"}"))
        .andExpect(status().isCreated());
  }

  private void createHumanGrant(
      String idempotencyKey, String subject, String authority, String scopeJson) throws Exception {
    var result =
        mockMvc
            .perform(
                post("/api/v1/platform/access-grants")
                    .header("Authorization", bearer(token(ADMIN)))
                    .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"principal\":{\"kind\":\"HUMAN\",\"issuer\":\""
                            + ISSUER
                            + "\",\"subject\":\""
                            + subject
                            + "\"},\"authority\":\""
                            + authority
                            + "\",\"scope\":"
                            + scopeJson
                            + ",\"reasonCode\":\"ACCESS_SETUP\"}"))
            .andReturn();
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isEqualTo(201);
  }

  private DirectoryFacility createReceivingFacility() throws Exception {
    String curator = "synthetic-directory-curator";
    createHumanGrant(
        "ips-directory-curator-grant", curator, "DirectoryCuration", "{\"kind\":\"PLATFORM\"}");
    MvcResult organizationResult =
        mockMvc
            .perform(
                post("/api/v1/directory/organizations")
                    .header("Authorization", bearer(token(curator)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", "ips-directory-organization")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"Synthetic IPS Receiving Organization\","
                            + "\"source\":{\"issuer\":\"urn:synthetic:directory-setup\","
                            + "\"resourceType\":\"Organization\",\"reference\":\"SYN-ORG-IPS-001\"},"
                            + "\"provenance\":{\"method\":\"MANUAL_REVIEW\","
                            + "\"observedAt\":\"2026-10-15T08:00:00Z\","
                            + "\"reasonCode\":\"INITIAL_ENTRY\"}}"))
            .andExpect(status().isCreated())
            .andReturn();
    String organizationId =
        com.jayway.jsonpath.JsonPath.read(
            organizationResult.getResponse().getContentAsString(), "$.id");
    MvcResult facilityResult =
        mockMvc
            .perform(
                post("/api/v1/directory/facilities")
                    .header("Authorization", bearer(token(curator)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", "ips-directory-facility")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"Synthetic IPS Receiving Facility\","
                            + "\"managingOrganizationId\":\""
                            + organizationId
                            + "\",\"source\":{\"issuer\":\"urn:synthetic:directory-setup\","
                            + "\"resourceType\":\"Facility\",\"reference\":\"SYN-FACILITY-IPS-001\"},"
                            + "\"provenance\":{\"method\":\"MANUAL_REVIEW\","
                            + "\"observedAt\":\"2026-10-15T08:00:00Z\","
                            + "\"reasonCode\":\"INITIAL_ENTRY\"}}"))
            .andExpect(status().isCreated())
            .andReturn();
    String facilityId =
        com.jayway.jsonpath.JsonPath.read(
            facilityResult.getResponse().getContentAsString(), "$.id");
    return new DirectoryFacility(organizationId, facilityId);
  }

  private MockHttpServletRequestBuilder directoryFeedRequest(String messageId, String body)
      throws Exception {
    return post("/api/v1/directory/mcsd/iti130/feed")
        .header("Authorization", bearer(serviceToken("synthetic-directory-feed")))
        .header("X-External-Message-ID", messageId)
        .contentType(MediaType.parseMediaType("application/fhir+json"))
        .content(body);
  }

  private String organizationFeedBundle(String name, String version) {
    return organizationFeedBundle("SYN-ORG-001", name, version);
  }

  private String organizationFeedBundle(String organizationId, String name, String version) {
    return "{\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":[{\"resource\":{"
        + "\"resourceType\":\"Organization\",\"id\":\""
        + organizationId
        + "\",\"meta\":{"
        + "\"profile\":[\""
        + MCSD_ORGANIZATION_PROFILE
        + "\"],\"versionId\":\""
        + version
        + "\",\"lastUpdated\":\"2026-10-15T10:00:00Z\"},"
        + "\"identifier\":[{\"system\":\"urn:ayuvahak:synthetic-orgs\",\"value\":\""
        + organizationId
        + "\"}],"
        + "\"active\":true,\"type\":[{\"coding\":[{\"system\":\"http://terminology.hl7.org/CodeSystem/organization-type\",\"code\":\"dept\"}]}],"
        + "\"name\":\""
        + name
        + "\"},\"request\":{\"method\":\"PUT\",\"url\":\"Organization/"
        + organizationId
        + "\"}}]}";
  }

  private String organizationDeleteFeedBundle(String organizationId, String expectedVersion) {
    Bundle bundle = new Bundle().setType(Bundle.BundleType.TRANSACTION);
    bundle
        .addEntry()
        .getRequest()
        .setMethod(Bundle.HTTPVerb.DELETE)
        .setUrl("Organization/" + organizationId)
        .setIfMatch("W/\"" + expectedVersion + "\"");
    return FhirContext.forR4Cached().newJsonParser().encodeResourceToString(bundle);
  }

  private static String fingerprint(String content) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private String createBody(String partnerKey, String basePath) {
    String configuration = configurationJson(basePath);
    return "{\"partnerKey\":\""
        + partnerKey
        + "\","
        + configuration.substring(1, configuration.length() - 1)
        + ",\"reasonCode\":\"PARTNER_SETUP\"}";
  }

  private String configurationJson(String basePath) {
    return "{\"organization\":{\"issuer\":\"urn:synthetic:directory\","
        + "\"identifier\":\"SYN-ORG-001\"},"
        + "\"endpointOrigin\":\"https://directory.example.test\","
        + "\"basePath\":\""
        + basePath
        + "\",\"authentication\":{\"method\":\"OAUTH_PRIVATE_KEY_JWT\","
        + "\"credentialReference\":\""
        + SECRET_REFERENCE
        + "\",\"tokenSigningKeysReference\":\"env:SYNTHETIC_PARTNER_TOKEN_JWKS\","
        + "\"tokenOrigin\":\"https://auth.example.test\","
        + "\"tokenPath\":\"/oauth/token\",\"issuer\":\"https://auth.example.test/issuer\","
        + "\"audience\":\"ayuvahak-external\",\"clientId\":\"synthetic-client\"},"
        + "\"tlsPolicy\":\"SYSTEM_CA\",\"tlsTrustReference\":null,"
        + "\"limits\":{\"connectTimeoutMillis\":1000,\"responseTimeoutMillis\":2000,"
        + "\"operationTimeoutMillis\":5000,\"maxResponseBytes\":1000000,"
        + "\"maxDecompressedBytes\":2000000,\"maxPages\":10,\"maxResources\":500,"
        + "\"maxNestingDepth\":32,\"maxAttempts\":3},\"retryClass\":\"IDEMPOTENT_READ\","
        + "\"allowedOperations\":[{\"ownerContext\":\"DIRECTORY\","
        + "\"operationCode\":\"mcsd.iti90.search\",\"direction\":\"OUTBOUND\","
        + "\"protocol\":\"FHIR_R4_JSON\",\"profile\":\"IHE-mCSD\"}]}";
  }

  private String inboundFeedConfigurationJson() {
    return inboundFeedConfigurationJson("https://directory.example.test/issuer", "SYN-ORG-001");
  }

  private String inboundFeedConfigurationJson(String issuer, String identifier) {
    return "{\"organization\":{\"issuer\":\""
        + issuer
        + "\",\"identifier\":\""
        + identifier
        + "\"},"
        + "\"endpointOrigin\":\"https://directory.example.test\",\"basePath\":\"/fhir\","
        + "\"authentication\":{\"method\":\"NONE\"},\"tlsPolicy\":\"SYSTEM_CA\","
        + "\"limits\":{\"connectTimeoutMillis\":1000,\"responseTimeoutMillis\":2000,"
        + "\"operationTimeoutMillis\":5000,\"maxResponseBytes\":1000000,"
        + "\"maxDecompressedBytes\":2000000,\"maxPages\":10,\"maxResources\":500,"
        + "\"maxNestingDepth\":32,\"maxAttempts\":1},\"retryClass\":\"NEVER\","
        + "\"allowedOperations\":[{\"ownerContext\":\"DIRECTORY\","
        + "\"operationCode\":\"mcsd.iti130.feed\",\"direction\":\"INBOUND\","
        + "\"protocol\":\"FHIR_R4_JSON\",\"profile\":\"IHE-mCSD\"}],"
        + "\"inboundPrincipal\":{\"issuer\":\""
        + ISSUER
        + "\",\"clientId\":\"synthetic-directory-feed\"}}";
  }

  private String transportProviderConfigurationJson() {
    return "{\"organization\":{\"issuer\":\"urn:synthetic:transport-provider\","
        + "\"identifier\":\"SYN-PROVIDER-001\"},"
        + "\"endpointOrigin\":\"https://provider.example.test\",\"basePath\":\"/api\","
        + "\"authentication\":{\"method\":\"NONE\"},\"tlsPolicy\":\"SYSTEM_CA\","
        + "\"limits\":{\"connectTimeoutMillis\":1000,\"responseTimeoutMillis\":2000,"
        + "\"operationTimeoutMillis\":5000,\"maxResponseBytes\":262144,"
        + "\"maxDecompressedBytes\":524288,\"maxPages\":1,\"maxResources\":20,"
        + "\"maxNestingDepth\":16,\"maxAttempts\":1},\"retryClass\":\"NEVER\","
        + "\"allowedOperations\":["
        + "{\"ownerContext\":\"TRANSPORT\",\"operationCode\":\"transport.dispatch\","
        + "\"direction\":\"OUTBOUND\",\"protocol\":\"JSON\",\"profile\":\"AyuVahakTransportProvider-v1\"},"
        + "{\"ownerContext\":\"TRANSPORT\",\"operationCode\":\"transport.request-cancellation\","
        + "\"direction\":\"OUTBOUND\",\"protocol\":\"JSON\",\"profile\":\"AyuVahakTransportProvider-v1\"},"
        + "{\"ownerContext\":\"TRANSPORT\",\"operationCode\":\"transport.destination-change\","
        + "\"direction\":\"OUTBOUND\",\"protocol\":\"JSON\",\"profile\":\"AyuVahakTransportProvider-v1\"},"
        + "{\"ownerContext\":\"TRANSPORT\",\"operationCode\":\"transport.provider.evidence\","
        + "\"direction\":\"INBOUND\",\"protocol\":\"JSON\",\"profile\":\"AyuVahakTransportProvider-v1\"}],"
        + "\"inboundPrincipal\":{\"issuer\":\""
        + ISSUER
        + "\",\"clientId\":\"synthetic-transport-provider\"}}";
  }

  private void activateLocalTransportProvider() throws Exception {
    String configuration =
        localTransportProviderConfigurationJson(
            "https://partner.test:" + mcsdServer.getAddress().getPort());
    String create =
        "{\"partnerKey\":\"synthetic-transport-provider\","
            + configuration.substring(1, configuration.length() - 1)
            + ",\"reasonCode\":\"PARTNER_SETUP\"}";
    mockMvc
        .perform(
            partnerRequest(
                post("/api/v1/platform/exchange-partners"),
                PARTNER_ADMIN,
                "local-transport-provider-create",
                create))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            partnerRequest(
                post(
                    "/api/v1/platform/exchange-partners/synthetic-transport-provider/versions/1/activate"),
                PARTNER_ADMIN,
                "local-transport-provider-activate",
                "{\"reasonCode\":\"PARTNER_ACTIVATION\"}"))
        .andExpect(status().isOk());
  }

  private String localTransportProviderConfigurationJson(String endpointOrigin) {
    return "{\"organization\":{\"issuer\":\"urn:synthetic:transport-provider\","
        + "\"identifier\":\"SYN-PROVIDER-001\"},"
        + "\"endpointOrigin\":\""
        + endpointOrigin
        + "\",\"basePath\":\"/api\","
        + "\"authentication\":{\"method\":\"NONE\"},\"tlsPolicy\":\"SYSTEM_CA\","
        + "\"limits\":{\"connectTimeoutMillis\":1000,\"responseTimeoutMillis\":2000,"
        + "\"operationTimeoutMillis\":5000,\"maxResponseBytes\":262144,"
        + "\"maxDecompressedBytes\":524288,\"maxPages\":1,\"maxResources\":20,"
        + "\"maxNestingDepth\":16,\"maxAttempts\":1},\"retryClass\":\"NEVER\","
        + "\"allowedOperations\":["
        + "{\"ownerContext\":\"TRANSPORT\",\"operationCode\":\"transport.dispatch\","
        + "\"direction\":\"OUTBOUND\",\"protocol\":\"JSON\",\"profile\":\"AyuVahakTransportProvider-v1\"},"
        + "{\"ownerContext\":\"TRANSPORT\",\"operationCode\":\"transport.request-cancellation\","
        + "\"direction\":\"OUTBOUND\",\"protocol\":\"JSON\",\"profile\":\"AyuVahakTransportProvider-v1\"},"
        + "{\"ownerContext\":\"TRANSPORT\",\"operationCode\":\"transport.destination-change\","
        + "\"direction\":\"OUTBOUND\",\"protocol\":\"JSON\",\"profile\":\"AyuVahakTransportProvider-v1\"},"
        + "{\"ownerContext\":\"TRANSPORT\",\"operationCode\":\"transport.provider.evidence\","
        + "\"direction\":\"INBOUND\",\"protocol\":\"JSON\",\"profile\":\"AyuVahakTransportProvider-v1\"}],"
        + "\"inboundPrincipal\":{\"issuer\":\""
        + ISSUER
        + "\",\"clientId\":\"synthetic-transport-provider\"}}";
  }

  private static String facilityScopeJson(InternalId organizationId, InternalId facilityId) {
    return "{\"kind\":\"FACILITY\",\"organization\":{\"issuer\":\""
        + DIRECTORY_ISSUER
        + "\",\"identifier\":\""
        + organizationId
        + "\"},\"facility\":{\"issuer\":\""
        + DIRECTORY_ISSUER
        + "\",\"identifier\":\""
        + facilityId
        + "\"}}";
  }

  private static org.ayuvahak.transport.domain.TransportRequest syntheticTransportRequest(
      InternalId requestId,
      InternalId transferId,
      InternalId organizationId,
      InternalId facilityId) {
    var operator = ActorReference.human(new IssuerId(ISSUER), "synthetic-transport-operator");
    var transportWorker = ActorReference.service(new IssuerId(ISSUER), "ayuvahak-transport-worker");
    return new org.ayuvahak.transport.domain.TransportRequest(
        requestId,
        transferId,
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facilityId.toString()),
        new ExternalReference(
            new IssuerId(DIRECTORY_ISSUER), "Facility", "20000000-0000-4000-8000-000000000005"),
        operator,
        transportWorker,
        org.ayuvahak.transport.domain.TransportRequest.Status.REQUESTED,
        Instant.parse("2026-10-15T10:00:00Z"),
        new CorrelationId("synthetic-transport-provider-request"),
        1);
  }

  private String localOutboundDirectoryConfigurationJson() {
    return "{\"organization\":{\"issuer\":\"urn:synthetic:directory\","
        + "\"identifier\":\"SYN-ORG-001\"},"
        + "\"endpointOrigin\":\"https://partner.test:"
        + mcsdServer.getAddress().getPort()
        + "\",\"basePath\":\"/fhir\","
        + "\"authentication\":{\"method\":\"NONE\"},"
        + "\"tlsPolicy\":\"SYSTEM_CA\",\"tlsTrustReference\":null,"
        + "\"limits\":{\"connectTimeoutMillis\":1000,\"responseTimeoutMillis\":2000,"
        + "\"operationTimeoutMillis\":5000,\"maxResponseBytes\":1000000,"
        + "\"maxDecompressedBytes\":2000000,\"maxPages\":10,\"maxResources\":500,"
        + "\"maxNestingDepth\":32,\"maxAttempts\":3},"
        + "\"retryClass\":\"IDEMPOTENT_READ\","
        + "\"allowedOperations\":[{\"ownerContext\":\"DIRECTORY\","
        + "\"operationCode\":\"mcsd.iti90.search\",\"direction\":\"OUTBOUND\","
        + "\"protocol\":\"FHIR_R4_JSON\",\"profile\":\"IHE-mCSD\"}]}";
  }

  private String localIpsConfigurationJson() {
    return "{\"organization\":{\"issuer\":\"urn:synthetic:clinical-source\","
        + "\"identifier\":\"SYN-ORG-IPS-001\"},"
        + "\"endpointOrigin\":\"https://partner.test:"
        + mcsdServer.getAddress().getPort()
        + "\",\"basePath\":\"/fhir\","
        + "\"authentication\":{\"method\":\"NONE\"},"
        + "\"tlsPolicy\":\"SYSTEM_CA\",\"tlsTrustReference\":null,"
        + "\"limits\":{\"connectTimeoutMillis\":1000,\"responseTimeoutMillis\":2000,"
        + "\"operationTimeoutMillis\":5000,\"maxResponseBytes\":1000000,"
        + "\"maxDecompressedBytes\":2000000,\"maxPages\":10,\"maxResources\":500,"
        + "\"maxNestingDepth\":32,\"maxAttempts\":3},"
        + "\"retryClass\":\"IDEMPOTENT_READ\","
        + "\"allowedOperations\":[{\"ownerContext\":\"REFERRAL\","
        + "\"operationCode\":\"ips.patient-context.read\",\"direction\":\"OUTBOUND\","
        + "\"protocol\":\"FHIR_R4_JSON\",\"profile\":\"IPS-2.0.1\"}]}";
  }

  private static byte[] mcsdOrganizationSearchBundle() {
    Organization organization = new Organization();
    organization.setId("SYN-ORG-MCSD-OUTBOUND-001");
    organization.setActive(true);
    organization.setName("Synthetic Outbound Directory Authority");
    organization
        .addType()
        .addCoding()
        .setSystem("http://terminology.hl7.org/CodeSystem/organization-type")
        .setCode("dept");
    Meta meta = new Meta();
    meta.addProfile(MCSD_ORGANIZATION_PROFILE);
    meta.setVersionId("1");
    meta.setLastUpdated(Date.from(Instant.parse("2026-10-15T10:00:00Z")));
    organization.setMeta(meta);

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.SEARCHSET);
    bundle.setTimestamp(Date.from(Instant.parse("2026-10-15T10:00:00Z")));
    bundle
        .addEntry()
        .setFullUrl("urn:uuid:00000000-0000-4000-8000-000000000001")
        .setResource(organization)
        .setSearch(new Bundle.BundleEntrySearchComponent().setMode(Bundle.SearchEntryMode.MATCH));
    return FhirContext.forR4Cached()
        .newJsonParser()
        .encodeResourceToString(bundle)
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] syntheticIpsDocument(String patientSystem, String patientIdentifier) {
    String patientUrl = "urn:uuid:00000000-0000-4000-8000-000000000002";
    String conditionUrl = "urn:uuid:00000000-0000-4000-8000-000000000003";
    Instant timestamp = Instant.parse("2026-10-15T08:00:00Z");

    Bundle bundle = new Bundle();
    bundle.getMeta().addProfile(IPS_BUNDLE_PROFILE);
    bundle.setIdentifier(
        new Identifier().setSystem("urn:synthetic:ips-documents").setValue("SYN-IPS-001"));
    bundle.setType(Bundle.BundleType.DOCUMENT);
    bundle.setTimestamp(Date.from(timestamp));

    Patient patient = new Patient();
    patient.setId("00000000-0000-4000-8000-000000000002");
    patient.getMeta().addProfile(IPS_PATIENT_PROFILE);
    patient.addIdentifier(new Identifier().setSystem(patientSystem).setValue(patientIdentifier));
    patient.addName().setText("SYNTHETIC PERSON — NOT REAL");
    patient.setBirthDate(Date.from(Instant.parse("2000-01-01T00:00:00Z")));

    Condition condition = new Condition();
    condition.setId("00000000-0000-4000-8000-000000000003");
    condition.getMeta().addProfile(IPS_CONDITION_PROFILE);
    condition.setSubject(new Reference(patientUrl));
    condition.setCode(
        new CodeableConcept()
            .addCoding(
                new Coding(
                    "urn:synthetic:clinical", "SYN-CODED-PROBLEM-001", "Synthetic problem")));

    Composition composition = new Composition();
    composition.getMeta().addProfile(IPS_COMPOSITION_PROFILE);
    composition.setStatus(Composition.CompositionStatus.FINAL);
    composition.setType(
        new CodeableConcept()
            .addCoding(new Coding("http://loinc.org", "60591-5", "Patient summary")));
    composition.setSubject(new Reference(patientUrl));
    composition.setDate(Date.from(timestamp));
    composition
        .addEvent()
        .addCode(
            new CodeableConcept()
                .addCoding(
                    new Coding(
                        "http://terminology.hl7.org/CodeSystem/v3-ActClass",
                        "PCPR",
                        "care provision")));
    composition.addAuthor().setDisplay("Synthetic IPS author");
    composition.setTitle("Synthetic patient summary");
    Narrative narrative = new Narrative();
    narrative.setStatus(Narrative.NarrativeStatus.GENERATED);
    narrative.setDivAsString(
        "<div xmlns=\"http://www.w3.org/1999/xhtml\">SYN-PRIVATE-NARRATIVE-CONTENT</div>");
    composition
        .addSection()
        .setTitle("Problems")
        .setCode(
            new CodeableConcept()
                .addCoding(new Coding("http://loinc.org", "11450-4", "Problem list")))
        .setText(narrative)
        .addEntry(new Reference(conditionUrl));
    composition
        .addSection()
        .setTitle("Allergies and intolerances")
        .setCode(
            new CodeableConcept().addCoding(new Coding("http://loinc.org", "48765-2", "Allergies")))
        .setText(syntheticNarrative("No synthetic allergy records."))
        .setEmptyReason(
            new CodeableConcept()
                .addCoding(
                    new Coding(
                        "http://terminology.hl7.org/CodeSystem/list-empty-reason",
                        "unavailable",
                        "Unavailable")));
    composition
        .addSection()
        .setTitle("Medication summary")
        .setCode(
            new CodeableConcept()
                .addCoding(new Coding("http://loinc.org", "10160-0", "Medication summary")))
        .setText(syntheticNarrative("No synthetic medication records."))
        .setEmptyReason(
            new CodeableConcept()
                .addCoding(
                    new Coding(
                        "http://terminology.hl7.org/CodeSystem/list-empty-reason",
                        "unavailable",
                        "Unavailable")));

    bundle
        .addEntry()
        .setFullUrl("urn:uuid:00000000-0000-4000-8000-000000000001")
        .setResource(composition);
    bundle.addEntry().setFullUrl(patientUrl).setResource(patient);
    bundle.addEntry().setFullUrl(conditionUrl).setResource(condition);
    return FhirContext.forR4Cached()
        .newJsonParser()
        .encodeResourceToString(bundle)
        .getBytes(StandardCharsets.UTF_8);
  }

  private static Narrative syntheticNarrative(String content) {
    Narrative narrative = new Narrative();
    narrative.setStatus(Narrative.NarrativeStatus.GENERATED);
    narrative.setDivAsString("<div xmlns=\"http://www.w3.org/1999/xhtml\">" + content + "</div>");
    return narrative;
  }

  private BootstrapGrantProperties bootstrapProperties() {
    return new BootstrapGrantProperties(
        "urn:ayuvahak:local-operator",
        "synthetic-bootstrap-operator",
        ISSUER,
        ActorKind.HUMAN.name(),
        ADMIN,
        "PLATFORM",
        "CREATE_FIRST_ACCESS_GRANT");
  }

  private String token(String subject) throws Exception {
    return token(subject, null);
  }

  private String serviceToken(String clientId) throws Exception {
    return token(clientId, clientId);
  }

  private String token(String subject, String clientId) throws Exception {
    JWTClaimsSet.Builder claimsBuilder =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject(subject)
            .audience(AUDIENCE)
            .issueTime(Date.from(Instant.now().minusSeconds(5)))
            .notBeforeTime(Date.from(Instant.now().minusSeconds(1)))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim("typ", "Bearer");
    if (clientId != null) {
      claimsBuilder.claim("client_id", clientId);
    }
    JWTClaimsSet claims = claimsBuilder.build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(SIGNING_KEY.getKeyID())
                .build(),
            claims);
    jwt.sign(new RSASSASigner(SIGNING_KEY));
    return jwt.serialize();
  }

  private static RSAKey newSigningKey(String keyId) {
    try {
      return new RSAKeyGenerator(2048).keyID(keyId).generate();
    } catch (Exception exception) {
      throw new IllegalStateException("Could not create synthetic signing key.", exception);
    }
  }

  private static String bearer(String value) {
    return "Bearer " + value;
  }

  private int count(String sql, Object... arguments) {
    return jdbc.queryForObject(sql, Integer.class, arguments);
  }

  private record DirectoryFacility(String organizationId, String facilityId) {}
}
