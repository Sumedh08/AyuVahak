package org.ayuvahak.referral.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import java.util.UUID;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Authenticated command-seam tests against disposable local PostgreSQL/PostGIS. */
@SpringBootTest(
    classes = AyuVahakApplication.class,
    properties = {
      "management.tracing.sampling.probability=0.0",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-referral-service",
      "ayuvahak.authentication.allow-http=true"
    })
@AutoConfigureMockMvc
@Testcontainers
class ReferralDraftCommandsApiIT {

  private static final String TEST_PASSWORD = "local-test-only";
  private static final String ISSUER = "https://identity.example.test/realms/ayuvahak";
  private static final String AUDIENCE = "ayuvahak-api";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String ADMIN = "synthetic-referral-admin";
  private static final String CURATOR = "synthetic-referral-curator";
  private static final String ORIGINATOR = "synthetic-referral-originator";
  private static final String UNAUTHORIZED = "synthetic-referral-unprivileged";
  private static final String CAPABILITY = "SYNTHETIC_EMERGENCY_SUPPORT";
  private static final RSAKey SIGNING_KEY = newSigningKey("referral-draft-fixture");
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
  private String capabilityId;

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
  void prepareSyntheticOrigin() throws Exception {
    if (Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT to_regclass('referral.draft') IS NOT NULL", Boolean.class))) {
      jdbc.update("DELETE FROM referral.command_receipt");
      jdbc.update("DELETE FROM referral.required_care_capability");
      jdbc.update("DELETE FROM referral.permitted_geography_code");
      jdbc.update("DELETE FROM referral.draft");
    }
    jdbc.update("DELETE FROM audit.audit_record");
    jdbc.update("DELETE FROM platform_eventing.outbox_delivery");
    jdbc.update("DELETE FROM platform_eventing.internal_event");
    jdbc.update("DELETE FROM identity_access.grant_command_receipt");
    jdbc.update("DELETE FROM identity_access.bootstrap_marker");
    jdbc.update("DELETE FROM identity_access.authorization_grant");
    clearDirectory();

    grant(ADMIN, "AccessGrantAdministration", "PLATFORM", null, null);
    grant(CURATOR, "DirectoryCuration", "PLATFORM", null, null);
    grant(CURATOR, "DirectoryVocabularyAdministration", "PLATFORM", null, null);
    organizationId = createOrganization();
    facilityId = createFacility();
    createCapability();
    grant(ORIGINATOR, "ReferralCreate", "FACILITY", organizationId, facilityId);
    grant(ORIGINATOR, "ReferralEdit", "FACILITY", organizationId, facilityId);
  }

