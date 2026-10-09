package org.ayuvahak.integration;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Synthetic cross-context scenario for the integrated Directory, Capacity, and Referral workflow.
 */
@SpringBootTest(
    classes = AyuVahakApplication.class,
    properties = {
      "management.tracing.sampling.probability=0.0",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-integration-service",
      "ayuvahak.authentication.allow-http=true"
    })
@AutoConfigureMockMvc
@Testcontainers
class DirectoryCapacityReferralScenarioIT {

  private static final String ISSUER = "https://identity.example.test/realms/cross-phase";
  private static final String AUDIENCE = "ayuvahak-api";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String CURATOR = "synthetic-cross-phase-curator";
  private static final String CAPACITY_ADMIN = "synthetic-cross-phase-capacity-admin";
  private static final String CAPACITY_REPORTER = "synthetic-cross-phase-reporter";
  private static final String REFERRAL_ORIGINATOR = "synthetic-cross-phase-originator";
  private static final String CAPABILITY = "SYNTHETIC_EMERGENCY_SUPPORT";
  private static final String DIMENSION = "COORDINATION_BEDS";
  private static final String TEST_PASSWORD = "local-test-only";
  private static final RSAKey SIGNING_KEY = newSigningKey("cross-phase-fixture");
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

  @Test
  void directoryFactsCapacityEvidenceAndReferralRequirementComposeWithoutCrossOwnership()
      throws Exception {
    grantPlatform(CURATOR, "DirectoryCuration");
    grantPlatform(CURATOR, "DirectoryVocabularyAdministration");
    grantPlatform(CAPACITY_ADMIN, "CapacityPolicyAdministration");

    String organizationId = createOrganization("synthetic-cross-phase-organization");
    String originFacilityId =
        createFacility("synthetic-cross-phase-origin", organizationId, "Synthetic Origin");
    String destinationFacilityId =
        createFacility(
            "synthetic-cross-phase-destination", organizationId, "Synthetic Destination");
    createCapability();

    grantFacility(CAPACITY_REPORTER, "CapacityReporting", organizationId, originFacilityId);
    grantFacility(CAPACITY_REPORTER, "CapacityEvidenceReview", organizationId, originFacilityId);
    grantFacility(REFERRAL_ORIGINATOR, "ReferralCreate", organizationId, originFacilityId);
    grantFacility(REFERRAL_ORIGINATOR, "ReferralSubmit", organizationId, originFacilityId);
    grantFacility(REFERRAL_ORIGINATOR, "ReferralRead", organizationId, originFacilityId);

    defineCapacityDimension();
    configureCapacityPolicy();
    recordCapacityObservation(organizationId, originFacilityId);
    mockMvc
        .perform(
            get("/api/v1/capacity/facilities/{facilityId}/current", originFacilityId)
                .header("Authorization", bearer(token(CAPACITY_REPORTER)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .param("dimension", DIMENSION))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[0].dimensionCode").value(DIMENSION))
        .andExpect(jsonPath("$.dimensions[0].freshness").value("CURRENT"))
        .andExpect(jsonPath("$.dimensions[0].quantity").value(4));
    mockMvc
        .perform(
            post("/api/v1/capacity/readiness-evaluations")
                .header("Authorization", bearer(token(CAPACITY_REPORTER)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .contentType("application/json")
                .content(
                    "{\"facilityId\":\""
                        + originFacilityId
                        + "\",\"requiredDimensions\":[\""
                        + DIMENSION
                        + "\"],\"evaluationTime\":\""
                        + Instant.now()
                        + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("READY"))
        .andExpect(jsonPath("$.dimensions[0].dimensionCode").value(DIMENSION))
        .andExpect(jsonPath("$.dimensions[0].outcome").value("READY"))
        .andExpect(jsonPath("$.dimensions[0].quantity").value(4));

    String referralId = createReferral(organizationId, originFacilityId);
    mockMvc
        .perform(
            post("/api/v1/referrals/{referralId}/submission", referralId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .header("Idempotency-Key", "synthetic-cross-phase-submit")
                .contentType("application/json")
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.submittedRevision.careRequirement.requiredCareCapabilityCodes[0]")
                .value(CAPABILITY));

    assertThat(destinationFacilityId).isNotBlank();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM referral.required_care_capability "
                    + "WHERE referral_id = ? AND capability_code = ?",
                Integer.class,
                UUID.fromString(referralId),
                CAPABILITY))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'capacity' "
                    + "AND column_name ILIKE '%capability%'",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'directory' "
                    + "AND column_name ILIKE '%capacity%'",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'referral' "
                    + "AND (column_name ILIKE '%capacity_dimension%' "
                    + "OR column_name = 'dimension_code')",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT schema_name FROM information_schema.schemata "
                    + "WHERE schema_name IN ('matching', 'transfer', 'transport', 'analytics')",
                String.class))
        .isEmpty();
  }

  private String createOrganization(String key) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/directory/organizations")
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(
                        "{\"name\":\"Synthetic Cross-Phase Organization\","
                            + "\"source\":{\"issuer\":\"urn:synthetic:directory\","
                            + "\"resourceType\":\"Organization\",\"reference\":\"org-"
                            + UUID.randomUUID()
                            + "\"},\"provenance\":{\"method\":\"MANUAL_REVIEW\","
                            + "\"observedAt\":\"2026-10-09T09:00:00Z\","
                            + "\"reasonCode\":\"SYNTHETIC_SETUP\"}}"))
            .andExpect(status().isCreated())
            .andReturn();
    return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  private String createFacility(String key, String organizationId, String name) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/directory/facilities")
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(
                        "{\"name\":\""
                            + name
                            + "\",\"managingOrganizationId\":\""
                            + organizationId
                            + "\",\"source\":{\"issuer\":\"urn:synthetic:directory\","
                            + "\"resourceType\":\"Facility\",\"reference\":\"facility-"
                            + UUID.randomUUID()
                            + "\"},\"provenance\":{\"method\":\"MANUAL_REVIEW\","
                            + "\"observedAt\":\"2026-10-09T09:00:00Z\","
                            + "\"reasonCode\":\"SYNTHETIC_SETUP\"}}"))
            .andExpect(status().isCreated())
            .andReturn();
    return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  private void createCapability() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/directory/capabilities")
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "synthetic-cross-phase-capability")
                .contentType("application/json")
                .content(
                    "{\"code\":\""
                        + CAPABILITY
                        + "\",\"displayName\":\"Synthetic emergency support\","
                        + "\"source\":{\"issuer\":\"urn:synthetic:directory\","
                        + "\"resourceType\":\"CareCapability\",\"reference\":\"capability-"
                        + UUID.randomUUID()
                        + "\"},\"provenance\":{\"method\":\"MANUAL_REVIEW\","
                        + "\"observedAt\":\"2026-10-09T09:00:00Z\","
                        + "\"reasonCode\":\"SYNTHETIC_SETUP\"}}"))
        .andExpect(status().isCreated());
  }

  private void defineCapacityDimension() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/capacity/policies/dimensions")
                .header("Authorization", bearer(token(CAPACITY_ADMIN)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "synthetic-cross-phase-dimension")
                .contentType("application/json")
                .content(
                    "{\"code\":\""
                        + DIMENSION
                        + "\",\"expectedVersion\":0,\"displayName\":\"Coordination beds\","
                        + "\"meaning\":\"Synthetic staffed beds available to coordination\","
                        + "\"valueKind\":\"QUANTITY\",\"unit\":\"bed\","
                        + "\"minimumValue\":0,\"maximumValue\":500,\"allowedStates\":[],"
                        + "\"usedForReadiness\":true,\"reservable\":false,"
                        + "\"reasonCode\":\"SYNTHETIC_SETUP\"}"))
        .andExpect(status().isCreated());
  }

  private void configureCapacityPolicy() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/capacity/policies/dimensions/{dimension}/policy", DIMENSION)
                .header("Authorization", bearer(token(CAPACITY_ADMIN)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "synthetic-cross-phase-policy")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":0,\"dimensionVersion\":1,\"sources\":["
                        + "{\"sourceCode\":\"manual\",\"priority\":0,"
                        + "\"fallbackWhenHigherPriorityAbsent\":false,"
                        + "\"fallbackWhenHigherPriorityStale\":false}],"
                        + "\"maximumObservationAgeSeconds\":600,\"defaultValiditySeconds\":300,"
                        + "\"futureSkewToleranceSeconds\":30,\"minimumPassingQuantity\":1,"
                        + "\"passingStates\":[],\"reasonCode\":\"SYNTHETIC_SETUP\"}"))
        .andExpect(status().isCreated());
  }

  private void recordCapacityObservation(String organizationId, String facilityId)
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/capacity/observations")
                .header("Authorization", bearer(token(CAPACITY_REPORTER)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", "synthetic-cross-phase-observation")
                .contentType("application/json")
                .content(
                    "{\"dimensionCode\":\""
                        + DIMENSION
                        + "\",\"sourceCode\":\"manual\","
                        + "\"sourceIssuer\":\"urn:synthetic:capacity-source\","
                        + "\"sourceObservationId\":\"cross-phase-observation-01\","
                        + "\"observationKind\":\"VALUE\",\"valueKind\":\"QUANTITY\","
                        + "\"quantity\":4,\"state\":null,\"unit\":\"bed\","
                        + "\"observedAt\":\""
                        + Instant.now().minusSeconds(2)
                        + "\",\"validUntil\":null,\"reportingMethod\":\"MANUAL_ENTRY\","
                        + "\"reasonCode\":\"CAPACITY_REPORT\"}"))
        .andExpect(status().isCreated());
  }

  private String createReferral(String organizationId, String facilityId) throws Exception {
    Instant start = Instant.now().plusSeconds(3600);
    Instant end = start.plusSeconds(3600);
    String body =
        "{\"patientReference\":{\"issuer\":\"urn:synthetic:source-ehr\","
            + "\"resourceType\":\"Patient\",\"identifier\":\"synthetic-patient-01\","
            + "\"encounterReference\":null},\"careRequirement\":{"
            + "\"requiredCareCapabilityCodes\":[\""
            + CAPABILITY
            + "\"],\"requestedStart\":\""
            + start
            + "\",\"requestedEnd\":\""
            + end
            + "\",\"permittedGeographyCodes\":[\"SYNTHETIC_DISTRICT_01\"],"
            + "\"selectedDestinationOrganizationId\":null,\"priorityCode\":\"ROUTINE\"}}";
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/referrals")
                    .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", facilityId)
                    .header("Idempotency-Key", "synthetic-cross-phase-referral")
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isCreated())
            .andExpect(
                jsonPath("$.careRequirement.requiredCareCapabilityCodes[0]").value(CAPABILITY))
            .andReturn();
    return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  private void grantPlatform(String subject, String authority) {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, grant_status, created_at) VALUES (gen_random_uuid(), 'HUMAN', ?, "
            + "'subject', ?, ?, 'PLATFORM', 'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        subject,
        authority);
  }

  private void grantFacility(
      String subject, String authority, String organizationId, String facilityId) {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, organization_issuer, organization_identifier_type, "
            + "organization_identifier, facility_issuer, facility_identifier_type, "
            + "facility_identifier, grant_status, created_at) VALUES (gen_random_uuid(), "
            + "'HUMAN', ?, 'subject', ?, ?, 'FACILITY', ?, 'Organization', ?, ?, 'Facility', ?, "
            + "'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        subject,
        authority,
        DIRECTORY_ISSUER,
        organizationId,
        DIRECTORY_ISSUER,
        facilityId);
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

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private static RSAKey newSigningKey(String keyId) {
    try {
      return new RSAKeyGenerator(2048).keyID(keyId).generate();
    } catch (Exception exception) {
      throw new IllegalStateException("Could not create a synthetic signing key.", exception);
    }
  }
}
