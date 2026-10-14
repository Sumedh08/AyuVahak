package org.ayuvahak.capacity.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.ayuvahak.platform.AyuVahakApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    classes = AyuVahakApplication.class,
    properties = {
      "management.tracing.sampling.probability=0.0",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-worker",
      "ayuvahak.authentication.allow-http=true"
    })
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CapacityPolicyAdministrationApiIT {

  private static final String TEST_PASSWORD = "local-test-only";
  private static final String ISSUER = "https://identity.example.test/realms/ayuvahak";
  private static final String AUDIENCE = "ayuvahak-api";
  private static final String ADMIN = "synthetic-capacity-policy-admin";
  private static final String UNAUTHORIZED = "synthetic-capacity-reporter";
  private static final RSAKey SIGNING_KEY = newSigningKey("capacity-policy-fixture");
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
  void resetSyntheticState() {
    jdbc.execute("DROP TRIGGER IF EXISTS test_fail_capacity_audit_insert ON audit.audit_record");
    jdbc.execute("DROP FUNCTION IF EXISTS test_fail_capacity_audit_insert()");
    Boolean capacitySchemaExists =
        jdbc.queryForObject("SELECT to_regnamespace('capacity') IS NOT NULL", Boolean.class);
    if (Boolean.TRUE.equals(capacitySchemaExists)) {
      jdbc.execute(
          "TRUNCATE TABLE capacity.command_receipt, capacity.capacity_policy_readiness_state, "
              + "capacity.capacity_policy_source, capacity.capacity_policy, "
              + "capacity.capacity_policy_version, capacity.capacity_dimension_state, "
              + "capacity.capacity_dimension, capacity.capacity_dimension_version CASCADE");
    }
    jdbc.update("DELETE FROM audit.audit_record");
    jdbc.update(
        "DELETE FROM identity_access.authorization_grant WHERE principal_identifier = ?", ADMIN);
    seedPolicyAdministrator();
  }

  @Test
  void createsTypedDimensionAndReplaysTheSameAttributedVersion() throws Exception {
    String request = dimensionRequest(0, "QUANTITY", "bed", "[]", true, true);

    var first =
        mockMvc
            .perform(
                post("/api/v1/capacity/policies/dimensions")
                    .header("Authorization", bearer(token(ADMIN)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", "capacity-dimension-create-01")
                    .contentType("application/json")
                    .content(request))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value("COORDINATION_BEDS"))
            .andExpect(jsonPath("$.version").value(1))
            .andReturn();

    String revisionId =
        com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.id");

    mockMvc
        .perform(
            post("/api/v1/capacity/policies/dimensions")
                .header("Authorization", bearer(token(ADMIN)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "capacity-dimension-create-01")
                .contentType("application/json")
                .content(request))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(revisionId))
        .andExpect(jsonPath("$.version").value(1));

    assertThat(count("SELECT COUNT(*) FROM capacity.capacity_dimension_version")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM audit.audit_record")).isEqualTo(1);
  }

  @Test
  void dimensionAndPolicyVersionsRemainAttributableAndDoNotRewritePriorVersions() throws Exception {
    defineDimension("dimension-version-1", dimensionRequest(0, "QUANTITY", "bed", "[]", true, true))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(1));
    configurePolicy(0, 1, 600, "policy-version-1")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(1));
    configurePolicy(0, 1, 600, "policy-version-1")
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"));
    configurePolicy(0, 1, 601, "policy-version-1")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    defineDimension(
            "dimension-version-2",
            dimensionRequest(1, "QUANTITY", "bed", "[]", true, true)
                .replace(
                    "Coordination-available staffed beds",
                    "Updated coordination-available staffed beds"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));
    configurePolicy(1, 2, 900, "policy-version-2")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));

    assertThat(
            jdbc.queryForList(
                "SELECT version, display_name, changed_by_identifier "
                    + "FROM capacity.capacity_dimension_version ORDER BY version"))
        .containsExactly(
            java.util.Map.of(
                "version",
                1L,
                "display_name",
                "Coordination-available staffed beds",
                "changed_by_identifier",
                ADMIN),
            java.util.Map.of(
                "version",
                2L,
                "display_name",
                "Updated coordination-available staffed beds",
                "changed_by_identifier",
                ADMIN));
    assertThat(
            jdbc.queryForList(
                "SELECT version, dimension_version, maximum_observation_age_seconds, "
                    + "changed_by_identifier FROM capacity.capacity_policy_version ORDER BY version"))
        .containsExactly(
            java.util.Map.of(
                "version", 1L,
                "dimension_version", 1L,
                "maximum_observation_age_seconds", 600L,
                "changed_by_identifier", ADMIN),
            java.util.Map.of(
                "version", 2L,
                "dimension_version", 2L,
                "maximum_observation_age_seconds", 900L,
                "changed_by_identifier", ADMIN));
    assertThat(
            count(
                "SELECT COUNT(*) FROM capacity.capacity_policy_source "
                    + "WHERE dimension_code = 'COORDINATION_BEDS' AND policy_version = 1 "
                    + "AND source_code = 'manual' AND priority = 1 "
                    + "AND fallback_when_higher_priority_absent"))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM audit.audit_record WHERE outcome = 'SUCCEEDED'"))
        .isEqualTo(4);
    assertThat(
            count(
                "SELECT COUNT(*) FROM capacity.command_receipt "
                    + "WHERE receipt_state = 'COMPLETED'"))
        .isEqualTo(4);
  }

  @Test
  void rejectsUntypedOrVersionMismatchedPolicyWithoutCreatingState() throws Exception {
    String untyped = dimensionRequest(0, "QUANTITY", "bed", "[]", true, true);
    untyped = untyped.substring(0, untyped.length() - 1) + ",\"extension\":{\"ready\":true}}";
    defineDimension("invalid-dimension-shape", untyped)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CAPACITY_VALUE_INVALID"));
    defineDimension(
            "dimension-for-invalid-policy",
            dimensionRequest(0, "QUANTITY", "bed", "[]", true, true))
        .andExpect(status().isCreated());
    configurePolicy(0, 2, 600, "policy-wrong-dimension-version")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CAPACITY_VALUE_INVALID"));

    assertThat(count("SELECT COUNT(*) FROM capacity.capacity_dimension_version")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM capacity.capacity_policy_version")).isZero();
    assertThat(count("SELECT COUNT(*) FROM capacity.command_receipt")).isEqualTo(1);
  }

  @Test
  void requiresPolicyAuthorityAndPlatformScope() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/capacity/policies/dimensions")
                .header("Authorization", bearer(token(UNAUTHORIZED)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "capacity-unauthorized")
                .contentType("application/json")
                .content(dimensionRequest(0, "QUANTITY", "bed", "[]", true, true)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));

    mockMvc
        .perform(
            post("/api/v1/capacity/policies/dimensions")
                .header("Authorization", bearer(token(ADMIN)))
                .header("X-AyuVahak-Execution-Scope", "ORGANIZATION")
                .header("Idempotency-Key", "capacity-wrong-scope")
                .contentType("application/json")
                .content(dimensionRequest(0, "QUANTITY", "bed", "[]", true, true)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_SCOPE"));

    assertThat(count("SELECT COUNT(*) FROM capacity.capacity_dimension_version")).isZero();
    assertThat(count("SELECT COUNT(*) FROM capacity.command_receipt")).isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE outcome = 'DENIED' "
                    + "AND action_code IN ('CAPACITY_DIMENSION_DEFINE', "
                    + "'CAPACITY_POLICY_SCOPE_REJECTED')"))
        .isEqualTo(2);
  }

  @Test
  void auditFailureRollsBackDimensionAndCommandReceipt() throws Exception {
    jdbc.execute(
        "CREATE FUNCTION test_fail_capacity_audit_insert() RETURNS trigger LANGUAGE plpgsql AS "
            + "'BEGIN IF NEW.action_code = ''CAPACITY_DIMENSION_DEFINE'' THEN "
            + "RAISE EXCEPTION ''synthetic capacity audit failure''; END IF; RETURN NEW; END'");
    jdbc.execute(
        "CREATE TRIGGER test_fail_capacity_audit_insert BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION test_fail_capacity_audit_insert()");

    mockMvc
        .perform(
            post("/api/v1/capacity/policies/dimensions")
                .header("Authorization", bearer(token(ADMIN)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "capacity-audit-failure")
                .contentType("application/json")
                .content(dimensionRequest(0, "QUANTITY", "bed", "[]", true, true)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));

    assertThat(count("SELECT COUNT(*) FROM capacity.capacity_dimension")).isZero();
    assertThat(count("SELECT COUNT(*) FROM capacity.capacity_dimension_version")).isZero();
    assertThat(count("SELECT COUNT(*) FROM capacity.command_receipt")).isZero();
  }

  private org.springframework.test.web.servlet.ResultActions defineDimension(
      String key, String request) throws Exception {
    return mockMvc.perform(
        post("/api/v1/capacity/policies/dimensions")
            .header("Authorization", bearer(token(ADMIN)))
            .header("X-AyuVahak-Execution-Scope", "PLATFORM")
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(request));
  }

  private org.springframework.test.web.servlet.ResultActions configurePolicy(
      long expectedPolicyVersion, long dimensionVersion, long maximumAge, String key)
      throws Exception {
    String request =
        "{\"expectedVersion\":"
            + expectedPolicyVersion
            + ",\"dimensionVersion\":"
            + dimensionVersion
            + ",\"sources\":["
            + "{\"sourceCode\":\"facility-his\",\"priority\":0,"
            + "\"fallbackWhenHigherPriorityAbsent\":false,"
            + "\"fallbackWhenHigherPriorityStale\":false},"
            + "{\"sourceCode\":\"manual\",\"priority\":1,"
            + "\"fallbackWhenHigherPriorityAbsent\":true,"
            + "\"fallbackWhenHigherPriorityStale\":false}],"
            + "\"maximumObservationAgeSeconds\":"
            + maximumAge
            + ",\"defaultValiditySeconds\":null,\"futureSkewToleranceSeconds\":30,"
            + "\"minimumPassingQuantity\":1,\"passingStates\":[],"
            + "\"reasonCode\":\"POLICY_CONFIGURATION\"}";
    return mockMvc.perform(
        post("/api/v1/capacity/policies/dimensions/COORDINATION_BEDS/policy")
            .header("Authorization", bearer(token(ADMIN)))
            .header("X-AyuVahak-Execution-Scope", "PLATFORM")
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(request));
  }

  private static String dimensionRequest(
      long expectedVersion,
      String valueKind,
      String unit,
      String allowedStates,
      boolean usedForReadiness,
      boolean reservable) {
    return "{\"code\":\"COORDINATION_BEDS\",\"expectedVersion\":"
        + expectedVersion
        + ",\"displayName\":\"Coordination-available staffed beds\","
        + "\"meaning\":\"Currently staffed beds explicitly available to coordination\","
        + "\"valueKind\":\""
        + valueKind
        + "\",\"unit\":"
        + (unit == null ? "null" : "\"" + unit + "\"")
        + ",\"minimumValue\":0,\"maximumValue\":500,\"allowedStates\":"
        + allowedStates
        + ",\"usedForReadiness\":"
        + usedForReadiness
        + ",\"reservable\":"
        + reservable
        + ",\"reasonCode\":\"POLICY_CONFIGURATION\"}";
  }

  private void seedPolicyAdministrator() {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, grant_status, created_at) VALUES (gen_random_uuid(), 'HUMAN', ?, "
            + "'subject', ?, 'CapacityPolicyAdministration', 'PLATFORM', 'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        ADMIN);
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  private String token(String subject) throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(SIGNING_KEY.getKeyID())
                .build(),
            new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(AUDIENCE)
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(1)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("typ", "Bearer")
                .build());
    jwt.sign(new RSASSASigner(SIGNING_KEY));
    return jwt.serialize();
  }

  private static RSAKey newSigningKey(String keyId) {
    try {
      return new RSAKeyGenerator(2048).keyID(keyId).generate();
    } catch (Exception exception) {
      throw new IllegalStateException("Could not create a synthetic signing key.", exception);
    }
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }
}