  @Test
  void authorizedOriginCreatesAndRetriesAMinimalDraftWithoutLeakingPatientReferenceToAudit()
      throws Exception {
    String command = draftBody("opaque-synthetic-patient-01");

    MvcResult first =
        mockMvc
            .perform(
                post("/api/v1/referrals")
                    .header("Authorization", bearer(token(ORIGINATOR)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", facilityId)
                    .header("Idempotency-Key", "draft-create-01")
                    .contentType("application/json")
                    .content(command))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(
                jsonPath("$.careRequirement.requiredCareCapabilityCodes[0]").value(CAPABILITY))
            .andExpect(
                jsonPath("$.patientReference.identifier").value("opaque-synthetic-patient-01"))
            .andReturn();
    String referralId =
        com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.id");

    mockMvc
        .perform(
            post("/api/v1/referrals")
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", "draft-create-01")
                .contentType("application/json")
                .content(command))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.id").value(referralId));

    assertThat(count("SELECT COUNT(*) FROM referral.draft")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM referral.required_care_capability")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM referral.command_receipt")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record "
                    + "WHERE action_code = 'REFERRAL_DRAFT_CREATE'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE actor_reference = ?",
                Integer.class,
                "opaque-synthetic-patient-01"))
        .isZero();

    mockMvc
        .perform(
            post("/api/v1/referrals")
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", "draft-create-01")
                .contentType("application/json")
                .content(draftBody("opaque-synthetic-patient-changed")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    assertThat(count("SELECT COUNT(*) FROM referral.draft")).isEqualTo(1);
  }

  @Test
  void authorizedOriginCanEditDraftWithExpectedVersionWithoutChangingItsOrigin() throws Exception {
    String referralId = createDraft("draft-edit-create", "opaque-synthetic-patient-edit-01");
    String edited = draftBody("opaque-synthetic-patient-edit-02");
    String patch = edited.substring(0, edited.length() - 2) + ",\"expectedVersion\":1}";

    mockMvc
        .perform(
            patch("/api/v1/referrals/{referralId}/draft", referralId)
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", "draft-edit-01")
                .contentType("application/json")
                .content(patch))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(referralId))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.origin.organizationId").value(organizationId))
        .andExpect(jsonPath("$.origin.facilityId").value(facilityId))
        .andExpect(
            jsonPath("$.patientReference.identifier").value("opaque-synthetic-patient-edit-02"));

    mockMvc
        .perform(
            patch("/api/v1/referrals/{referralId}/draft", referralId)
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", "draft-edit-01")
                .contentType("application/json")
                .content(patch))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.id").value(referralId))
        .andExpect(jsonPath("$.version").value(2));

    assertThat(count("SELECT COUNT(*) FROM referral.draft WHERE version = 2")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record "
                    + "WHERE action_code = 'REFERRAL_DRAFT_EDIT'",
                Integer.class))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM referral.command_receipt")).isEqualTo(2);

    String attemptedBody = draftBody("opaque-synthetic-patient-edit-03");
    String attemptedOriginChange =
        attemptedBody.substring(0, attemptedBody.length() - 2)
            + ",\"expectedVersion\":2,\"origin\":{\"organizationId\":\""
            + UUID.randomUUID()
            + "\"}}";
    mockMvc
        .perform(
            patch("/api/v1/referrals/{referralId}/draft", referralId)
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", "draft-edit-origin-change")
                .contentType("application/json")
                .content(attemptedOriginChange))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REFERRAL_REQUIREMENT_INVALID"));
    assertThat(count("SELECT COUNT(*) FROM referral.draft WHERE version = 2")).isEqualTo(1);
  }

  @Test
  void staleDraftVersionConflictsWithoutChangingDraft() throws Exception {
    String referralId = createDraft("draft-stale-create", "opaque-synthetic-patient-stale-01");
    String body = draftBody("opaque-synthetic-patient-stale-02");
    body = body.substring(0, body.length() - 2) + ",\"expectedVersion\":2}";

    mockMvc
        .perform(
            patch("/api/v1/referrals/{referralId}/draft", referralId)
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", "draft-edit-stale-01")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REFERRAL_VERSION_CONFLICT"));

