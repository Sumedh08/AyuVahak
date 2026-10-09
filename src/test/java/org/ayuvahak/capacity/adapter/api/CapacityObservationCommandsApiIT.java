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
      "ayuvahak.authentication.service-client-ids=synthetic-capacity-service",
      "ayuvahak.authentication.allow-http=true"
    })
@AutoConfigureMockMvc
@Testcontainers
class CapacityObservationCommandsApiIT {

  private static final String TEST_PASSWORD = "local-test-only";
  private static final String ISSUER = "https://identity.example.test/realms/ayuvahak";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String AUDIENCE = "ayuvahak-api";
  private static final String ADMIN = "synthetic-platform-admin";
  private static final String CURATOR = "synthetic-directory-curator";
  private static final String REPORTER = "synthetic-capacity-reporter";
  private static final String SERVICE_REPORTER = "synthetic-capacity-service";
  private static final RSAKey SIGNING_KEY = newSigningKey("capacity-observation-fixture");
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

  private String organizationId;
  private String facilityId;

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
  void prepareLocalAuthoritiesAndCapacityPolicy() throws Exception {
    jdbc.execute(
        "DROP TRIGGER IF EXISTS test_fail_capacity_observation_audit ON audit.audit_record");
    jdbc.execute("DROP FUNCTION IF EXISTS test_fail_capacity_observation_audit()");
    Boolean observationTableExists =
        jdbc.queryForObject(
            "SELECT to_regclass('capacity.capacity_observation') IS NOT NULL", Boolean.class);
    if (Boolean.TRUE.equals(observationTableExists)) {
      jdbc.execute(
          "TRUNCATE TABLE capacity.observation_correction, capacity.capacity_observation, "
              + "capacity.command_receipt, capacity.capacity_policy_readiness_state, "
              + "capacity.capacity_policy_source, capacity.capacity_policy, "
              + "capacity.capacity_policy_version, capacity.capacity_dimension_state, "
              + "capacity.capacity_dimension, capacity.capacity_dimension_version CASCADE");
    } else {
      jdbc.execute(
          "TRUNCATE TABLE capacity.command_receipt, capacity.capacity_policy_readiness_state, "
              + "capacity.capacity_policy_source, capacity.capacity_policy, "
              + "capacity.capacity_policy_version, capacity.capacity_dimension_state, "
              + "capacity.capacity_dimension, capacity.capacity_dimension_version CASCADE");
    }
    jdbc.update("DELETE FROM audit.audit_record");
    jdbc.update("DELETE FROM platform_eventing.outbox_delivery");
    jdbc.update("DELETE FROM platform_eventing.internal_event");
    jdbc.update("DELETE FROM identity_access.grant_command_receipt");
    jdbc.update("DELETE FROM identity_access.bootstrap_marker");
    jdbc.update("DELETE FROM identity_access.authorization_grant");
    clearDirectory();
    seedPlatformAdministrator();
    seedCapacityPolicyAdministrator();
    grant(CURATOR, "DirectoryCuration", "PLATFORM", null, null, "curator-grant");
    organizationId = createOrganization("North Coordination Clinic");
    facilityId = createFacility("North Coordination Facility");
    grant(REPORTER, "CapacityReporting", "FACILITY", organizationId, facilityId, "reporter-grant");
    grantService(
        SERVICE_REPORTER,
        "CapacityReporting",
        organizationId,
        facilityId,
        "service-reporter-grant");
    defineDimension();
    configurePolicy();
  }

  @Test
  void ingestsTypedEvidenceAndSeparatesSourceReplayFromCommandRetry() throws Exception {
    long receiptCountBefore = count("SELECT COUNT(*) FROM capacity.command_receipt");
    String command = observationBody("source-observation-01", 4, "2026-10-08T09:00:00Z");

    var first =
        submit("command-observation-01", command).andExpect(status().isCreated()).andReturn();
    String observationId =
        com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.id");
    submit("command-observation-01", command)
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.id").value(observationId));
    submit("command-observation-02", command)
        .andExpect(status().isOk())
        .andExpect(header().string("Source-Observation-Replayed", "true"))
        .andExpect(jsonPath("$.id").value(observationId));

    assertThat(count("SELECT COUNT(*) FROM capacity.capacity_observation")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM capacity.command_receipt"))
        .isEqualTo(receiptCountBefore + 2);
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'CAPACITY_OBSERVATION_INGEST'"))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT reported_by_kind FROM capacity.capacity_observation", String.class))
        .isEqualTo("HUMAN");
    assertThat(
            jdbc.queryForObject(
                "SELECT source_identifier FROM capacity.capacity_observation", String.class))
        .isEqualTo("source-observation-01");
    assertThat(
            jdbc.queryForObject(
                "SELECT received_at > observed_at FROM capacity.capacity_observation",
                Boolean.class))
        .isTrue();

