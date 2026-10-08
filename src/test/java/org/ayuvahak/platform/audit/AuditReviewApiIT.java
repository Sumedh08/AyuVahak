package org.ayuvahak.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.ayuvahak.platform.authorization.adapter.api.PlatformAccessGrantController;
import org.ayuvahak.platform.authorization.application.BootstrapGrantProperties;
import org.ayuvahak.platform.authorization.application.BootstrapGrantService;
import org.ayuvahak.platform.core.ActorKind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class AuditReviewApiIT {

  private static final String TEST_PASSWORD = "local-test-only";
  private static final String ISSUER = "https://identity.example.test/realms/ayuvahak";
  private static final String ADMIN_SUBJECT = "synthetic-admin-audit-test";
  private static final String REVIEWER_SUBJECT = "synthetic-audit-reviewer";
  private static final RSAKey SIGNING_KEY = newSigningKey("audit-review-fixture");
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

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private BootstrapGrantService bootstrapService;

  @BeforeAll
  static void startLocalJwksServer() throws Exception {
    jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    jwksServer.createContext(
        "/jwks",
        exchange -> {
          byte[] body =
              new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
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
  void prepareSyntheticState() throws Exception {
    jdbc.execute("DROP TRIGGER IF EXISTS test_fail_audit_review ON audit.audit_record");
    jdbc.execute("DROP FUNCTION IF EXISTS test_fail_audit_review()");
    jdbc.update("DELETE FROM identity_access.grant_command_receipt");
    jdbc.update("DELETE FROM identity_access.bootstrap_marker");
    jdbc.update("DELETE FROM audit.audit_record");
    jdbc.update("DELETE FROM platform_eventing.outbox_delivery");
    jdbc.update("DELETE FROM platform_eventing.internal_event");
    jdbc.update("DELETE FROM identity_access.authorization_grant");
    bootstrapService.provisionFirstGrant(bootstrapProperties(), "synthetic-test-host");
    var grantRequest =
        "{\"principal\":{\"kind\":\"HUMAN\",\"issuer\":\""
            + ISSUER
            + "\",\"subject\":\""
            + REVIEWER_SUBJECT
            + "\"},\"authority\":\"AuditReview\",\"scope\":{\"kind\":\"PLATFORM\"},"
            + "\"reasonCode\":\"ACCESS_SETUP\"}";
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN_SUBJECT)))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                .header("Idempotency-Key", "audit-review-grant-01")
                .contentType("application/json")
                .content(grantRequest))
        .andExpect(status().isCreated());
  }

  @Test
  void authorizedReviewerReceivesBoundedMinimizedPageAfterItsReadIsDurablyAudited()
      throws Exception {
    jdbc.update("DELETE FROM audit.audit_record");
    insertAuditRecord(
        "synthetic-safe-record", "SAFE_ACTION", "safe-correlation", "2026-10-08T12:00:02Z");
    jdbc.update(
        "UPDATE audit.audit_record SET target_scope_kind = 'ORGANIZATION', "
            + "target_organization_issuer = 'urn:ayuvahak:directory', "
            + "target_organization_reference_type = 'Organization', "
            + "target_organization_reference = 'org-17' WHERE actor_reference = ?",
        "synthetic-safe-record");
    insertAuditRecord(
        "synthetic-secret-record", "SAFE_ACTION", "safe-correlation-2", "2026-10-08T12:00:01Z");

    var response =
        mockMvc
            .perform(
                get("/api/v1/platform/audit-records")
                    .header("Authorization", bearer(token(REVIEWER_SUBJECT)))
                    .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                    .param("limit", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].actionCode").value("AUDIT_RECORD_LIST"))
            .andExpect(jsonPath("$.items[0].actorReference").value(REVIEWER_SUBJECT))
            .andExpect(jsonPath("$.items[1].actionCode").value("SAFE_ACTION"))
            .andExpect(jsonPath("$.items[1].actorReference").value("synthetic-safe-record"))
            .andExpect(jsonPath("$.items[1].targetScopeKind").value("ORGANIZATION"))
            .andExpect(jsonPath("$.items[1].targetOrganizationReference").value("org-17"))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty())
            .andReturn();

    String body = response.getResponse().getContentAsString();
    assertThat(body).doesNotContain("rawToken", "credential", "clinicalPayload", "password");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'AUDIT_RECORD_LIST' "
                    + "AND action_kind = 'SENSITIVE_READ' AND outcome = 'SUCCEEDED' "
                    + "AND actor_reference = ?",
                Integer.class,
                REVIEWER_SUBJECT))
        .isEqualTo(1);
    assertThat(response.getResponse().getHeader("X-Correlation-ID")).isNotBlank();
    String nextCursor = com.jayway.jsonpath.JsonPath.read(body, "$.nextCursor");
    mockMvc
        .perform(
            get("/api/v1/platform/audit-records")
                .header("Authorization", bearer(token(REVIEWER_SUBJECT)))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                .param("limit", "2")
                .param("cursor", nextCursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].actorReference").value("synthetic-secret-record"));
  }

  @Test
  void reviewerWithoutAuditGrantIsDeniedWithoutRecordDisclosureAndDenialIsAudited()
      throws Exception {
    insertAuditRecord(
        "synthetic-protected-record",
        "PROTECTED_ACTION",
        "hidden-correlation",
        "2026-10-08T12:00:00Z");

    var response =
        mockMvc
            .perform(
                get("/api/v1/platform/audit-records")
                    .header("Authorization", bearer(token("synthetic-unprivileged")))
                    .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, "PLATFORM"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"))
            .andReturn();

    assertThat(response.getResponse().getContentAsString())
        .doesNotContain("synthetic-protected-record", "PROTECTED_ACTION", "items");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'AUDIT_RECORD_LIST' "
                    + "AND action_kind = 'SECURITY_OUTCOME' AND outcome = 'DENIED' "
                    + "AND actor_reference = 'synthetic-unprivileged'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void auditPersistenceFailureReturnsSafeUnavailableProblemWithoutRecordPayload() throws Exception {
    insertAuditRecord(
        "synthetic-protected-record",
        "PROTECTED_ACTION",
        "hidden-correlation",
        "2026-10-08T12:00:00Z");
    jdbc.execute(
        "CREATE FUNCTION test_fail_audit_review() RETURNS trigger LANGUAGE plpgsql AS "
            + "'BEGIN IF NEW.action_code = ''AUDIT_RECORD_LIST'' THEN RAISE EXCEPTION ''synthetic audit failure''; END IF; RETURN NEW; END'");
    jdbc.execute(
        "CREATE TRIGGER test_fail_audit_review BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION test_fail_audit_review()");

    var response =
        mockMvc
            .perform(
                get("/api/v1/platform/audit-records")
                    .header("Authorization", bearer(token(REVIEWER_SUBJECT)))
                    .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, "PLATFORM"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"))
            .andReturn();

    assertThat(response.getResponse().getContentAsString())
        .doesNotContain("synthetic-protected-record", "PROTECTED_ACTION", "items");
  }

  @Test
  void pageSizeAboveMaximumIsRejected() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/platform/audit-records")
                .header("Authorization", bearer(token(REVIEWER_SUBJECT)))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                .param("limit", "101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  private void insertAuditRecord(
      String actor, String action, String correlation, String timestampValue) {
    Instant timestamp = Instant.parse(timestampValue);
    jdbc.update(
        "INSERT INTO audit.audit_record (id, action_kind, actor_kind, actor_issuer, "
            + "actor_reference_type, actor_reference, scope_kind, action_code, target_type, "
            + "occurred_at, recorded_at, outcome, authentication_evidence, "
            + "authorization_outcome, correlation_id) "
            + "VALUES (?, 'SENSITIVE_READ', 'HUMAN', ?, 'subject', ?, 'PLATFORM', ?, "
            + "'AuditRecord', ?, ?, 'SUCCEEDED', 'HUMAN_ACCESS_TOKEN', 'ALLOWED', ?)",
        UUID.randomUUID(),
        ISSUER,
        actor,
        action,
        java.sql.Timestamp.from(timestamp),
        java.sql.Timestamp.from(timestamp),
        correlation);
  }

  private BootstrapGrantProperties bootstrapProperties() {
    return new BootstrapGrantProperties(
        "urn:ayuvahak:local-operator",
        "operator-fixture-10",
        ISSUER,
        ActorKind.HUMAN.name(),
        ADMIN_SUBJECT,
        "PLATFORM",
        "CREATE_FIRST_ACCESS_GRANT");
  }

  private static String token(String subject) throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject(subject)
            .audience("ayuvahak-api")
            .issueTime(Date.from(Instant.now().minusSeconds(5)))
            .notBeforeTime(Date.from(Instant.now().minusSeconds(1)))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim("typ", "Bearer")
            .build();
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

  private static String bearer(String token) {
    return "Bearer " + token;
  }
}
