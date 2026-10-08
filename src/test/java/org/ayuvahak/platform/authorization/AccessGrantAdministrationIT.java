package org.ayuvahak.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.platform.authorization.adapter.api.PlatformAccessGrantController;
import org.ayuvahak.platform.authorization.adapter.cli.BootstrapGrantApplication;
import org.ayuvahak.platform.authorization.application.BootstrapGrantProperties;
import org.ayuvahak.platform.authorization.application.BootstrapGrantService;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    properties = {
      "management.tracing.sampling.probability=0.0",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-worker",
      "ayuvahak.authentication.allow-http=true"
    })
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Import(AccessGrantAdministrationIT.DirectoryGrantScopeFixtureConfiguration.class)
class AccessGrantAdministrationIT {

  private static final String TEST_PASSWORD = "local-test-only";
  private static final String ISSUER = "https://identity.example.test/realms/ayuvahak";
  private static final String AUDIENCE = "ayuvahak-api";
  private static final String EXECUTION_SCOPE = "PLATFORM";
  private static final String LOCAL_OPERATOR_ISSUER = "urn:ayuvahak:local-operator";
  private static final String ADMIN_SUBJECT = "synthetic-admin-17";
  private static final RSAKey SIGNING_KEY = newSigningKey("grant-admin-fixture");
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(TEST_PASSWORD);
  private static HttpServer jwksServer;