    assertThat(count("SELECT COUNT(*) FROM referral.draft WHERE version = 1")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT patient_identifier FROM referral.draft WHERE referral_id = ?::uuid",
                String.class,
                referralId))
        .isEqualTo("opaque-synthetic-patient-stale-01");
    assertThat(count("SELECT COUNT(*) FROM referral.command_receipt")).isEqualTo(1);
  }

  @Test
  void malformedPatientReferenceAndInvalidTimeWindowFailClosed() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/referrals")
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", "draft-malformed-patient")
                .contentType("application/json")
                .content(
                    draftBody("opaque-synthetic-patient-malformed")
                        .replace("Patient", "Observation")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REFERRAL_REQUIREMENT_INVALID"));

    String reversedWindow =
        draftBody("opaque-synthetic-patient-invalid-window")
            .replace("2026-10-09T10:00:00Z", "2026-10-09T13:00:00Z");
    mockMvc
        .perform(
            post("/api/v1/referrals")
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", "draft-invalid-window")
                .contentType("application/json")
                .content(reversedWindow))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REFERRAL_REQUIREMENT_INVALID"));

    assertThat(count("SELECT COUNT(*) FROM referral.draft")).isZero();
    assertThat(count("SELECT COUNT(*) FROM referral.command_receipt")).isZero();
  }

  @Test
  void unknownCapabilityAndUnauthorizedActorCannotCreateDraft() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/referrals")
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", "draft-unknown-capability")
                .contentType("application/json")
                .content(
                    draftBody("opaque-synthetic-patient-unknown-capability")
                        .replace(CAPABILITY, "SYNTHETIC_UNKNOWN_CAPABILITY")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("REFERRAL_CAPABILITY_UNKNOWN"));

    mockMvc
        .perform(
            post("/api/v1/directory/capabilities/{capabilityId}/deactivation", capabilityId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "referral-deactivate-capability")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":1,\"provenance\":{\"method\":\"MANUAL_REVIEW\","
                        + "\"observedAt\":\"2026-10-09T09:00:00Z\","
                        + "\"reasonCode\":\"SYNTHETIC_TEST_DEACTIVATION\"}}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            post("/api/v1/referrals")
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", "draft-inactive-capability")
                .contentType("application/json")
                .content(draftBody("opaque-synthetic-patient-inactive-capability")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("REFERRAL_CAPABILITY_UNKNOWN"));

    mockMvc
        .perform(
            post("/api/v1/referrals")
                .header("Authorization", bearer(token(UNAUTHORIZED)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", "draft-unauthorized")
                .contentType("application/json")
                .content(draftBody("opaque-synthetic-patient-unauthorized")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));

    assertThat(count("SELECT COUNT(*) FROM referral.draft")).isZero();
    assertThat(count("SELECT COUNT(*) FROM referral.command_receipt")).isZero();
  }

  @Test
  void missingMutationAuditRollsBackDraftAndIdempotencyReceipt() throws Exception {
    jdbc.execute(
        "CREATE FUNCTION audit.reject_referral_draft_create() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN "
            + "IF NEW.action_code = 'REFERRAL_DRAFT_CREATE' THEN "
            + "RAISE EXCEPTION 'synthetic audit failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_referral_draft_create BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION audit.reject_referral_draft_create()");
    try {
      mockMvc
          .perform(
              post("/api/v1/referrals")
                  .header("Authorization", bearer(token(ORIGINATOR)))
                  .header("X-AyuVahak-Execution-Scope", "FACILITY")
                  .header("X-AyuVahak-Organization-Id", organizationId)
                  .header("X-AyuVahak-Facility-Id", facilityId)
                  .header("Idempotency-Key", "draft-audit-failure")
                  .contentType("application/json")
                  .content(draftBody("opaque-synthetic-patient-audit-failure")))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));

      assertThat(count("SELECT COUNT(*) FROM referral.draft")).isZero();
      assertThat(count("SELECT COUNT(*) FROM referral.command_receipt")).isZero();
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS reject_referral_draft_create ON audit.audit_record");
      jdbc.execute("DROP FUNCTION IF EXISTS audit.reject_referral_draft_create()");
    }
  }

  private String createDraft(String idempotencyKey, String patientIdentifier) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/referrals")
                    .header("Authorization", bearer(token(ORIGINATOR)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", facilityId)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType("application/json")
                    .content(draftBody(patientIdentifier)))
            .andExpect(status().isCreated())
            .andReturn();
    return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  private String createOrganization() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/directory/organizations")
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", "referral-origin-organization")
                    .contentType("application/json")
                    .content(
                        "{\"name\":\"Synthetic Referral Origin\","
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

  private String createFacility() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/directory/facilities")
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", "referral-origin-facility")
                    .contentType("application/json")
                    .content(
                        "{\"name\":\"Synthetic Referral Facility\",\"managingOrganizationId\":\""
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
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/directory/capabilities")
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", "referral-capability")
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
            .andExpect(status().isCreated())
            .andReturn();
    capabilityId =
        com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  private void grant(
      String subject, String authority, String scope, String organization, String facility) {
    if ("PLATFORM".equals(scope)) {
      jdbc.update(
          "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
              + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
              + "scope_kind, grant_status, created_at) VALUES (gen_random_uuid(), 'HUMAN', ?, "
              + "'subject', ?, ?, 'PLATFORM', 'ACTIVE', CURRENT_TIMESTAMP)",
          ISSUER,
          subject,
          authority);
      return;
    }
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
        organization,
        DIRECTORY_ISSUER,
        facility);
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

  private String draftBody(String patientIdentifier) {
    return """
        {
          "patientReference": {
            "issuer": "urn:synthetic:source-ehr",
            "resourceType": "Patient",
            "identifier": "%s",
            "encounterReference": null
          },
          "careRequirement": {
            "requiredCareCapabilityCodes": ["%s"],
            "requestedStart": "2026-10-09T10:00:00Z",
            "requestedEnd": "2026-10-09T12:00:00Z",
            "permittedGeographyCodes": ["SYNTHETIC_DISTRICT_01"],
            "selectedDestinationOrganizationId": null,
            "priorityCode": "ROUTINE"
          }
        }
        """
        .formatted(patientIdentifier, CAPABILITY);
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

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }
}