    submit(
            "command-observation-03",
            observationBody("source-observation-01", 5, "2026-10-08T09:00:00Z"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CAPACITY_OBSERVATION_CONFLICT"));
    assertThat(count("SELECT COUNT(*) FROM capacity.capacity_observation")).isEqualTo(1);
  }

  @Test
  void appendsCorrectionAndRetractionWithoutChangingTheOriginal() throws Exception {
    String originalId =
        com.jayway.jsonpath.JsonPath.read(
            submit(
                    "command-original-01",
                    observationBody("source-original-01", 4, "2026-10-08T09:00:00Z"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");

    var correction =
        correct(originalId, "command-correction-01", correctionBody("source-correction-01", 3))
            .andExpect(status().isCreated())
            .andReturn();
    String correctedId =
        com.jayway.jsonpath.JsonPath.read(correction.getResponse().getContentAsString(), "$.id");
    correct(originalId, "command-correction-01", correctionBody("source-correction-01", 3))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.id").value(correctedId));

    correct(correctedId, "command-retraction-01", retractionBody("source-retraction-01"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.kind").value("RETRACTION"));
    correct(originalId, "command-double-correction", correctionBody("source-correction-02", 2))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CAPACITY_OBSERVATION_CONFLICT"));

    assertThat(count("SELECT COUNT(*) FROM capacity.capacity_observation")).isEqualTo(3);
    assertThat(count("SELECT COUNT(*) FROM capacity.observation_correction")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT quantity FROM capacity.capacity_observation WHERE observation_id = ?",
                java.math.BigDecimal.class,
                java.util.UUID.fromString(originalId)))
        .isEqualByComparingTo("4");
    assertThat(
            jdbc.queryForObject(
                "SELECT observation_kind FROM capacity.capacity_observation WHERE observation_id = ?",
                String.class,
                java.util.UUID.fromString(correctedId)))
        .isEqualTo("VALUE");
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code IN ('CAPACITY_OBSERVATION_CORRECT', 'CAPACITY_OBSERVATION_RETRACT')"))
        .isEqualTo(2);
  }

  @Test
  void commandRetryReplaysAfterPolicyNoLongerPermitsTheSource() throws Exception {
    String command = observationBody("source-observation-policy-change", 4, "2026-10-08T09:00:00Z");
    var first =
        submit("command-policy-change-retry", command).andExpect(status().isCreated()).andReturn();
    String observationId =
        com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.id");

    replacePolicySource();

    submit("command-policy-change-retry", command)
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.id").value(observationId));
    submit("command-policy-change-source-replay", command)
        .andExpect(status().isOk())
        .andExpect(header().string("Source-Observation-Replayed", "true"))
        .andExpect(jsonPath("$.id").value(observationId));
    submit(
            "command-policy-change-source-conflict",
            observationBody("source-observation-policy-change", 5, "2026-10-08T09:00:00Z"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CAPACITY_OBSERVATION_CONFLICT"));
    assertThat(count("SELECT COUNT(*) FROM capacity.capacity_observation")).isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'CAPACITY_OBSERVATION_INGEST'"))
        .isEqualTo(1);
  }

  @Test
  void automatedServiceObservationPreservesServiceProvenanceWithoutInventingHumanReporter()
      throws Exception {
    String command = observationBody("source-service-observation", 6, "2026-10-08T09:00:00Z");
    mockMvc
        .perform(
            post("/api/v1/capacity/observations")
                .header("Authorization", bearer(serviceToken(SERVICE_REPORTER)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", "service-observation-command")
                .contentType("application/json")
                .content(command))
        .andExpect(status().isCreated());

    assertThat(
            jdbc.queryForMap(
                "SELECT reported_by_kind, reported_by_identifier_type, reported_by_identifier "
                    + "FROM capacity.capacity_observation WHERE source_identifier = ?",
                "source-service-observation"))
        .containsEntry("reported_by_kind", "SERVICE")
        .containsEntry("reported_by_identifier_type", "client_id")
        .containsEntry("reported_by_identifier", SERVICE_REPORTER);
    assertThat(
            jdbc.queryForMap(
                "SELECT actor_kind, actor_reference_type, actor_reference "
                    + "FROM audit.audit_record WHERE action_code = 'CAPACITY_OBSERVATION_INGEST'"))
        .containsEntry("actor_kind", "SERVICE")
        .containsEntry("actor_reference_type", "client_id")
        .containsEntry("actor_reference", SERVICE_REPORTER);
  }

  @Test
  void auditFailureRollsBackObservationAndOwnerReceipt() throws Exception {
    long auditCountBefore = count("SELECT COUNT(*) FROM audit.audit_record");
    long receiptCountBefore = count("SELECT COUNT(*) FROM capacity.command_receipt");
    jdbc.execute(
        "CREATE FUNCTION test_fail_capacity_observation_audit() RETURNS trigger LANGUAGE plpgsql AS "
            + "'BEGIN IF NEW.action_code = ''CAPACITY_OBSERVATION_INGEST'' THEN "
            + "RAISE EXCEPTION ''synthetic capacity observation audit failure''; END IF; RETURN NEW; END'");
    jdbc.execute(
        "CREATE TRIGGER test_fail_capacity_observation_audit BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION test_fail_capacity_observation_audit()");

    submit(
            "command-audit-failure",
            observationBody("source-audit-failure", 4, "2026-10-08T09:00:00Z"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));

    assertThat(count("SELECT COUNT(*) FROM capacity.capacity_observation")).isZero();
    assertThat(count("SELECT COUNT(*) FROM capacity.command_receipt"))
        .isEqualTo(receiptCountBefore);
    assertThat(count("SELECT COUNT(*) FROM audit.audit_record")).isEqualTo(auditCountBefore);
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'CAPACITY_OBSERVATION_INGEST'"))
        .isZero();
  }

  @Test
  void quarantinesFutureSkewAndRejectsInvalidTypeOrDirectoryScope() throws Exception {
    submit(
            "command-future-01",
            observationBody("source-future-01", 4, Instant.now().plusSeconds(120).toString()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.futureSkewed").value(true));
    submit(
            "command-invalid-kind",
            observationBody("source-invalid-kind", 4, "2026-10-08T09:00:00Z")
                .replace("\"valueKind\":\"QUANTITY\"", "\"valueKind\":\"STATE\""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CAPACITY_VALUE_INVALID"));

    mockMvc
        .perform(
            post("/api/v1/capacity/observations")
                .header("Authorization", bearer(token(REPORTER)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", "00000000-0000-4000-8000-000000000001")
                .header("Idempotency-Key", "inactive-facility-scope")
                .contentType("application/json")
                .content(observationBody("source-inactive-facility", 4, "2026-10-08T09:00:00Z")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_SCOPE"));

    assertThat(count("SELECT COUNT(*) FROM capacity.capacity_observation")).isEqualTo(1);
  }

  private org.springframework.test.web.servlet.ResultActions submit(String key, String body)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/capacity/observations")
            .header("Authorization", bearer(token(REPORTER)))
            .header("X-AyuVahak-Execution-Scope", "FACILITY")
            .header("X-AyuVahak-Organization-Id", organizationId)
            .header("X-AyuVahak-Facility-Id", facilityId)
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(body));
  }

  private void replacePolicySource() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/capacity/policies/dimensions/COORDINATION_BEDS/policy")
                .header("Authorization", bearer(token(ADMIN)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "policy-source-replacement")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":1,\"dimensionVersion\":1,\"sources\":["
                        + "{\"sourceCode\":\"automated\",\"priority\":0,"
                        + "\"fallbackWhenHigherPriorityAbsent\":false,\"fallbackWhenHigherPriorityStale\":false}],"
                        + "\"maximumObservationAgeSeconds\":600,\"defaultValiditySeconds\":300,"
                        + "\"futureSkewToleranceSeconds\":30,\"minimumPassingQuantity\":1,"
                        + "\"passingStates\":[],\"reasonCode\":\"TEST_CONFIGURATION\"}"))
        .andExpect(status().isOk());
  }

  private org.springframework.test.web.servlet.ResultActions correct(
      String observationId, String key, String body) throws Exception {
    return mockMvc.perform(
        post("/api/v1/capacity/observations/{observationId}/corrections", observationId)
            .header("Authorization", bearer(token(REPORTER)))
            .header("X-AyuVahak-Execution-Scope", "FACILITY")
            .header("X-AyuVahak-Organization-Id", organizationId)
            .header("X-AyuVahak-Facility-Id", facilityId)
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(body));
  }

  private void defineDimension() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/capacity/policies/dimensions")
                .header("Authorization", bearer(token(ADMIN)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "dimension-setup")
                .contentType("application/json")
                .content(
                    "{\"code\":\"COORDINATION_BEDS\",\"expectedVersion\":0,"
                        + "\"displayName\":\"Coordination beds\",\"meaning\":\"Synthetic staffed beds available to coordination\","
                        + "\"valueKind\":\"QUANTITY\",\"unit\":\"bed\",\"minimumValue\":0,\"maximumValue\":500,"
                        + "\"allowedStates\":[],\"usedForReadiness\":true,\"reservable\":false,"
                        + "\"reasonCode\":\"TEST_CONFIGURATION\"}"))
        .andExpect(status().isCreated());
  }

  private void configurePolicy() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/capacity/policies/dimensions/COORDINATION_BEDS/policy")
                .header("Authorization", bearer(token(ADMIN)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "policy-setup")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":0,\"dimensionVersion\":1,\"sources\":["
                        + "{\"sourceCode\":\"manual\",\"priority\":0,"
                        + "\"fallbackWhenHigherPriorityAbsent\":false,\"fallbackWhenHigherPriorityStale\":false}],"
                        + "\"maximumObservationAgeSeconds\":600,\"defaultValiditySeconds\":300,"
                        + "\"futureSkewToleranceSeconds\":30,\"minimumPassingQuantity\":1,"
                        + "\"passingStates\":[],\"reasonCode\":\"TEST_CONFIGURATION\"}"))
        .andExpect(status().isCreated());
  }

  private String createOrganization(String name) throws Exception {
    var response =
        mockMvc
            .perform(
                post("/api/v1/directory/organizations")
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", "organization-setup")
                    .contentType("application/json")
                    .content(directoryOrganizationBody(name)))
            .andExpect(status().isCreated())
            .andReturn();
    return com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.id");
  }

  private String createFacility(String name) throws Exception {
    var response =
        mockMvc
            .perform(
                post("/api/v1/directory/facilities")
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", "facility-setup")
                    .contentType("application/json")
                    .content(directoryFacilityBody(organizationId, name)))
            .andExpect(status().isCreated())
            .andReturn();
    return com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.id");
  }

  private void grant(
      String grantee,
      String authority,
      String scope,
      String grantOrganization,
      String grantFacility,
      String key)
      throws Exception {
    String scopeJson =
        "PLATFORM".equals(scope)
            ? "\"kind\":\"PLATFORM\""
            : "\"kind\":\"FACILITY\",\"organization\":{"
                + ref(grantOrganization, "Organization")
                + "},\"facility\":{"
                + ref(grantFacility, "Facility")
                + "}";
    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(
                    "{\"principal\":{\"kind\":\"HUMAN\",\"issuer\":\""
                        + ISSUER
                        + "\",\"subject\":\""
                        + grantee
                        + "\"},\"authority\":\""
                        + authority
                        + "\",\"scope\":{"
                        + scopeJson
                        + "},\"reasonCode\":\"ACCESS_SETUP\"}"))
        .andExpect(status().isCreated());
  }

  private void grantService(
      String clientId, String authority, String grantOrganization, String grantFacility, String key)
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(
                    "{\"principal\":{\"kind\":\"SERVICE\",\"issuer\":\""
                        + ISSUER
                        + "\",\"clientId\":\""
                        + clientId
                        + "\"},\"authority\":\""
                        + authority
                        + "\",\"scope\":{\"kind\":\"FACILITY\",\"organization\":{"
                        + ref(grantOrganization, "Organization")
                        + "},\"facility\":{"
                        + ref(grantFacility, "Facility")
                        + "}},\"reasonCode\":\"SERVICE_SETUP\"}"))
        .andExpect(status().isCreated());
  }

  private void clearDirectory() {
    jdbc.update("DELETE FROM directory.command_receipt");
    jdbc.update("DELETE FROM directory.external_identifier_revision");
    jdbc.update("DELETE FROM directory.external_identifier");
    jdbc.update("DELETE FROM directory.facility_capability_assertion_revision");
    jdbc.update("DELETE FROM directory.facility_capability_assertion");
    jdbc.update("DELETE FROM directory.healthcare_service_revision");
    jdbc.update("DELETE FROM directory.healthcare_service");
    jdbc.update("DELETE FROM directory.care_capability_revision");
    jdbc.update("UPDATE directory.care_capability SET parent_capability_id = NULL");
    jdbc.update("DELETE FROM directory.care_capability");
    jdbc.update("DELETE FROM directory.location_revision");
    jdbc.update("DELETE FROM directory.location");
    jdbc.update("DELETE FROM directory.facility_revision");
    jdbc.update("DELETE FROM directory.organization_revision");
    jdbc.update("DELETE FROM directory.facility");
    jdbc.update("DELETE FROM directory.organization");
  }

  private void seedPlatformAdministrator() {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, grant_status, created_at) VALUES (gen_random_uuid(), 'HUMAN', ?, "
            + "'subject', ?, 'AccessGrantAdministration', 'PLATFORM', 'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        ADMIN);
  }

  private void seedCapacityPolicyAdministrator() {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, grant_status, created_at) VALUES (gen_random_uuid(), 'HUMAN', ?, "
            + "'subject', ?, 'CapacityPolicyAdministration', 'PLATFORM', 'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        ADMIN);
  }

  private String observationBody(String sourceObservationId, int quantity, String observedAt) {
    return "{\"dimensionCode\":\"COORDINATION_BEDS\",\"sourceCode\":\"manual\","
        + "\"sourceIssuer\":\"urn:coordination:manual-entry\",\"sourceObservationId\":\""
        + sourceObservationId
        + "\",\"observationKind\":\"VALUE\",\"valueKind\":\"QUANTITY\","
        + "\"quantity\":"
        + quantity
        + ",\"state\":null,\"unit\":\"bed\",\"observedAt\":\""
        + observedAt
        + "\",\"validUntil\":null,\"reportingMethod\":\"MANUAL_ENTRY\","
        + "\"reasonCode\":\"CAPACITY_REPORT\"}";
  }

  private String correctionBody(String sourceObservationId, int quantity) {
    return "{\"dimensionCode\":\"COORDINATION_BEDS\",\"sourceCode\":\"manual\",\"sourceIssuer\":\"urn:coordination:manual-entry\","
        + "\"sourceObservationId\":\""
        + sourceObservationId
        + "\",\"observationKind\":\"VALUE\",\"valueKind\":\"QUANTITY\","
        + "\"quantity\":"
        + quantity
        + ",\"state\":null,\"unit\":\"bed\",\"observedAt\":\"2026-10-08T09:30:00Z\","
        + "\"validUntil\":null,\"reportingMethod\":\"MANUAL_CORRECTION\","
        + "\"reasonCode\":\"SOURCE_CORRECTION\"}";
  }

  private String retractionBody(String sourceObservationId) {
    return "{\"dimensionCode\":\"COORDINATION_BEDS\",\"sourceCode\":\"manual\",\"sourceIssuer\":\"urn:coordination:manual-entry\","
        + "\"sourceObservationId\":\""
        + sourceObservationId
        + "\",\"observationKind\":\"RETRACTION\",\"valueKind\":null,\"quantity\":null,"
        + "\"state\":null,\"unit\":null,\"observedAt\":\"2026-10-08T09:40:00Z\","
        + "\"validUntil\":null,\"reportingMethod\":\"MANUAL_CORRECTION\","
        + "\"reasonCode\":\"SOURCE_RETRACTION\"}";
  }

  private static String directoryOrganizationBody(String name) {
    return "{\"name\":\""
        + name
        + "\",\"source\":{\"issuer\":\"urn:registry:synthetic\",\"resourceType\":\"Organization\",\"reference\":\"org-01\"},"
        + "\"provenance\":{\"method\":\"MANUAL_REVIEW\",\"observedAt\":\"2026-10-08T09:00:00Z\",\"reasonCode\":\"INITIAL_ENTRY\"}}";
  }

  private static String directoryFacilityBody(String organizationId, String name) {
    return "{\"name\":\""
        + name
        + "\",\"managingOrganizationId\":\""
        + organizationId
        + "\",\"source\":{\"issuer\":\"urn:registry:synthetic\",\"resourceType\":\"Facility\",\"reference\":\"facility-01\"},"
        + "\"provenance\":{\"method\":\"MANUAL_REVIEW\",\"observedAt\":\"2026-10-08T09:00:00Z\",\"reasonCode\":\"INITIAL_ENTRY\"}}";
  }

  private static String ref(String identifier, String resourceType) {
    return "\"issuer\":\"" + DIRECTORY_ISSUER + "\",\"identifier\":\"" + identifier + "\"";
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  private String token(String subject) throws Exception {
    return token(subject, null);
  }

  private String serviceToken(String clientId) throws Exception {
    return token("synthetic-service-subject", clientId);
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
    if (clientId != null) {
      claims.claim("client_id", clientId);
    }
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
      throw new IllegalStateException("Could not create a synthetic signing key.", exception);
    }
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }
}
