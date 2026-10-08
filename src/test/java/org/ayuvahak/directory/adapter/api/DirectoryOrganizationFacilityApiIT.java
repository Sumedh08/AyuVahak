package org.ayuvahak.directory.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.ayuvahak.platform.AyuVahakApplication;
import org.ayuvahak.platform.authorization.adapter.api.PlatformAccessGrantController;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionScopeOwnerValidator;
import org.ayuvahak.platform.executioncontext.ExecutionScopeValidator;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
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
class DirectoryOrganizationFacilityApiIT {

  private static final String TEST_PASSWORD = "local-test-only";
  private static final String ISSUER = "https://identity.example.test/realms/ayuvahak";
  private static final String AUDIENCE = "ayuvahak-api";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String ADMIN = "synthetic-admin-17";
  private static final String CURATOR = "synthetic-curator-21";
  private static final String ORG_CURATOR = "synthetic-org-curator-22";
  private static final String FACILITY_CURATOR = "synthetic-facility-curator-23";
  private static final String UNAUTHORIZED = "synthetic-unprivileged-24";
  private static final RSAKey SIGNING_KEY = newSigningKey("directory-api-fixture");
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
  @Autowired private List<ExecutionScopeOwnerValidator> scopeOwners;
  @Autowired private ExecutionScopeValidator executionScopeValidator;

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
    jdbc.update("DELETE FROM directory.command_receipt");
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
    jdbc.update("DELETE FROM audit.audit_record");
    jdbc.update("DELETE FROM platform_eventing.outbox_delivery");
    jdbc.update("DELETE FROM platform_eventing.internal_event");
    jdbc.update("DELETE FROM identity_access.grant_command_receipt");
    jdbc.update("DELETE FROM identity_access.bootstrap_marker");
    jdbc.update("DELETE FROM identity_access.authorization_grant");
    jdbc.execute("DROP TRIGGER IF EXISTS test_fail_audit_insert ON audit.audit_record");
    jdbc.execute("DROP FUNCTION IF EXISTS test_fail_audit_insert()");
    seedPlatformAdministrator();
  }

  @Test
  void createsAndReadsProvenanceBackedOrganizationAndFacilityAtExactScopes() throws Exception {
    grant(ADMIN, CURATOR, "DirectoryCuration", "PLATFORM", null, null, "seed-curator-01");
    String organizationId = createOrganization(CURATOR, "org-create-01", "North Coordination");
    grant(
        ADMIN,
        ORG_CURATOR,
        "DirectoryCuration",
        "ORGANIZATION",
        organizationId,
        null,
        "seed-org-curator-01");
    grant(
        ADMIN,
        ORG_CURATOR,
        "DirectoryRead",
        "ORGANIZATION",
        organizationId,
        null,
        "seed-org-reader-01");

    String facilityId =
        createFacility(
            ORG_CURATOR,
            "ORGANIZATION",
            "facility-create-01",
            "North Receiving Site",
            organizationId);
    assertThat(
            createFacility(
                ORG_CURATOR,
                "ORGANIZATION",
                "facility-create-01",
                "North Receiving Site",
                organizationId))
        .isEqualTo(facilityId);
    grant(
        ADMIN,
        FACILITY_CURATOR,
        "DirectoryRead",
        "FACILITY",
        organizationId,
        facilityId,
        "seed-facility-reader-01");

    mockMvc
        .perform(
            get("/api/v1/directory/organizations/{id}", organizationId)
                .header("Authorization", bearer(token(ORG_CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "ORGANIZATION")
                .header("X-AyuVahak-Organization-Id", organizationId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(organizationId))
        .andExpect(jsonPath("$.name").value("North Coordination"))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.source.issuer").value("urn:synthetic:facility-registry"))
        .andExpect(jsonPath("$.provenance.method").value("MANUAL_REVIEW"));

    mockMvc
        .perform(
            get("/api/v1/directory/facilities/{id}", facilityId)
                .header("Authorization", bearer(token(FACILITY_CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(facilityId))
        .andExpect(jsonPath("$.managingOrganizationId").value(organizationId))
        .andExpect(jsonPath("$.name").value("North Receiving Site"))
        .andExpect(jsonPath("$.version").value(1));

    assertThat(count("SELECT COUNT(*) FROM directory.organization")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM directory.facility")).isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code IN "
                    + "('DIRECTORY_ORGANIZATION_CREATE', 'DIRECTORY_FACILITY_CREATE')"))
        .isEqualTo(2);
  }

  @Test
  void replaysCreateAndCorrectionCommandsAndRejectsStaleVersions() throws Exception {
    grant(ADMIN, CURATOR, "DirectoryCuration", "PLATFORM", null, null, "seed-curator-02");
    grant(ADMIN, CURATOR, "DirectoryRead", "PLATFORM", null, null, "seed-reader-02");
    String organizationId = createOrganization(CURATOR, "org-idempotency-01", "West Coordination");
    assertThat(createOrganization(CURATOR, "org-idempotency-01", "West Coordination"))
        .isEqualTo(organizationId);
    assertThat(count("SELECT COUNT(*) FROM directory.organization")).isEqualTo(1);

    mockMvc
        .perform(
            patch("/api/v1/directory/organizations/{id}", organizationId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "org-correction-01")
                .contentType("application/json")
                .content(correctionBody(1, "West Regional Coordination", "CORRECTION_01")))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            patch("/api/v1/directory/organizations/{id}", organizationId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "org-correction-01")
                .contentType("application/json")
                .content(correctionBody(1, "West Regional Coordination", "CORRECTION_01")))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            get("/api/v1/directory/organizations/{id}", organizationId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("West Regional Coordination"))
        .andExpect(jsonPath("$.version").value(2));

    mockMvc
        .perform(
            patch("/api/v1/directory/organizations/{id}", organizationId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "org-correction-stale-01")
                .contentType("application/json")
                .content(correctionBody(1, "Stale Update", "CORRECTION_02")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DIRECTORY_VERSION_CONFLICT"));
    assertThat(count("SELECT COUNT(*) FROM directory.organization_revision")).isEqualTo(1);

    mockMvc
        .perform(
            patch("/api/v1/directory/organizations/{id}", organizationId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "org-correction-reused-01")
                .contentType("application/json")
                .content(correctionBody(2, "Another Name", "CORRECTION_03")))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            patch("/api/v1/directory/organizations/{id}", organizationId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "org-correction-reused-01")
                .contentType("application/json")
                .content(correctionBody(2, "Different Name", "CORRECTION_03")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
  }

  @Test
  void concurrentCreateRetryProducesOneOrganizationAndOneAuditRecord() throws Exception {
    grant(ADMIN, CURATOR, "DirectoryCuration", "PLATFORM", null, null, "seed-curator-concurrent");
    String authorization = bearer(token(CURATOR));
    String body = organizationBody("Concurrent Coordination");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      var first = workers.submit(() -> concurrentCreate(authorization, body, ready, start));
      var second = workers.submit(() -> concurrentCreate(authorization, body, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      MvcResult firstResult = first.get(10, TimeUnit.SECONDS);
      MvcResult secondResult = second.get(10, TimeUnit.SECONDS);
      assertThat(firstResult.getResponse().getStatus()).isEqualTo(201);
      assertThat(secondResult.getResponse().getStatus()).isEqualTo(201);
      String firstId =
          com.jayway.jsonpath.JsonPath.read(firstResult.getResponse().getContentAsString(), "$.id");
      String secondId =
          com.jayway.jsonpath.JsonPath.read(
              secondResult.getResponse().getContentAsString(), "$.id");
      assertThat(secondId).isEqualTo(firstId);
      assertThat(count("SELECT COUNT(*) FROM directory.organization")).isEqualTo(1);
      assertThat(
              count(
                  "SELECT COUNT(*) FROM audit.audit_record "
                      + "WHERE action_code = 'DIRECTORY_ORGANIZATION_CREATE'"))
          .isEqualTo(1);
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void directoryOwnedScopesRejectMismatchedTargetsAndGrantAdministrationFailsClosed()
      throws Exception {
    grant(ADMIN, CURATOR, "DirectoryCuration", "PLATFORM", null, null, "seed-curator-03");
    String organizationId = createOrganization(CURATOR, "org-scope-a", "Scope A");
    String otherOrganizationId = createOrganization(CURATOR, "org-scope-b", "Scope B");
    String facilityId =
        createFacility(CURATOR, "PLATFORM", "facility-scope-a", "Facility A", organizationId);
    var mismatchedFacilityScope =
        RequestedExecutionScope.facility(
            new ExternalReference(
                new IssuerId(DIRECTORY_ISSUER), "Organization", otherOrganizationId),
            new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facilityId));
    assertThat(scopeOwners).hasSize(1);
    assertThat(scopeOwners.getFirst().isValid(mismatchedFacilityScope)).isFalse();
    assertThat(executionScopeValidator.isValidFor(null, mismatchedFacilityScope)).isFalse();

    grant(
        ADMIN,
        ORG_CURATOR,
        "DirectoryCuration",
        "ORGANIZATION",
        organizationId,
        null,
        "seed-org-curator-03");
    mockMvc
        .perform(
            post("/api/v1/directory/facilities")
                .header("Authorization", bearer(token(ORG_CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "ORGANIZATION")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("Idempotency-Key", "facility-wrong-org-01")
                .contentType("application/json")
                .content(facilityBody(otherOrganizationId, "Rejected Facility")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("DIRECTORY_SCOPE_MISMATCH"));

    String missingOrganizationId = InternalId.generate().toString();
    mockMvc
        .perform(
            post("/api/v1/directory/facilities")
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "facility-missing-org-01")
                .contentType("application/json")
                .content(facilityBody(missingOrganizationId, "Missing Owner Site")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DIRECTORY_NOT_FOUND"));

    String inactiveOrganizationId =
        createOrganization(CURATOR, "org-inactive-owner-01", "Inactive Owner");
    jdbc.update(
        "UPDATE directory.organization SET lifecycle_status = 'INACTIVE' "
            + "WHERE organization_id = ?",
        java.util.UUID.fromString(inactiveOrganizationId));
    mockMvc
        .perform(
            post("/api/v1/directory/facilities")
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "facility-inactive-org-01")
                .contentType("application/json")
                .content(facilityBody(inactiveOrganizationId, "Inactive Owner Site")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DIRECTORY_INACTIVE"));

    String invalidFacilityGrant =
        grantBody(
            "synthetic-reader-25", "DirectoryRead", "FACILITY", otherOrganizationId, facilityId);
    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN)))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                .header("Idempotency-Key", "invalid-owner-grant-01")
                .contentType("application/json")
                .content(invalidFacilityGrant))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_SCOPE"));

    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(ADMIN)))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                .header("Idempotency-Key", "self-grant-directory-01")
                .contentType("application/json")
                .content(grantBody(ADMIN, "DirectoryRead", "ORGANIZATION", organizationId, null)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));

    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(UNAUTHORIZED)))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                .header("Idempotency-Key", "unauthorized-grant-directory-01")
                .contentType("application/json")
                .content(
                    grantBody(
                        "synthetic-reader-26",
                        "DirectoryRead",
                        "ORGANIZATION",
                        organizationId,
                        null)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));

    mockMvc
        .perform(
            get("/api/v1/directory/facilities/{id}", facilityId)
                .header("Authorization", bearer(token(ORG_CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", otherOrganizationId)
                .header("X-AyuVahak-Facility-Id", facilityId))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_SCOPE"));

    assertThat(
            count(
                "SELECT COUNT(*) FROM identity_access.authorization_grant WHERE authority_code='DirectoryRead'"))
        .isZero();
    assertThat(count("SELECT COUNT(*) FROM directory.facility")).isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE outcome = 'DENIED' "
                    + "AND action_code IN ('ACCESS_GRANT_CREATE', 'DIRECTORY_FACILITY_CREATE')"))
        .isGreaterThanOrEqualTo(3);
  }

  @Test
  void failedRequiredAuditRollsBackTheFactAndItsOwnerLocalReceipt() throws Exception {
    grant(ADMIN, CURATOR, "DirectoryCuration", "PLATFORM", null, null, "seed-curator-04");
    jdbc.execute(
        "CREATE FUNCTION test_fail_audit_insert() RETURNS trigger LANGUAGE plpgsql AS "
            + "'BEGIN RAISE EXCEPTION ''synthetic audit failure''; END'");
    jdbc.execute(
        "CREATE TRIGGER test_fail_audit_insert BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION test_fail_audit_insert()");

    mockMvc
        .perform(
            post("/api/v1/directory/organizations")
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "audit-failure-org-01")
                .contentType("application/json")
                .content(organizationBody("Audit Must Roll Back")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));

    assertThat(count("SELECT COUNT(*) FROM directory.organization")).isZero();
    assertThat(count("SELECT COUNT(*) FROM directory.command_receipt")).isZero();
  }

  @Test
  void deactivationPreservesTheFacilityAndAdvancesItsVersion() throws Exception {
    grant(ADMIN, CURATOR, "DirectoryCuration", "PLATFORM", null, null, "seed-curator-05");
    grant(ADMIN, CURATOR, "DirectoryRead", "PLATFORM", null, null, "seed-reader-05");
    String organizationId =
        createOrganization(CURATOR, "org-deactivate-01", "Central Coordination");
    String facilityId =
        createFacility(
            CURATOR, "PLATFORM", "facility-deactivate-01", "Central Site", organizationId);

    String correction = correctionBody(1, "Central Hospital Annex", "FACILITY_CORRECTION_01");
    mockMvc
        .perform(
            patch("/api/v1/directory/facilities/{id}", facilityId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "facility-correction-01")
                .contentType("application/json")
                .content(correction))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            patch("/api/v1/directory/facilities/{id}", facilityId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "facility-correction-01")
                .contentType("application/json")
                .content(correction))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            post("/api/v1/directory/facilities/{id}/deactivation", facilityId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "facility-deactivate-01")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":2,\"provenance\":{" + provenanceJson("RETIRED") + "}}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            get("/api/v1/directory/facilities/{id}", facilityId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("INACTIVE"))
        .andExpect(jsonPath("$.name").value("Central Hospital Annex"))
        .andExpect(jsonPath("$.version").value(3));

    assertThat(count("SELECT COUNT(*) FROM directory.facility")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM directory.facility_revision")).isEqualTo(2);
  }

  @Test
  void createsCorrectsAndSelectsOnePrimaryLocation() throws Exception {
    grant(ADMIN, CURATOR, "DirectoryCuration", "PLATFORM", null, null, "seed-location-curator-01");
    grant(ADMIN, CURATOR, "DirectoryRead", "PLATFORM", null, null, "seed-location-reader-01");
    String organizationId = createOrganization(CURATOR, "location-org-01", "Location Authority");
    String facilityId =
        createFacility(
            CURATOR, "PLATFORM", "location-facility-01", "Main Facility", organizationId);

    String firstLocationId =
        createLocation(
            CURATOR,
            facilityId,
            "North Entrance",
            "-73.9857",
            "40.7484",
            true,
            "location-create-01");
    String secondLocationId =
        createLocation(
            CURATOR,
            facilityId,
            "Emergency Entrance",
            "-73.9840",
            "40.7490",
            true,
            "location-create-02");

    var firstLocationSummary =
        mockMvc
            .perform(
                get("/api/v1/directory/facilities/{id}", facilityId)
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.primaryLocationId").value(secondLocationId))
            .andExpect(jsonPath("$.locations[*].isPrimary", org.hamcrest.Matchers.hasItem(false)))
            .andExpect(jsonPath("$.locations[*].isPrimary", org.hamcrest.Matchers.hasItem(true)))
            .andExpect(
                jsonPath("$.locations[?(@.id == '" + firstLocationId + "')].version").value(2))
            .andExpect(
                jsonPath("$.locations[?(@.id == '" + secondLocationId + "')].version").value(1))
            .andReturn();
    List<Boolean> firstPrimaryFlags =
        com.jayway.jsonpath.JsonPath.read(
            firstLocationSummary.getResponse().getContentAsString(), "$.locations[*].isPrimary");
    assertThat(firstPrimaryFlags).containsExactlyInAnyOrder(false, true);

    mockMvc
        .perform(
            patch("/api/v1/directory/locations/{id}", firstLocationId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "location-correction-01")
                .contentType("application/json")
                .content(
                    locationCorrectionBody(
                        2, "North Entrance Corrected", "-73.9800", "40.7500", true)))
        .andExpect(status().isNoContent());

    var correctedLocationSummary =
        mockMvc
            .perform(
                get("/api/v1/directory/facilities/{id}", facilityId)
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.primaryLocationId").value(firstLocationId))
            .andExpect(
                jsonPath("$.locations[?(@.id == '" + firstLocationId + "')].name")
                    .value("North Entrance Corrected"))
            .andExpect(
                jsonPath("$.locations[?(@.id == '" + firstLocationId + "')].coordinates.longitude")
                    .value(-73.98))
            .andExpect(
                jsonPath("$.locations[?(@.id == '" + firstLocationId + "')].coordinates.latitude")
                    .value(40.75))
            .andExpect(
                jsonPath("$.locations[?(@.id == '" + firstLocationId + "')].version").value(3))
            .andExpect(
                jsonPath("$.locations[?(@.id == '" + secondLocationId + "')].version").value(2))
            .andReturn();
    List<Boolean> correctedPrimaryFlags =
        com.jayway.jsonpath.JsonPath.read(
            correctedLocationSummary.getResponse().getContentAsString(),
            "$.locations[*].isPrimary");
    assertThat(correctedPrimaryFlags).containsExactlyInAnyOrder(false, true);
    assertThat(secondLocationId).isNotEqualTo(firstLocationId);
  }

  @Test
  void rejectsInvalidOrIncompleteWgs84Coordinates() throws Exception {
    grant(
        ADMIN,
        CURATOR,
        "DirectoryCuration",
        "PLATFORM",
        null,
        null,
        "seed-invalid-location-curator-01");
    String organizationId =
        createOrganization(CURATOR, "invalid-location-org-01", "Coordinate Authority");
    String facilityId =
        createFacility(
            CURATOR,
            "PLATFORM",
            "invalid-location-facility-01",
            "Coordinate Facility",
            organizationId);

    mockMvc
        .perform(
            post("/api/v1/directory/locations")
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "invalid-location-out-of-range-01")
                .contentType("application/json")
                .content(locationBody(facilityId, "Invalid Longitude", "181", "40", true)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DIRECTORY_COORDINATE_INVALID"));

    mockMvc
        .perform(
            post("/api/v1/directory/locations")
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "invalid-location-latitude-01")
                .contentType("application/json")
                .content(locationBody(facilityId, "Invalid Latitude", "72.8", "91", true)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DIRECTORY_COORDINATE_INVALID"));

    mockMvc
        .perform(
            post("/api/v1/directory/locations")
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "invalid-location-non-finite-01")
                .contentType("application/json")
                .content(locationBody(facilityId, "Non-finite Coordinate", "1e309", "40", true)))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/v1/directory/locations")
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "invalid-location-incomplete-01")
                .contentType("application/json")
                .content(locationBody(facilityId, "Incomplete Coordinate", "null", "40", true)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DIRECTORY_COORDINATE_INVALID"));

    assertThat(count("SELECT COUNT(*) FROM directory.location")).isZero();
  }

  @Test
  void rejectsLocationCreationOutsideFacilityExecutionScope() throws Exception {
    grant(
        ADMIN,
        CURATOR,
        "DirectoryCuration",
        "PLATFORM",
        null,
        null,
        "seed-location-scope-platform-curator-01");
    String organizationId =
        createOrganization(CURATOR, "location-scope-org-01", "Scoped Location Authority");
    String authorizedFacility =
        createFacility(
            CURATOR,
            "PLATFORM",
            "location-scope-facility-01",
            "Authorized Facility",
            organizationId);
    String otherFacility =
        createFacility(
            CURATOR, "PLATFORM", "location-scope-facility-02", "Other Facility", organizationId);
    grant(
        ADMIN,
        FACILITY_CURATOR,
        "DirectoryCuration",
        "FACILITY",
        organizationId,
        authorizedFacility,
        "seed-location-scope-facility-curator-01");

    mockMvc
        .perform(
            post("/api/v1/directory/locations")
                .header("Authorization", bearer(token(FACILITY_CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", authorizedFacility)
                .header("Idempotency-Key", "location-scope-violation-01")
                .contentType("application/json")
                .content(locationBody(otherFacility, "Out of Scope", "72.8", "19.1", true)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("DIRECTORY_SCOPE_MISMATCH"));

    assertThat(count("SELECT COUNT(*) FROM directory.location")).isZero();
  }

  @Test
  void nearbySearchUsesInclusivePostgisMetersStableUuidTieBreakAndSeparateUnlocatedResults()
      throws Exception {
    grant(ADMIN, CURATOR, "DirectoryCuration", "PLATFORM", null, null, "seed-nearby-curator-01");
    grant(ADMIN, CURATOR, "DirectoryRead", "PLATFORM", null, null, "seed-nearby-reader-01");
    String organizationId = createOrganization(CURATOR, "nearby-org-01", "Nearby Authority");

    String closeFacility =
        createFacility(
            CURATOR, "PLATFORM", "nearby-close-facility-01", "Closer Facility", organizationId);
    createLocation(
        CURATOR, closeFacility, "Primary", "0.0005", "0.0", true, "nearby-close-location-01");

    String boundaryFacilityOne =
        createFacility(
            CURATOR, "PLATFORM", "nearby-boundary-facility-01", "Boundary One", organizationId);
    createLocation(
        CURATOR,
        boundaryFacilityOne,
        "Primary",
        "0.001",
        "0.0",
        true,
        "nearby-boundary-location-01");
    String boundaryFacilityTwo =
        createFacility(
            CURATOR, "PLATFORM", "nearby-boundary-facility-02", "Boundary Two", organizationId);
    createLocation(
        CURATOR,
        boundaryFacilityTwo,
        "Primary",
        "0.001",
        "0.0",
        true,
        "nearby-boundary-location-02");

    String outsideFacility =
        createFacility(
            CURATOR, "PLATFORM", "nearby-outside-facility-01", "Outside Facility", organizationId);
    createLocation(
        CURATOR,
        outsideFacility,
        "Primary",
        "0.00100001",
        "0.0",
        true,
        "nearby-outside-location-01");

    String missingCoordinateFacility =
        createFacility(
            CURATOR,
            "PLATFORM",
            "nearby-unlocated-facility-01",
            "Unlocated Facility",
            organizationId);
    createLocation(
        CURATOR,
        missingCoordinateFacility,
        "Address Not Geocoded",
        "null",
        "null",
        true,
        "nearby-unlocated-location-01");
    String noLocationFacility =
        createFacility(
            CURATOR,
            "PLATFORM",
            "nearby-no-location-facility-01",
            "No Location Facility",
            organizationId);

    Double radiusMeters =
        jdbc.queryForObject(
            "SELECT ST_Distance("
                + "ST_SetSRID(ST_MakePoint(0.001::double precision, 0.0::double precision), 4326)::geography, "
                + "ST_SetSRID(ST_MakePoint(0.0::double precision, 0.0::double precision), 4326)::geography)",
            Double.class);
    assertThat(radiusMeters).isNotNull().isPositive();
    assertThat(
            jdbc.queryForObject(
                "SELECT ST_Distance("
                    + "ST_SetSRID(ST_MakePoint(0.001::double precision, 0.0::double precision), 4326)::geography, "
                    + "ST_SetSRID(ST_MakePoint(0.0::double precision, 0.0::double precision), 4326)::geography) <= ?",
                Boolean.class,
                radiusMeters))
        .isTrue();

    var response =
        mockMvc
            .perform(
                get("/api/v1/directory/facilities")
                    .param("centerLongitude", "0.0")
                    .param("centerLatitude", "0.0")
                    .param("radiusMeters", radiusMeters.toString())
                    .param("pageSize", "10")
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nearby.length()").value(3))
            .andExpect(jsonPath("$.nearby[1].distanceMeters").value(radiusMeters))
            .andExpect(jsonPath("$.nearby[2].distanceMeters").value(radiusMeters))
            .andExpect(jsonPath("$.unlocated.length()").value(2))
            .andReturn();

    List<String> actualOrder =
        com.jayway.jsonpath.JsonPath.read(
            response.getResponse().getContentAsString(), "$.nearby[*].facilityId");
    List<String> boundaryOrder =
        java.util.stream.Stream.of(boundaryFacilityOne, boundaryFacilityTwo).sorted().toList();
    assertThat(actualOrder)
        .containsExactly(closeFacility, boundaryOrder.getFirst(), boundaryOrder.getLast());
    List<String> unlocatedIds =
        com.jayway.jsonpath.JsonPath.read(
            response.getResponse().getContentAsString(), "$.unlocated[*].facilityId");
    List<String> expectedUnlocatedOrder =
        java.util.stream.Stream.of(noLocationFacility, missingCoordinateFacility).sorted().toList();
    assertThat(unlocatedIds).containsExactlyElementsOf(expectedUnlocatedOrder);
    assertThat(
            jdbc.queryForObject(
                "SELECT ST_Distance("
                    + "ST_SetSRID(ST_MakePoint(0.001::double precision, 0.0::double precision), 4326)::geography, "
                    + "ST_SetSRID(ST_MakePoint(0.0::double precision, 0.0::double precision), 4326)::geography)",
                Double.class))
        .isEqualTo(radiusMeters);

    grant(
        ADMIN,
        CURATOR,
        "DirectoryVocabularyAdministration",
        "PLATFORM",
        null,
        null,
        "nearby-vocabulary-admin-01");
    createCareCapability("SEARCH_A", "Search A", null, "nearby-capability-a-01");
    createCareCapability("SEARCH_B", "Search B", null, "nearby-capability-b-01");
    for (String facilityId : List.of(closeFacility, boundaryFacilityOne, outsideFacility)) {
      createCapabilityAssertion(
          CURATOR, facilityId, "SEARCH_A", null, "VERIFIED", "nearby-a-" + facilityId, null);
      createCapabilityAssertion(
          CURATOR, facilityId, "SEARCH_B", null, "VERIFIED", "nearby-b-" + facilityId, null);
    }
    String inactiveOffering =
        createHealthcareService(
            CURATOR,
            "PLATFORM",
            organizationId,
            boundaryFacilityTwo,
            null,
            "Search Offering",
            "nearby-offering-01");
    createCapabilityAssertion(
        CURATOR, boundaryFacilityTwo, "SEARCH_A", null, "VERIFIED", "nearby-a-boundary-two", null);
    createCapabilityAssertion(
        CURATOR,
        boundaryFacilityTwo,
        "SEARCH_B",
        inactiveOffering,
        "VERIFIED",
        "nearby-b-inactive-offering",
        null);
    createCapabilityAssertion(
        CURATOR,
        missingCoordinateFacility,
        "SEARCH_A",
        null,
        "VERIFIED",
        "nearby-a-unlocated",
        null);
    createCapabilityAssertion(
        CURATOR,
        missingCoordinateFacility,
        "SEARCH_B",
        null,
        "VERIFIED",
        "nearby-b-unlocated",
        null);
    createCapabilityAssertion(
        CURATOR, noLocationFacility, "SEARCH_A", null, "VERIFIED", "nearby-a-no-location", null);
    mockMvc
        .perform(
            post("/api/v1/directory/healthcare-services/{serviceId}/deactivation", inactiveOffering)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "nearby-offering-deactivate-01")
                .contentType("application/json")
                .content(deactivationBody(1, "SERVICE_RETIRED")))
        .andExpect(status().isNoContent());

    var capabilityFilteredResponse =
        mockMvc
            .perform(
                get("/api/v1/directory/facilities")
                    .param("centerLongitude", "0.0")
                    .param("centerLatitude", "0.0")
                    .param("radiusMeters", radiusMeters.toString())
                    .param("pageSize", "10")
                    .param("requiredCapability", "SEARCH_A", "SEARCH_B")
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nearby.length()").value(2))
            .andExpect(jsonPath("$.unlocated.length()").value(1))
            .andReturn();
    List<String> capabilityFilteredNearby =
        com.jayway.jsonpath.JsonPath.read(
            capabilityFilteredResponse.getResponse().getContentAsString(),
            "$.nearby[*].facilityId");
    assertThat(capabilityFilteredNearby).containsExactly(closeFacility, boundaryFacilityOne);
    List<String> capabilityFilteredUnlocated =
        com.jayway.jsonpath.JsonPath.read(
            capabilityFilteredResponse.getResponse().getContentAsString(),
            "$.unlocated[*].facilityId");
    assertThat(capabilityFilteredUnlocated).containsExactly(missingCoordinateFacility);
    mockMvc
        .perform(
            get(
                    "/api/v1/directory/facilities/{facilityId}/capability-evidence",
                    boundaryFacilityTwo)
                .param("requiredCapability", "SEARCH_B")
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requirements[0].outcome").value("INACTIVE_OFFERING"));

    mockMvc
        .perform(
            get("/api/v1/directory/facilities")
                .param("centerLongitude", "0.0")
                .param("centerLatitude", "0.0")
                .param("radiusMeters", radiusMeters.toString())
                .param("pageSize", "101")
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            get("/api/v1/directory/facilities")
                .param("centerLongitude", "0.0")
                .param("centerLatitude", "0.0")
                .param("radiusMeters", "500001")
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            get("/api/v1/directory/facilities")
                .param("centerLongitude", "0.0")
                .param("centerLatitude", "0.0")
                .param("radiusMeters", "0")
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createsAndReadsAServiceWithoutTreatingItsOfferingAsCurrentCapacity() throws Exception {
    grant(ADMIN, CURATOR, "DirectoryCuration", "PLATFORM", null, null, "service-curator-01");
    grant(ADMIN, CURATOR, "DirectoryRead", "PLATFORM", null, null, "service-reader-01");
    String organizationId = createOrganization(CURATOR, "service-org-01", "Service Authority");
    String facilityId =
        createFacility(
            CURATOR, "PLATFORM", "service-facility-01", "Service Facility", organizationId);

    var created =
        mockMvc
            .perform(
                post("/api/v1/directory/healthcare-services")
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", "service-create-01")
                    .contentType("application/json")
                    .content(
                        healthcareServiceBody(organizationId, facilityId, "Emergency Service")))
            .andExpect(status().isCreated())
            .andReturn();
    String serviceId =
        com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    var replayed =
        mockMvc
            .perform(
                post("/api/v1/directory/healthcare-services")
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", "service-create-01")
                    .contentType("application/json")
                    .content(
                        healthcareServiceBody(organizationId, facilityId, "Emergency Service")))
            .andExpect(status().isCreated())
            .andReturn();
    String replayedServiceId =
        com.jayway.jsonpath.JsonPath.read(replayed.getResponse().getContentAsString(), "$.id");
    assertThat(replayedServiceId).isEqualTo(serviceId);

    mockMvc
        .perform(
            get("/api/v1/directory/healthcare-services/{serviceId}", serviceId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Emergency Service"))
        .andExpect(jsonPath("$.organizationId").value(organizationId))
        .andExpect(jsonPath("$.facilityId").value(facilityId))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.capacity").doesNotExist())
        .andExpect(jsonPath("$.readiness").doesNotExist());

    assertThat(count("SELECT COUNT(*) FROM directory.healthcare_service")).isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record "
                    + "WHERE action_code = 'DIRECTORY_HEALTHCARE_SERVICE_CREATE'"))
        .isEqualTo(1);
  }

  @Test
  void onlyDirectVerifiedEffectiveAssertionsSatisfyAllRequiredCapabilities() throws Exception {
    grant(ADMIN, CURATOR, "DirectoryCuration", "PLATFORM", null, null, "cap-curator-01");
    grant(ADMIN, CURATOR, "DirectoryRead", "PLATFORM", null, null, "cap-reader-01");
    grant(
        ADMIN,
        CURATOR,
        "DirectoryVocabularyAdministration",
        "PLATFORM",
        null,
        null,
        "cap-vocabulary-admin-01");
    String organizationId = createOrganization(CURATOR, "cap-org-01", "Capability Authority");
    String facilityId =
        createFacility(
            CURATOR, "PLATFORM", "cap-facility-01", "Capability Facility", organizationId);
    String serviceId =
        createHealthcareService(
            CURATOR,
            "PLATFORM",
            organizationId,
            facilityId,
            null,
            "Emergency Service",
            "cap-service-01");

    mockMvc
        .perform(
            post("/api/v1/directory/capabilities")
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "ORGANIZATION")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("Idempotency-Key", "cap-vocabulary-wrong-scope-01")
                .contentType("application/json")
                .content(capabilityBody("SCOPE_ONLY", "Scope Only", null)))
        .andExpect(status().isForbidden());
    assertThat(count("SELECT COUNT(*) FROM directory.care_capability")).isZero();

    String parent = createCareCapability("PARENT_CAP", "Parent", null, "cap-parent-01");
    createCareCapability("CHILD_CAP", "Child", parent, "cap-child-01");
    createCareCapability("DIRECT_A", "Direct capability", null, "cap-direct-a-01");
    createCareCapability("SERVICE_B", "Service capability", null, "cap-service-b-01");
    createCareCapability("UNVERIFIED_C", "Unverified capability", null, "cap-unverified-01");
    createCareCapability("FUTURE_D", "Future capability", null, "cap-future-01");
    createCareCapability("DISPUTED_E", "Disputed capability", null, "cap-disputed-01");
    createCareCapability("WITHDRAWN_F", "Withdrawn capability", null, "cap-withdrawn-01");
    createCareCapability("SUPERSEDED_G", "Superseded capability", null, "cap-superseded-01");
    String inactiveCapabilityId =
        createCareCapability("INACTIVE_H", "Inactive capability", null, "cap-inactive-01");

    String parentAssertion =
        createCapabilityAssertion(
            CURATOR, facilityId, "PARENT_CAP", null, "VERIFIED", "parent-assertion-01", null);
    String directAssertion =
        createCapabilityAssertion(
            CURATOR, facilityId, "DIRECT_A", null, "VERIFIED", "direct-assertion-01", null);
    String serviceAssertion =
        createCapabilityAssertion(
            CURATOR, facilityId, "SERVICE_B", serviceId, "VERIFIED", "service-assertion-01", null);
    String unverifiedAssertion =
        createCapabilityAssertion(
            CURATOR,
            facilityId,
            "UNVERIFIED_C",
            null,
            "UNVERIFIED",
            "unverified-assertion-01",
            null);
    createCapabilityAssertion(
        CURATOR,
        facilityId,
        "FUTURE_D",
        null,
        "VERIFIED",
        "future-assertion-01",
        "2027-01-01T00:00:00Z");
    createCapabilityAssertion(
        CURATOR, facilityId, "DISPUTED_E", null, "DISPUTED", "disputed-assertion-01", null);
    createCapabilityAssertion(
        CURATOR, facilityId, "WITHDRAWN_F", null, "WITHDRAWN", "withdrawn-assertion-01", null);
    createCapabilityAssertion(
        CURATOR, facilityId, "SUPERSEDED_G", null, "SUPERSEDED", "superseded-assertion-01", null);
    createCapabilityAssertion(
        CURATOR, facilityId, "INACTIVE_H", null, "VERIFIED", "inactive-assertion-01", null);

    mockMvc
        .perform(
            post("/api/v1/directory/capabilities/{capabilityId}/deactivation", inactiveCapabilityId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "cap-inactive-deactivate-01")
                .contentType("application/json")
                .content(deactivationBody(1, "CAPABILITY_RETIRED")))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            get("/api/v1/directory/facilities/{facilityId}/capability-evidence", facilityId)
                .param("requiredCapability", "DIRECT_A", "SERVICE_B")
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requirementsSatisfied").value(true))
        .andExpect(jsonPath("$.requirements.length()").value(2))
        .andExpect(jsonPath("$.requirements[0].outcome").value("SATISFIED"))
        .andExpect(jsonPath("$.requirements[1].outcome").value("SATISFIED"));

    assertCapabilityOutcome(facilityId, "CHILD_CAP", "NO_ASSERTION");
    assertCapabilityOutcome(facilityId, "UNVERIFIED_C", "UNVERIFIED");
    assertCapabilityOutcome(facilityId, "FUTURE_D", "NOT_EFFECTIVE");
    assertCapabilityOutcome(facilityId, "DISPUTED_E", "DISPUTED");
    assertCapabilityOutcome(facilityId, "WITHDRAWN_F", "NO_CURRENT_ASSERTION");
    assertCapabilityOutcome(facilityId, "SUPERSEDED_G", "NO_CURRENT_ASSERTION");
    assertCapabilityOutcome(facilityId, "INACTIVE_H", "INACTIVE_CAPABILITY");
    assertCapabilityOutcome(facilityId, "EMERGENCY", "UNKNOWN_CAPABILITY");

    correctCapabilityAssertion(unverifiedAssertion, 1, "VERIFIED", "cap-unverified-correction-01");
    assertCapabilityOutcome(facilityId, "UNVERIFIED_C", "SATISFIED");
    correctCapabilityAssertion(unverifiedAssertion, 2, "WITHDRAWN", "cap-unverified-withdraw-01");
    assertCapabilityOutcome(facilityId, "UNVERIFIED_C", "NO_CURRENT_ASSERTION");
    assertThat(
            count(
                "SELECT COUNT(*) FROM directory.facility_capability_assertion_revision "
                    + "WHERE assertion_id = '"
                    + unverifiedAssertion
                    + "'"))
        .isEqualTo(2);

    mockMvc
        .perform(
            post("/api/v1/directory/healthcare-services/{serviceId}/deactivation", serviceId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "cap-service-deactivate-01")
                .contentType("application/json")
                .content(deactivationBody(1, "SERVICE_RETIRED")))
        .andExpect(status().isNoContent());
    assertCapabilityOutcome(facilityId, "SERVICE_B", "INACTIVE_OFFERING");
    assertCapabilityOutcome(facilityId, "DIRECT_A", "SATISFIED");

    mockMvc
        .perform(
            get("/api/v1/directory/capability-assertions/{assertionId}", serviceAssertion)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))
        .andExpect(jsonPath("$.healthcareServiceId").value(serviceId))
        .andExpect(jsonPath("$.source.resourceType").value("FacilityCapabilityAssertion"))
        .andExpect(jsonPath("$.provenance.method").value("MANUAL_REVIEW"));

    assertThat(count("SELECT COUNT(*) FROM directory.healthcare_service")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM directory.facility_capability_assertion")).isEqualTo(9);
    assertThat(count("SELECT COUNT(*) FROM directory.healthcare_service_revision")).isEqualTo(1);
    assertThat(parentAssertion).isNotBlank();
    assertThat(directAssertion).isNotBlank();
  }

  private String createOrganization(String actor, String key, String name) throws Exception {
    var response =
        mockMvc
            .perform(
                post("/api/v1/directory/organizations")
                    .header("Authorization", bearer(token(actor)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(organizationBody(name)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andReturn();
    String id =
        com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.id");
    assertThat(InternalId.parse(id).value().version()).isEqualTo(4);
    return id;
  }

  private MvcResult concurrentCreate(
      String authorization, String body, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("concurrent Directory test did not start");
    }
    return mockMvc
        .perform(
            post("/api/v1/directory/organizations")
                .header("Authorization", authorization)
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "org-concurrent-create-01")
                .contentType("application/json")
                .content(body))
        .andReturn();
  }

  private String createFacility(
      String actor, String scopeKind, String key, String name, String organizationId)
      throws Exception {
    var request =
        post("/api/v1/directory/facilities")
            .header("Authorization", bearer(token(actor)))
            .header("X-AyuVahak-Execution-Scope", scopeKind)
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(facilityBody(organizationId, name));
    if ("ORGANIZATION".equals(scopeKind)) {
      request.header("X-AyuVahak-Organization-Id", organizationId);
    }
    if ("FACILITY".equals(scopeKind)) {
      throw new IllegalArgumentException(
          "facility-scoped creation is not valid for a new Facility");
    }
    var response = mockMvc.perform(request).andExpect(status().isCreated()).andReturn();
    String id =
        com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.id");
    assertThat(InternalId.parse(id).value().version()).isEqualTo(4);
    return id;
  }

  private String createHealthcareService(
      String actor,
      String scope,
      String organizationId,
      String facilityId,
      String locationId,
      String name,
      String idempotencyKey)
      throws Exception {
    var request =
        post("/api/v1/directory/healthcare-services")
            .header("Authorization", bearer(token(actor)))
            .header("X-AyuVahak-Execution-Scope", scope)
            .header("Idempotency-Key", idempotencyKey)
            .contentType("application/json")
            .content(healthcareServiceBody(organizationId, facilityId, locationId, name));
    if ("ORGANIZATION".equals(scope)) {
      request.header("X-AyuVahak-Organization-Id", organizationId);
    }
    var response = mockMvc.perform(request).andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.id");
  }

  private String createCareCapability(
      String code, String displayName, String parentCapabilityId, String idempotencyKey)
      throws Exception {
    var response =
        mockMvc
            .perform(
                post("/api/v1/directory/capabilities")
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType("application/json")
                    .content(capabilityBody(code, displayName, parentCapabilityId)))
            .andExpect(status().isCreated())
            .andReturn();
    return com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.id");
  }

  private String createCapabilityAssertion(
      String actor,
      String facilityId,
      String capabilityCode,
      String serviceId,
      String verificationStatus,
      String idempotencyKey,
      String effectiveFromOverride)
      throws Exception {
    String body =
        assertionBody(
            capabilityCode, serviceId, verificationStatus, effectiveFromOverride, idempotencyKey);
    var response =
        mockMvc
            .perform(
                post("/api/v1/directory/facilities/{facilityId}/capability-assertions", facilityId)
                    .header("Authorization", bearer(token(actor)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    return com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.id");
  }

  private void correctCapabilityAssertion(
      String assertionId, long expectedVersion, String status, String idempotencyKey)
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/directory/capability-assertions/{assertionId}/corrections", assertionId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content(assertionCorrectionBody(expectedVersion, status, idempotencyKey)))
        .andExpect(status().isNoContent());
  }

  private void assertCapabilityOutcome(String facilityId, String code, String expectedOutcome)
      throws Exception {
    mockMvc
        .perform(
            get("/api/v1/directory/facilities/{facilityId}/capability-evidence", facilityId)
                .param("requiredCapability", code)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requirementsSatisfied").value("SATISFIED".equals(expectedOutcome)))
        .andExpect(jsonPath("$.requirements[0].code").value(code))
        .andExpect(jsonPath("$.requirements[0].outcome").value(expectedOutcome));
  }

  private void grant(
      String administrator,
      String grantee,
      String authority,
      String scope,
      String organizationId,
      String facilityId,
      String key)
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", bearer(token(administrator)))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(grantBody(grantee, authority, scope, organizationId, facilityId)))
        .andExpect(status().isCreated());
  }

  private static String grantBody(
      String grantee, String authority, String scope, String organizationId, String facilityId) {
    String scopeJson =
        switch (scope) {
          case "PLATFORM" -> "\"kind\":\"PLATFORM\"";
          case "ORGANIZATION" ->
              "\"kind\":\"ORGANIZATION\",\"organization\":{"
                  + ref(organizationId, "Organization")
                  + "}";
          case "FACILITY" ->
              "\"kind\":\"FACILITY\",\"organization\":{"
                  + ref(organizationId, "Organization")
                  + "},\"facility\":{"
                  + ref(facilityId, "Facility")
                  + "}";
          default -> throw new IllegalArgumentException("unknown grant scope");
        };
    return "{\"principal\":{\"kind\":\"HUMAN\",\"issuer\":\""
        + ISSUER
        + "\",\"subject\":\""
        + grantee
        + "\"},\"authority\":\""
        + authority
        + "\",\"scope\":{"
        + scopeJson
        + "},\"reasonCode\":\"ACCESS_SETUP\"}";
  }

  private static String ref(String id, String type) {
    return "\"issuer\":\"" + DIRECTORY_ISSUER + "\",\"identifier\":\"" + id + "\"";
  }

  private static String organizationBody(String name) {
    return "{\"name\":\""
        + name
        + "\",\"source\":{"
        + sourceJson("Organization")
        + "},\"provenance\":{"
        + provenanceJson("INITIAL_ENTRY")
        + "}}";
  }

  private static String facilityBody(String organizationId, String name) {
    return "{\"name\":\""
        + name
        + "\",\"managingOrganizationId\":\""
        + organizationId
        + "\",\"source\":{"
        + sourceJson("Facility")
        + "},\"provenance\":{"
        + provenanceJson("INITIAL_ENTRY")
        + "}}";
  }

  private String createLocation(
      String actor,
      String facilityId,
      String name,
      String longitude,
      String latitude,
      boolean primary,
      String idempotencyKey)
      throws Exception {
    var response =
        mockMvc
            .perform(
                post("/api/v1/directory/locations")
                    .header("Authorization", bearer(token(actor)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType("application/json")
                    .content(locationBody(facilityId, name, longitude, latitude, primary)))
            .andExpect(status().isCreated())
            .andReturn();
    String id =
        com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.id");
    assertThat(InternalId.parse(id).value().version()).isEqualTo(4);
    return id;
  }

  private static String locationBody(
      String facilityId, String name, String longitude, String latitude, boolean primary) {
    return "{\"facilityId\":\""
        + facilityId
        + "\",\"name\":\""
        + name
        + "\",\"longitude\":"
        + longitude
        + ",\"latitude\":"
        + latitude
        + ",\"primary\":"
        + primary
        + ",\"source\":{"
        + sourceJson("Location")
        + "},\"provenance\":{"
        + provenanceJson("LOCATION_ENTRY")
        + "}}";
  }

  private static String locationCorrectionBody(
      long expectedVersion, String name, String longitude, String latitude, boolean primary) {
    return "{\"expectedVersion\":"
        + expectedVersion
        + ",\"name\":\""
        + name
        + "\",\"longitude\":"
        + longitude
        + ",\"latitude\":"
        + latitude
        + ",\"primary\":"
        + primary
        + ",\"provenance\":{"
        + provenanceJson("LOCATION_CORRECTION")
        + "}}";
  }

  private static String healthcareServiceBody(
      String organizationId, String facilityId, String name) {
    return healthcareServiceBody(organizationId, facilityId, null, name);
  }

  private static String healthcareServiceBody(
      String organizationId, String facilityId, String locationId, String name) {
    return "{\"organizationId\":\""
        + organizationId
        + "\",\"facilityId\":\""
        + facilityId
        + "\",\"locationId\":"
        + (locationId == null ? "null" : "\"" + locationId + "\"")
        + ",\"name\":\""
        + name
        + "\",\"source\":{"
        + sourceJson("HealthcareService")
        + "},\"provenance\":{"
        + provenanceJson("SERVICE_ENTRY")
        + "}}";
  }

  private static String capabilityBody(String code, String displayName, String parentCapabilityId) {
    return "{\"code\":\""
        + code
        + "\",\"displayName\":\""
        + displayName
        + "\",\"parentCapabilityId\":"
        + (parentCapabilityId == null ? "null" : "\"" + parentCapabilityId + "\"")
        + ",\"source\":{"
        + sourceJson("CareCapability", "synthetic-capability-" + code)
        + "},\"provenance\":{"
        + provenanceJson("CAPABILITY_ENTRY")
        + "}}";
  }

  private static String assertionBody(
      String capabilityCode,
      String serviceId,
      String verificationStatus,
      String effectiveFromOverride,
      String sourceReference) {
    String effectiveFrom =
        effectiveFromOverride == null ? "2026-01-01T00:00:00Z" : effectiveFromOverride;
    return "{\"capabilityCode\":\""
        + capabilityCode
        + "\",\"healthcareServiceId\":"
        + (serviceId == null ? "null" : "\"" + serviceId + "\"")
        + ",\"verificationStatus\":\""
        + verificationStatus
        + "\",\"source\":{"
        + sourceJson("FacilityCapabilityAssertion", "synthetic-assertion-" + sourceReference)
        + "},\"provenance\":{"
        + provenanceJson("MANUAL_REVIEW")
        + "},\"effectiveFrom\":\""
        + effectiveFrom
        + "\",\"effectiveUntil\":null}";
  }

  private static String assertionCorrectionBody(
      long expectedVersion, String verificationStatus, String reasonCode) {
    return "{\"expectedVersion\":"
        + expectedVersion
        + ",\"verificationStatus\":\""
        + verificationStatus
        + "\",\"source\":{"
        + sourceJson("FacilityCapabilityAssertion", "synthetic-assertion-correction-" + reasonCode)
        + "},\"provenance\":{"
        + provenanceJson("ASSERTION_CORRECTION")
        + "},\"effectiveFrom\":\"2026-01-01T00:00:00Z\",\"effectiveUntil\":null}";
  }

  private static String deactivationBody(long expectedVersion, String reasonCode) {
    return "{\"expectedVersion\":"
        + expectedVersion
        + ",\"provenance\":{"
        + provenanceJson(reasonCode)
        + "}}";
  }

  private static String correctionBody(long expectedVersion, String name, String reasonCode) {
    return "{\"expectedVersion\":"
        + expectedVersion
        + ",\"name\":\""
        + name
        + "\",\"provenance\":{"
        + provenanceJson(reasonCode)
        + "}}";
  }

  private static String sourceJson(String resourceType) {
    return sourceJson(resourceType, "synthetic-" + resourceType.toLowerCase() + "-001");
  }

  private static String sourceJson(String resourceType, String reference) {
    return "\"issuer\":\"urn:synthetic:facility-registry\",\"resourceType\":\""
        + resourceType
        + "\",\"reference\":\""
        + reference
        + "\"";
  }

  private static String provenanceJson(String reasonCode) {
    return "\"method\":\"MANUAL_REVIEW\",\"observedAt\":\"2026-10-08T09:00:00Z\",\"reasonCode\":\""
        + reasonCode
        + "\"";
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
