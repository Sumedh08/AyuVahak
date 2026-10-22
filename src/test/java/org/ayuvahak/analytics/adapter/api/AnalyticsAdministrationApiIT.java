package org.ayuvahak.analytics.adapter.api;

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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.ayuvahak.platform.AyuVahakApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

/** Verifies the authenticated, least-privilege Analytics administration HTTP seams. */
@SpringBootTest(
    classes = AyuVahakApplication.class,
    properties = {
      "management.tracing.sampling.probability=0.0",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-analytics",
      "ayuvahak.authentication.allow-http=true",
      "spring.task.scheduling.enabled=false"
    })
@AutoConfigureMockMvc
@Testcontainers
class AnalyticsAdministrationApiIT {

  private static final String PASSWORD = "local-test-only";
  private static final String ISSUER = "https://identity.example.test/realms/ayuvahak";
  private static final String AUDIENCE = "ayuvahak-api";
  private static final String ADMIN = "synthetic-analytics-definition-admin";
  private static final String SERVICE = "synthetic-analytics";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final UUID ORGANIZATION_ID = UUID.fromString("7cc4dd8c-0000-4000-8000-000000000031");
  private static final RSAKey SIGNING_KEY = newSigningKey("analytics-admin-fixture");
  private static final PostgreSQLContainer POSTGRES =
      org.ayuvahak.testcontainers.PostgresTestContainerFactory.create(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(PASSWORD);

  @Container private static final PostgreSQLContainer CONTAINER = POSTGRES;
  private static HttpServer jwksServer;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;

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
    if (jwksServer != null) jwksServer.stop(0);
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

  @Test
  void publishesOnlyAnAuthorizedBoundedImmutableIndicatorVersion() throws Exception {
    grantHuman(ADMIN, "AnalyticsIndicatorDefinitionAdministration", "PLATFORM");
    String body = definitionBody("SYN-LOCAL-REFERRAL-COUNT", "Synthetic local referral count");

    mockMvc
        .perform(
            post("/api/v1/analytics/indicator-definitions")
                .header("Authorization", bearer(token(ADMIN, null)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.key").value("SYN-LOCAL-REFERRAL-COUNT"))
        .andExpect(jsonPath("$.version").value(1));

    mockMvc
        .perform(
            post("/api/v1/analytics/indicator-definitions")
                .header("Authorization", bearer(token(ADMIN, null)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .contentType("application/json")
                .content(body.replace("Synthetic local referral count", "Synthetic local referral count v2")))
        .andExpect(status().isConflict());

    mockMvc
        .perform(
            post("/api/v1/analytics/indicator-definitions")
                .header("Authorization", bearer(token("synthetic-unauthorized", null)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            post("/api/v1/analytics/indicator-definitions")
                .header("Authorization", bearer(token(ADMIN, null)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .contentType("application/json")
                .content(body.replace("\"zeroAllowed\":true", "\"zeroAllowed\":true,\"patientId\":\"SYN-PATIENT-PRIVATE\"")))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void acceptsOnlyAServiceActorWithAnOrganizationScopedComputationGrant() throws Exception {
    seedOrganization();
    grantService(
        SERVICE,
        "AnalyticsAggregateComputation",
        "ORGANIZATION",
        ORGANIZATION_ID.toString());
    String cutoff = Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString();
    String request =
        "{\"computationRequestId\":\""
            + UUID.randomUUID()
            + "\",\"indicatorKey\":\"SYN-LOCAL-REFERRAL-COUNT\",\"indicatorVersion\":1,"
            + "\"startInclusive\":\"2026-01-01\",\"endExclusive\":\"2026-02-01\","
            + "\"referralSourceCutoff\":\""
            + cutoff
            + "\"}";

    mockMvc
        .perform(
            post("/api/v1/analytics/computation-operations")
                .header("Authorization", bearer(token("synthetic-analytics", SERVICE)))
                .header("X-AyuVahak-Execution-Scope", "ORGANIZATION")
                .header("X-AyuVahak-Organization-Id", ORGANIZATION_ID)
                .contentType("application/json")
                .content(request))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.operationId").isNotEmpty())
        .andExpect(jsonPath("$.state").value("PENDING"));

    mockMvc
        .perform(
            post("/api/v1/analytics/computation-operations")
                .header("Authorization", bearer(token(ADMIN, null)))
                .header("X-AyuVahak-Execution-Scope", "ORGANIZATION")
                .header("X-AyuVahak-Organization-Id", ORGANIZATION_ID)
                .contentType("application/json")
                .content(request))
        .andExpect(status().isUnauthorized());
  }

  private void seedOrganization() {
    Instant now = Instant.now();
    jdbc.update(
        "INSERT INTO directory.organization "
            + "(organization_id, name, source_issuer, source_resource_type, source_reference, "
            + "provenance_method, provenance_observed_at, provenance_recorded_at, "
            + "provenance_actor_kind, provenance_actor_issuer, provenance_actor_identifier_type, "
            + "provenance_actor_identifier, provenance_reason_code, lifecycle_status, version, "
            + "created_at, updated_at) VALUES (?, 'SYN Analytics Organization', 'urn:synthetic:registry', "
            + "'Organization', 'SYN-ORG-ANALYTICS-001', 'SYNTHETIC_FIXTURE', ?, ?, 'HUMAN', ?, "
            + "'subject', ?, 'SYNTHETIC_ANALYTICS_API_TEST', 'ACTIVE', 1, ?, ?)",
        ORGANIZATION_ID,
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now),
        ISSUER,
        ADMIN,
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now));
  }

  private void grantHuman(String subject, String authority, String scope) {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant "
            + "(grant_id, principal_kind, principal_issuer, principal_identifier_type, "
            + "principal_identifier, authority_code, scope_kind, grant_status, created_at) "
            + "VALUES (gen_random_uuid(), 'HUMAN', ?, 'subject', ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        subject,
        authority,
        scope);
  }

  private void grantService(String clientId, String authority, String scope, String organizationId) {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant "
            + "(grant_id, principal_kind, principal_issuer, principal_identifier_type, "
            + "principal_identifier, authority_code, scope_kind, organization_issuer, "
            + "organization_identifier_type, organization_identifier, grant_status, created_at) "
            + "VALUES (gen_random_uuid(), 'SERVICE', ?, 'client_id', ?, ?, ?, ?, 'Organization', ?, 'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        clientId,
        authority,
        scope,
        DIRECTORY_ISSUER,
        organizationId);
  }

  private static String definitionBody(String key, String description) {
    return "{\"key\":\""
        + key
        + "\",\"version\":1,\"description\":\""
        + description
        + "\",\"formula\":\"SUM\",\"reportingTimeZone\":\"UTC\","
        + "\"sourceMeasures\":[{\"owner\":\"REFERRAL\",\"key\":\"SUBMITTED_COUNT\",\"attribution\":\"NONE\"}],"
        + "\"zeroAllowed\":true,\"denominatorMeasures\":[],\"governance\":{"
        + "\"ownerApprovedPurpose\":\"Synthetic local demonstration\",\"effectiveFrom\":\"2026-01-01T00:00:00Z\","
        + "\"reportingCalendar\":\"MONTHLY\",\"allowedDimensions\":[\"ORGANIZATION\"],"
        + "\"completenessRequirement\":\"ALL_REQUIRED_SOURCES_COMPLETE_FOR_NUMERIC_VALUE\","
        + "\"sensitivity\":\"LOW_SENSITIVITY_AGGREGATE\"}}";
  }

  private String token(String subject, String clientId) throws Exception {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject(subject)
            .audience(AUDIENCE)
            .issueTime(Date.from(Instant.now().minusSeconds(5)))
            .notBeforeTime(Date.from(Instant.now().minusSeconds(1)))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim("typ", "Bearer");
    if (clientId != null) claims.claim("client_id", clientId);
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
      throw new IllegalStateException("Could not create a synthetic Analytics API test key.", exception);
    }
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }
}