  @Container private static final PostgreSQLContainer CONTAINER = POSTGRES;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private BootstrapGrantService bootstrapService;
  @Autowired private FunctionalAuthorityCatalog authorities;
  @Autowired private ExecutionContextFactory executionContexts;

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
  }

  @AfterAll
  static void stopLocalJwksServer() {
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
  }

  @BeforeEach
  void clearLocalGrantState() {
    jdbc.update("DELETE FROM identity_access.grant_command_receipt");
    jdbc.update("DELETE FROM identity_access.bootstrap_marker");
    jdbc.update("DELETE FROM audit.audit_record");
    jdbc.update("DELETE FROM platform_eventing.outbox_delivery");
    jdbc.update("DELETE FROM platform_eventing.internal_event");
    jdbc.update("DELETE FROM identity_access.authorization_grant");
    jdbc.execute("DROP TRIGGER IF EXISTS test_fail_audit_insert ON audit.audit_record");
    jdbc.execute("DROP FUNCTION IF EXISTS test_fail_audit_insert()");
    jdbc.execute(
        "DROP TRIGGER IF EXISTS test_fail_event_insert ON platform_eventing.internal_event");
    jdbc.execute("DROP FUNCTION IF EXISTS test_fail_event_insert()");
  }

  @Test
  void provisionsFirstGrantThroughOneShotNonWebCommandAndRefusesASecondRun() {
    assertThat(BootstrapGrantApplication.application().getWebApplicationType())
        .isEqualTo(WebApplicationType.NONE);

    try (var context = BootstrapGrantApplication.application().run(bootstrapArguments())) {
      assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
    }

    assertThat(grantCount()).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM identity_access.bootstrap_marker")).isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'BOOTSTRAP_INITIAL_ACCESS'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM platform_eventing.internal_event WHERE event_type = 'identity-access.grant-created.v1'"))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT authentication_evidence FROM audit.audit_record WHERE action_code = 'BOOTSTRAP_INITIAL_ACCESS'",
                String.class))
        .isEqualTo("LOCAL_OPERATOR_CONTROL");

    assertThatThrownBy(
            () -> {
              try (var ignored =
                  BootstrapGrantApplication.application().run(bootstrapArguments())) {
                // A successful second invocation is a test failure.
              }
            })
        .isInstanceOf(org.ayuvahak.platform.api.PlatformProblemException.class)
        .hasMessage(
            "Initial access has already been established; use normal grant administration.");
    assertThat(grantCount()).isEqualTo(1);
  }

  @Test
  void createsListsRevokesAndReplaysGrantCommandsAtTheProtectedApi() throws Exception {
    bootstrapService.provisionFirstGrant(bootstrapProperties(), "synthetic-host");

    String createBody =
        "{\"principal\":{\"kind\":\"HUMAN\",\"issuer\":\""
            + ISSUER
            + "\",\"subject\":\"synthetic-coordinator-31\"},"
            + "\"authority\":\"AuditReview\",\"scope\":{\"kind\":\"PLATFORM\"},"
            + "\"reasonCode\":\"ACCESS_SETUP\"}";
    var created =
        mockMvc
            .perform(
                post("/api/v1/platform/access-grants")
                    .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                    .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                    .header("Idempotency-Key", "grant-create-01")
                    .contentType("application/json")
                    .content(createBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.grantId").isNotEmpty())
            .andReturn();
    String grantId =
        com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.grantId");
    assertThat(created.getResponse().getHeader("Location"))
        .isEqualTo("/api/v1/platform/access-grants/" + grantId);

    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                .header("Idempotency-Key", "grant-create-01")
                .contentType("application/json")
                .content(createBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.grantId").value(grantId));
    assertThat(grantCount()).isEqualTo(2);

    mockMvc
        .perform(
            get("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[*].grantId", org.hamcrest.Matchers.hasItem(grantId)))
        .andExpect(
            jsonPath(
                "$.items[*].subject", org.hamcrest.Matchers.hasItem("synthetic-coordinator-31")));

    String deleteBody = "{\"reasonCode\":\"ACCESS_REMOVAL\"}";
    mockMvc
        .perform(
            delete("/api/v1/platform/access-grants/" + grantId)
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                .header("Idempotency-Key", "grant-revoke-01")
                .contentType("application/json")
                .content(deleteBody))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            delete("/api/v1/platform/access-grants/" + grantId)
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                .header("Idempotency-Key", "grant-revoke-01")
                .contentType("application/json")
                .content(deleteBody))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            delete("/api/v1/platform/access-grants/" + grantId)
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                .header("Idempotency-Key", "grant-revoke-02")
                .contentType("application/json")
                .content(deleteBody))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("GRANT_NOT_ACTIVE"));
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'ACCESS_GRANT_REVOKE'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM platform_eventing.internal_event WHERE event_type = 'identity-access.grant-revoked.v1'"))
        .isEqualTo(1);
  }

  @Test
  void rejectsReusedKeysSelfGrantAndMissingScopeWithDurableAudit() throws Exception {
    bootstrapService.provisionFirstGrant(bootstrapProperties(), "synthetic-host");
    String body =
        "{\"principal\":{\"kind\":\"HUMAN\",\"issuer\":\""
            + ISSUER
            + "\",\"subject\":\"synthetic-coordinator-32\"},"
            + "\"authority\":\"AuditReview\",\"scope\":{\"kind\":\"PLATFORM\"},"
            + "\"reasonCode\":\"ACCESS_SETUP\"}";
    var first =
        mockMvc
            .perform(
                post("/api/v1/platform/access-grants")
                    .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                    .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                    .header("Idempotency-Key", "scope-key-01")
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    String changedBody = body.replace("ACCESS_SETUP", "OTHER");
    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                .header("Idempotency-Key", "scope-key-01")
                .contentType("application/json")
                .content(changedBody))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    assertThat(grantCount()).isEqualTo(2);

    String selfBody =
        "{\"principal\":{\"kind\":\"HUMAN\",\"issuer\":\""
            + ISSUER
            + "\",\"subject\":\""
            + ADMIN_SUBJECT
            + "\"},\"authority\":\"DirectoryRead\",\"scope\":{"
            + "\"kind\":\"ORGANIZATION\",\"organization\":{"
            + "\"issuer\":\"urn:ayuvahak:directory\",\"identifier\":\"org-17\"}},"
            + "\"reasonCode\":\"ACCESS_SETUP\"}";
    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                .header("Idempotency-Key", "self-grant-01")
                .contentType("application/json")
                .content(selfBody))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));

    java.util.UUID administratorGrantId =
        jdbc.queryForObject(
            "SELECT grant_id FROM identity_access.authorization_grant "
                + "WHERE principal_identifier = ? "
                + "AND authority_code = 'AccessGrantAdministration'",
            java.util.UUID.class,
            ADMIN_SUBJECT);
    mockMvc
        .perform(
            delete("/api/v1/platform/access-grants/" + administratorGrantId)
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                .header("Idempotency-Key", "self-revoke-01")
                .contentType("application/json")
                .content("{\"reasonCode\":\"ACCESS_REMOVAL\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));

    mockMvc
        .perform(
            get("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of()))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_SCOPE"));
    assertThat(count("SELECT COUNT(*) FROM audit.audit_record WHERE outcome = 'DENIED'"))
        .isEqualTo(3);
    assertThat(first.getResponse().getContentAsString()).contains("grantId");
  }

  @Test
  void protectedCommandCorrelationIsPreservedInItsAuditAndEvent() throws Exception {
    bootstrapService.provisionFirstGrant(bootstrapProperties(), "synthetic-host");
    String correlationId = "grant-request-correlation-13";
    String body =
        "{\"principal\":{\"kind\":\"HUMAN\",\"issuer\":\""
            + ISSUER
            + "\",\"subject\":\"synthetic-coordinator-34\"},"
            + "\"authority\":\"AuditReview\",\"scope\":{\"kind\":\"PLATFORM\"},"
            + "\"reasonCode\":\"ACCESS_SETUP\"}";

    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                .header("X-Correlation-ID", correlationId)
                .header("Idempotency-Key", "correlated-grant-create-13")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("X-Correlation-ID", correlationId));

    assertThat(
            jdbc.queryForObject(
                "SELECT correlation_id FROM audit.audit_record "
                    + "WHERE action_code = 'ACCESS_GRANT_CREATE'",
                String.class))
        .isEqualTo(correlationId);
    assertThat(
            jdbc.queryForObject(
                "SELECT envelope ->> 'correlationId' FROM platform_eventing.internal_event "
                    + "WHERE event_type = 'identity-access.grant-created.v1' "
                    + "AND envelope ->> 'correlationId' = ?",
                String.class,
                correlationId))
        .isEqualTo(correlationId);
  }

  @Test
  void rollsBackGrantAuditEventAndReceiptWhenOutboxPersistenceFails() throws Exception {
    bootstrapService.provisionFirstGrant(bootstrapProperties(), "synthetic-host");
    jdbc.execute(
        "CREATE FUNCTION test_fail_event_insert() RETURNS trigger LANGUAGE plpgsql AS "
            + "'BEGIN RAISE EXCEPTION ''synthetic event failure''; END'");
    jdbc.execute(
        "CREATE TRIGGER test_fail_event_insert BEFORE INSERT ON platform_eventing.internal_event "
            + "FOR EACH ROW EXECUTE FUNCTION test_fail_event_insert()");
    String body =
        "{\"principal\":{\"kind\":\"HUMAN\",\"issuer\":\""
            + ISSUER
            + "\",\"subject\":\"synthetic-coordinator-33\"},"
            + "\"authority\":\"AuditReview\",\"scope\":{\"kind\":\"PLATFORM\"},"
            + "\"reasonCode\":\"ACCESS_SETUP\"}";

    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                .header("Idempotency-Key", "outbox-failure-01")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("EVENT_PUBLICATION_UNAVAILABLE"));

    assertThat(grantCount()).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM identity_access.grant_command_receipt")).isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'ACCESS_GRANT_CREATE'"))
        .isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM platform_eventing.internal_event WHERE event_type = 'identity-access.grant-created.v1'"))
        .isEqualTo(1);
  }

  @Test
  void provisionsOnlyRegisteredAuthoritiesAtValidatedTargetScope() throws Exception {
    bootstrapService.provisionFirstGrant(bootstrapProperties(), "synthetic-host");

    assertThat(authorities.find("DirectoryRead")).isPresent();
    assertThat(authorities.find("DirectoryCuration")).isPresent();
    assertThat(authorities.find("DirectoryVocabularyAdministration")).isPresent();

    String organizationBody = directoryGrantBody("synthetic-directory-reader-01", "DirectoryRead");
    var created =
        mockMvc
            .perform(
                post("/api/v1/platform/access-grants")
                    .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                    .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                    .header("Idempotency-Key", "directory-org-grant-01")
                    .contentType("application/json")
                    .content(organizationBody))
            .andExpect(status().isCreated())
            .andReturn();
    String grantId =
        com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.grantId");

    assertThat(
            jdbc.queryForObject(
                "SELECT scope_kind || ':' || organization_identifier "
                    + "FROM identity_access.authorization_grant WHERE grant_id = ?",
                String.class,
                java.util.UUID.fromString(grantId)))
        .isEqualTo("ORGANIZATION:org-17");
    assertThat(
            jdbc.queryForObject(
                "SELECT target_scope_kind || ':' || target_organization_reference "
                    + "FROM audit.audit_record WHERE action_code = 'ACCESS_GRANT_CREATE'",
                String.class))
        .isEqualTo("ORGANIZATION:org-17");
    assertThat(
            jdbc.queryForObject(
                "SELECT scope_kind FROM audit.audit_record "
                    + "WHERE action_code = 'ACCESS_GRANT_CREATE'",
                String.class))
        .isEqualTo("PLATFORM");
    assertThat(
            jdbc.queryForObject(
                "SELECT envelope -> 'payload' ->> 'scopeKind' "
                    + "FROM platform_eventing.internal_event "
                    + "WHERE event_type = 'identity-access.grant-created.v1' "
                    + "AND envelope -> 'payload' ->> 'grantId' = ?",
                String.class,
                grantId))
        .isEqualTo("ORGANIZATION");

    String facilityBody =
        "{\"principal\":{\"kind\":\"HUMAN\",\"issuer\":\""
            + ISSUER
            + "\",\"subject\":\"synthetic-facility-reader-01\"},"
            + "\"authority\":\"DirectoryCuration\",\"scope\":{\"kind\":\"FACILITY\","
            + "\"organization\":{\"issuer\":\"urn:ayuvahak:directory\","
            + "\"identifier\":\"org-17\"},\"facility\":{"
            + "\"issuer\":\"urn:ayuvahak:directory\",\"identifier\":\"facility-23\"}},"
            + "\"reasonCode\":\"ACCESS_SETUP\"}";
    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                .header("Idempotency-Key", "directory-facility-grant-01")
                .contentType("application/json")
                .content(facilityBody))
        .andExpect(status().isCreated());
    assertThat(
            jdbc.queryForObject(
                "SELECT target_scope_kind || ':' || target_organization_reference || ':' "
                    + "|| target_facility_reference FROM audit.audit_record "
                    + "WHERE action_code = 'ACCESS_GRANT_CREATE' "
                    + "AND target_scope_kind = 'FACILITY'",
                String.class))
        .isEqualTo("FACILITY:org-17:facility-23");

    mockMvc
        .perform(
            get("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].grantId", org.hamcrest.Matchers.hasItem(grantId)))
        .andExpect(jsonPath("$.items[*].scope", org.hamcrest.Matchers.hasItem("ORGANIZATION")))
        .andExpect(
            jsonPath("$.items[*].organizationIdentifier", org.hamcrest.Matchers.hasItem("org-17")));

    mockMvc
        .perform(
            delete("/api/v1/platform/access-grants/" + grantId)
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                .header("Idempotency-Key", "directory-org-revoke-01")
                .contentType("application/json")
                .content("{\"reasonCode\":\"ACCESS_REMOVAL\"}"))
        .andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject(
                "SELECT target_scope_kind || ':' || target_organization_reference "
                    + "FROM audit.audit_record WHERE action_code = 'ACCESS_GRANT_REVOKE'",
                String.class))
        .isEqualTo("ORGANIZATION:org-17");
    assertThat(
            jdbc.queryForObject(
                "SELECT envelope -> 'payload' ->> 'scopeKind' "
                    + "FROM platform_eventing.internal_event "
                    + "WHERE event_type = 'identity-access.grant-revoked.v1' "
                    + "AND envelope -> 'payload' ->> 'grantId' = ?",
                String.class,
                grantId))
        .isEqualTo("ORGANIZATION");

    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                .header("Idempotency-Key", "directory-org-grant-01")
                .contentType("application/json")
                .content(organizationBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.grantId").value(grantId));

    String changedScope = organizationBody.replace("org-17", "org-18");
    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                .header("Idempotency-Key", "directory-org-grant-01")
                .contentType("application/json")
                .content(changedScope))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                .header("Idempotency-Key", "invalid-directory-target-01")
                .contentType("application/json")
                .content(organizationBody.replace("org-17", "org-99")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_SCOPE"));
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'ACCESS_GRANT_CREATE' AND outcome = 'DENIED' "
                    + "AND reason_code = 'INVALID_GRANT_TARGET_SCOPE'"))
        .isEqualTo(1);

    String forbiddenAuthorityScope =
        directoryGrantBody("synthetic-vocabulary-admin-01", "DirectoryVocabularyAdministration");
    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN_SUBJECT, Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                .header("Idempotency-Key", "directory-vocab-org-01")
                .contentType("application/json")
                .content(forbiddenAuthorityScope))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_SCOPE"));
  }

  @Test
  void executionContextFactoryAcceptsOnlyDirectoryValidatedOrganizationAndFacilityScopes() {
    var actor = ActorReference.human(new IssuerId(ISSUER), "synthetic-context-operator-01");
    var organization =
        new ExternalReference(new IssuerId("urn:ayuvahak:directory"), "Organization", "org-17");
    var facility =
        new ExternalReference(new IssuerId("urn:ayuvahak:directory"), "Facility", "facility-23");

    var organizationContext =
        executionContexts.forInteractiveRequest(
            actor,
            RequestedExecutionScope.organization(organization),
            new CorrelationId("directory-context-organization-01"));
    var facilityContext =
        executionContexts.forInteractiveRequest(
            actor,
            RequestedExecutionScope.facility(organization, facility),
            new CorrelationId("directory-context-facility-01"));

    assertThat(organizationContext.scope().kind())
        .isEqualTo(org.ayuvahak.platform.executioncontext.ExecutionScope.Kind.ORGANIZATION);
    assertThat(facilityContext.scope().kind())
        .isEqualTo(org.ayuvahak.platform.executioncontext.ExecutionScope.Kind.FACILITY);
    assertThatThrownBy(
            () ->
                executionContexts.forInteractiveRequest(
                    actor,
                    RequestedExecutionScope.facility(
                        organization,
                        new ExternalReference(
                            new IssuerId("urn:ayuvahak:directory"),
                            "Facility",
                            "facility-not-in-org")),
                    new CorrelationId("directory-context-invalid-facility-01")))
        .isInstanceOf(org.ayuvahak.platform.executioncontext.ExecutionScopeDeniedException.class);
  }

  @Test
  void unauthorizedActorCannotUseTheGrantAdministrationBoundary() throws Exception {
    bootstrapService.provisionFirstGrant(bootstrapProperties(), "synthetic-host");

    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token("synthetic-ungranted-actor", Map.of())))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, EXECUTION_SCOPE)
                .header("Idempotency-Key", "unauthorized-directory-grant-01")
                .contentType("application/json")
                .content(directoryGrantBody("synthetic-reader-02", "DirectoryRead")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));
  }

  private String directoryGrantBody(String subject, String authority) {
    return "{\"principal\":{\"kind\":\"HUMAN\",\"issuer\":\""
        + ISSUER
        + "\",\"subject\":\""
        + subject
        + "\"},\"authority\":\""
        + authority
        + "\",\"scope\":{\"kind\":\"ORGANIZATION\",\"organization\":{"
        + "\"issuer\":\"urn:ayuvahak:directory\",\"identifier\":\"org-17\"}},"
        + "\"reasonCode\":\"ACCESS_SETUP\"}";
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class DirectoryGrantScopeFixtureConfiguration {

    @Bean
    @Primary
    DirectoryScopeReferenceLookup directoryGrantScopeFixture() {
      return new DirectoryScopeReferenceLookup() {
        @Override
        public boolean isActiveOrganization(ExternalReference organization) {
          return organization.equals(
                  new ExternalReference(
                      new IssuerId("urn:ayuvahak:directory"), "Organization", "org-17"))
              || organization.equals(
                  new ExternalReference(
                      new IssuerId("urn:ayuvahak:directory"), "Organization", "org-18"));
        }

        @Override
        public boolean isActiveFacilityInOrganization(
            ExternalReference organization, ExternalReference facility) {
          return organization.equals(
                  new ExternalReference(
                      new IssuerId("urn:ayuvahak:directory"), "Organization", "org-17"))
              && facility.equals(
                  new ExternalReference(
                      new IssuerId("urn:ayuvahak:directory"), "Facility", "facility-23"));
        }
      };
    }
  }

  private BootstrapGrantProperties bootstrapProperties() {
    return new BootstrapGrantProperties(
        LOCAL_OPERATOR_ISSUER,
        "operator-fixture-09",
        ISSUER,
        ActorKind.HUMAN.name(),
        ADMIN_SUBJECT,
        "PLATFORM",
        "CREATE_FIRST_ACCESS_GRANT");
  }

  private String[] bootstrapArguments() {
    return new String[] {
      "--AYUVAHAK_DATABASE_URL=" + POSTGRES.getJdbcUrl(),
      "--AYUVAHAK_DATABASE_USERNAME=" + POSTGRES.getUsername(),
      "--AYUVAHAK_DATABASE_PASSWORD=" + POSTGRES.getPassword(),
      "--ayuvahak.authentication.issuer=" + ISSUER,
      "--ayuvahak.authentication.jwk-set-uri=http://127.0.0.1:"
          + jwksServer.getAddress().getPort()
          + "/jwks",
      "--ayuvahak.authentication.audience=" + AUDIENCE,
      "--ayuvahak.authentication.service-client-ids=synthetic-worker",
      "--ayuvahak.authentication.allow-http=true",
      "--ayuvahak.bootstrap.operator-issuer=" + LOCAL_OPERATOR_ISSUER,
      "--ayuvahak.bootstrap.operator-reference=operator-fixture-09",
      "--ayuvahak.bootstrap.grantee-issuer=" + ISSUER,
      "--ayuvahak.bootstrap.grantee-kind=HUMAN",
      "--ayuvahak.bootstrap.grantee-identifier=" + ADMIN_SUBJECT,
      "--ayuvahak.bootstrap.scope=PLATFORM",
      "--ayuvahak.bootstrap.confirmation=CREATE_FIRST_ACCESS_GRANT"
    };
  }

  private String token(String subject, Map<String, Object> extraClaims) throws Exception {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject(subject)
            .audience(AUDIENCE)
            .issueTime(Date.from(Instant.now().minusSeconds(5)))
            .notBeforeTime(Date.from(Instant.now().minusSeconds(1)))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim("typ", "Bearer");
    extraClaims.forEach(claims::claim);
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(SIGNING_KEY.getKeyID())
                .build(),
            claims.build());
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

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private int grantCount() {
    return count("SELECT COUNT(*) FROM identity_access.authorization_grant");
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }
}
