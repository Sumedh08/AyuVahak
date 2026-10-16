package org.ayuvahak.capacity.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.ayuvahak.capacity.application.CapacityReadiness;
import org.ayuvahak.capacity.application.CapacityReadinessBatchTarget;
import org.ayuvahak.platform.AyuVahakApplication;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.fhir.FhirPayloadLimits;
import org.ayuvahak.platform.fhir.FhirProfileContext;
import org.ayuvahak.platform.fhir.FhirR4PayloadParser;
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
      "ayuvahak.authentication.service-client-ids=synthetic-capacity-service",
      "ayuvahak.authentication.allow-http=true"
    })
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CapacityObservationCommandsApiIT {

  private static final String TEST_PASSWORD = "local-test-only";
  private static final String ISSUER = "https://identity.example.test/realms/ayuvahak";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String AUDIENCE = "ayuvahak-api";
  private static final String ADMIN = "synthetic-platform-admin";
  private static final String CURATOR = "synthetic-directory-curator";
  private static final String REPORTER = "synthetic-capacity-reporter";
  private static final String HISTORY_REPORTER = "synthetic-capacity-history-reporter";
  private static final String UNREVIEWED_REPORTER = "synthetic-capacity-unreviewed-reporter";
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
  @Autowired private CapacityReadiness capacityReadiness;
  @Autowired private ExecutionContextFactory executionContextFactory;

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
    jdbc.execute("DROP TRIGGER IF EXISTS test_fail_capacity_readiness_audit ON audit.audit_record");
    jdbc.execute("DROP FUNCTION IF EXISTS test_fail_capacity_readiness_audit()");
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
    grant(
        HISTORY_REPORTER,
        "CapacityReporting",
        "FACILITY",
        organizationId,
        facilityId,
        "history-reporter-grant");
    grant(
        UNREVIEWED_REPORTER,
        "CapacityReporting",
        "FACILITY",
        organizationId,
        facilityId,
        "unreviewed-reporter-grant");
    seedCapacityEvidenceReviewer();
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
  void freeFhirSlotCannotCreateCapacityEvidenceOrReadyStatus() throws Exception {
    String freeSlot =
        """
        {
          "resourceType": "Slot",
          "id": "SYN-SLOT-001",
          "schedule": {"reference": "Schedule/SYN-SCHEDULE-001"},
          "status": "free",
          "start": "2026-10-15T10:00:00Z",
          "end": "2026-10-15T10:30:00Z"
        }
        """;
    var parsedSlot =
        FhirR4PayloadParser.withCoreProfiles(
                new FhirPayloadLimits(
                    4 * 1024 * 1024, 64, 250, 1000, java.time.Duration.ofSeconds(10)))
            .parse(
                freeSlot.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "application/fhir+json",
                new FhirProfileContext("4.0.1", "http://hl7.org/fhir/StructureDefinition/Slot"));
    assertThat(parsedSlot.resource()).isInstanceOf(org.hl7.fhir.r4.model.Slot.class);
    assertThat(((org.hl7.fhir.r4.model.Slot) parsedSlot.resource()).getStatus())
        .isEqualTo(org.hl7.fhir.r4.model.Slot.SlotStatus.FREE);

    String schedule =
        """
        {
          "resourceType": "Schedule",
          "id": "SYN-SCHEDULE-001",
          "active": true,
          "actor": [{"reference": "Location/SYN-FACILITY-A"}]
        }
        """;
    var parsedSchedule =
        FhirR4PayloadParser.withCoreProfiles(
                new FhirPayloadLimits(
                    4 * 1024 * 1024, 64, 250, 1000, java.time.Duration.ofSeconds(10)))
            .parse(
                schedule.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "application/fhir+json",
                new FhirProfileContext(
                    "4.0.1", "http://hl7.org/fhir/StructureDefinition/Schedule"));
    assertThat(parsedSchedule.resource()).isInstanceOf(org.hl7.fhir.r4.model.Schedule.class);

    mockMvc
        .perform(
            post("/api/v1/capacity/observations")
                .header("Authorization", bearer(token(REPORTER)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", "free-fhir-slot-must-not-be-capacity")
                .contentType("application/fhir+json")
                .content(freeSlot))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CAPACITY_VALUE_INVALID"));

    mockMvc
        .perform(
            post("/api/v1/capacity/observations")
                .header("Authorization", bearer(token(REPORTER)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", "fhir-schedule-must-not-be-capacity")
                .contentType("application/fhir+json")
                .content(schedule))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CAPACITY_VALUE_INVALID"));

    assertThat(count("SELECT COUNT(*) FROM capacity.capacity_observation")).isZero();

    queryCurrent("COORDINATION_BEDS")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[0].freshness").value("UNKNOWN"))
        .andExpect(jsonPath("$.dimensions[0].evidence").isEmpty());
    evaluateReadiness("[\"COORDINATION_BEDS\"]")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("UNKNOWN"))
        .andExpect(jsonPath("$.dimensions[0].outcome").value("UNKNOWN"));
  }

  @Test
  void authorizedFacilityReaderGetsCurrentEvidenceWithItsProvenance() throws Exception {
    String observedAt = Instant.now().minusSeconds(2).toString();
    submit("current-query-observation", observationBody("source-current-query", 4, observedAt))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            get("/api/v1/capacity/facilities/{facilityId}/current", facilityId)
                .param("dimension", "COORDINATION_BEDS")
                .header("Authorization", bearer(token(REPORTER)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.facilityId").value(facilityId))
        .andExpect(jsonPath("$.dimensions[0].dimensionCode").value("COORDINATION_BEDS"))
        .andExpect(jsonPath("$.dimensions[0].freshness").value("CURRENT"))
        .andExpect(jsonPath("$.dimensions[0].quantity").value(4))
        .andExpect(jsonPath("$.dimensions[0].unit").value("bed"))
        .andExpect(jsonPath("$.dimensions[0].evidence[0].sourceCode").value("manual"))
        .andExpect(jsonPath("$.dimensions[0].evidence[0].observedAt").exists())
        .andExpect(jsonPath("$.dimensions[0].evidence[0].receivedAt").exists())
        .andExpect(jsonPath("$.dimensions[0].policyVersion").value(1))
        .andExpect(jsonPath("$.dimensions[0].projectionVersion").value("capacity-evidence-v1"));
  }

  @Test
  void evaluatesOnlyExplicitDimensionsAndReturnsAttributablePassingEvidence() throws Exception {
    String observedAt = Instant.now().minusSeconds(2).toString();
    submit(
            "readiness-passing-observation",
            observationBody("source-readiness-passing", 4, observedAt))
        .andExpect(status().isCreated());

    evaluateReadiness("[\"COORDINATION_BEDS\"]")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.facilityId").value(facilityId))
        .andExpect(jsonPath("$.outcome").value("READY"))
        .andExpect(jsonPath("$.dimensions.length()").value(1))
        .andExpect(jsonPath("$.dimensions[0].dimensionCode").value("COORDINATION_BEDS"))
        .andExpect(jsonPath("$.dimensions[0].outcome").value("READY"))
        .andExpect(jsonPath("$.dimensions[0].quantity").value(4))
        .andExpect(jsonPath("$.dimensions[0].unit").value("bed"))
        .andExpect(jsonPath("$.dimensions[0].evidence[0].sourceCode").value("manual"))
        .andExpect(jsonPath("$.dimensions[0].evidence[0].observedAt").exists())
        .andExpect(jsonPath("$.dimensions[0].dimensionVersion").value(1))
        .andExpect(jsonPath("$.dimensions[0].capacityPolicyVersion").value(1))
        .andExpect(jsonPath("$.dimensions[0].evidence[0].ingestionPolicyVersion").value(1))
        .andExpect(jsonPath("$.dimensions[0].reasonCodes[0]").value("READINESS_RULE_PASSED"));
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'CAPACITY_READINESS_EVALUATE'"))
        .isEqualTo(1);
  }

  @Test
  void notReadyOutranksMissingEvidenceAndExplicitZeroRemainsAValue() throws Exception {
    submit(
            "readiness-zero-observation",
            observationBody("source-readiness-zero", 0, Instant.now().minusSeconds(2).toString()))
        .andExpect(status().isCreated());

    evaluateReadiness("[\"COORDINATION_BEDS\",\"UNCONFIGURED_RESOURCE\"]")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("NOT_READY"))
        .andExpect(jsonPath("$.dimensions[0].outcome").value("NOT_READY"))
        .andExpect(jsonPath("$.dimensions[0].quantity").value(0))
        .andExpect(jsonPath("$.dimensions[0].reasonCodes[0]").value("BELOW_MINIMUM_QUANTITY"))
        .andExpect(jsonPath("$.dimensions[1].outcome").value("UNKNOWN"))
        .andExpect(jsonPath("$.dimensions[1].reasonCodes[0]").value("DIMENSION_UNKNOWN"));
  }

  @Test
  void missingAndMixedEvidenceCannotProduceReady() throws Exception {
    evaluateReadiness("[\"COORDINATION_BEDS\"]")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("UNKNOWN"))
        .andExpect(jsonPath("$.dimensions[0].freshness").value("UNKNOWN"));

    submit(
            "readiness-one-known-one-unknown",
            observationBody(
                "source-readiness-one-known", 4, Instant.now().minusSeconds(2).toString()))
        .andExpect(status().isCreated());
    evaluateReadiness("[\"COORDINATION_BEDS\",\"UNCONFIGURED_RESOURCE\"]")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("UNKNOWN"))
        .andExpect(jsonPath("$.dimensions[0].outcome").value("READY"))
        .andExpect(jsonPath("$.dimensions[1].outcome").value("UNKNOWN"));
  }

  @Test
  void staleEvidenceCannotProduceReady() throws Exception {
    Instant now = Instant.now();
    submit(
            "readiness-stale-observation",
            observationBody(
                "source-readiness-stale",
                "manual",
                4,
                now.minusSeconds(1_200).toString(),
                now.plusSeconds(1_200).toString()))
        .andExpect(status().isCreated());
    evaluateReadiness("[\"COORDINATION_BEDS\"]")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("STALE"))
        .andExpect(jsonPath("$.dimensions[0].outcome").value("STALE"))
        .andExpect(jsonPath("$.dimensions[0].freshness").value("STALE"))
        .andExpect(jsonPath("$.dimensions[0].reasonCodes[0]").value("EVIDENCE_STALE"));
  }

  @Test
  void dimensionWithoutAReadinessPolicyRemainsUnknown() throws Exception {
    defineDimensionWithoutPolicy("POLICYLESS_RESOURCE");

    evaluateReadiness("[\"POLICYLESS_RESOURCE\"]")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("UNKNOWN"))
        .andExpect(jsonPath("$.dimensions[0].outcome").value("UNKNOWN"))
        .andExpect(jsonPath("$.dimensions[0].dimensionVersion").value(1))
        .andExpect(jsonPath("$.dimensions[0].capacityPolicyVersion").doesNotExist())
        .andExpect(
            jsonPath("$.dimensions[0].reasonCodes[0]").value("READINESS_POLICY_UNCONFIGURED"));
  }

  @Test
  void equalPriorityDisagreementProducesConflictedReadiness() throws Exception {
    configureEqualPriorityPolicy();
    String observedAt = Instant.now().minusSeconds(2).toString();
    submit(
            "readiness-conflict-one",
            observationBody("source-readiness-conflict-one", "manual", 4, observedAt, null))
        .andExpect(status().isCreated());
    submit(
            "readiness-conflict-two",
            observationBody("source-readiness-conflict-two", "manual-replica", 7, observedAt, null))
        .andExpect(status().isCreated());

    evaluateReadiness("[\"COORDINATION_BEDS\"]")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("CONFLICTED"))
        .andExpect(jsonPath("$.dimensions[0].outcome").value("CONFLICTED"))
        .andExpect(jsonPath("$.dimensions[0].quantity").doesNotExist())
        .andExpect(jsonPath("$.dimensions[0].evidence.length()").value(2))
        .andExpect(
            jsonPath("$.dimensions[0].reasonCodes")
                .value(org.hamcrest.Matchers.hasItem("EVIDENCE_CONFLICTED")));
  }

  @Test
  void invalidRequestsReturnSafeProblemDetailsAndCorrelation() throws Exception {
    String duplicateDimensions =
        "{\"facilityId\":\""
            + facilityId
            + "\",\"requiredDimensions\":[\"COORDINATION_BEDS\",\"COORDINATION_BEDS\"]}";
    evaluateReadinessRequest(REPORTER, facilityId, duplicateDimensions)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CAPACITY_VALUE_INVALID"))
        .andExpect(jsonPath("$.correlationId").value("readiness-invalid-correlation"))
        .andExpect(header().string("X-Correlation-ID", "readiness-invalid-correlation"));

    evaluateReadinessRequest(
            REPORTER,
            facilityId,
            "{\"facilityId\":\"" + facilityId + "\",\"requiredDimensions\":[]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CAPACITY_VALUE_INVALID"));

    evaluateReadinessRequest(
            REPORTER,
            facilityId,
            "{\"facilityId\":\""
                + facilityId
                + "\",\"requiredDimensions\":[\"COORDINATION_BEDS\"],"
                + "\"evaluationTime\":\""
                + Instant.now().plusSeconds(3_600)
                + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CAPACITY_VALUE_INVALID"));

    evaluateReadinessRequest(
            REPORTER,
            facilityId,
            "{\"facilityId\":\""
                + facilityId
                + "\",\"requiredDimensions\":[\"COORDINATION_BEDS\"],"
                + "\"careCapabilities\":[\"ICU\"]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CAPACITY_VALUE_INVALID"));
  }

  @Test
  void scopeAndAuthorizationFailuresAreDeniedAndAudited() throws Exception {
    String body =
        "{\"facilityId\":\"" + facilityId + "\",\"requiredDimensions\":[\"COORDINATION_BEDS\"]}";
    evaluateReadinessRequest(REPORTER, "00000000-0000-4000-8000-000000000001", body)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_SCOPE"));
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'CAPACITY_EVIDENCE_SCOPE_REJECTED' AND outcome = 'DENIED'"))
        .isEqualTo(1);

    evaluateReadinessRequest(UNREVIEWED_REPORTER, facilityId, body)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'CAPACITY_READINESS_EVALUATE' AND outcome = 'DENIED'"))
        .isEqualTo(1);
  }

  @Test
  void auditFailureMakesTheReadinessQueryFailClosed() throws Exception {
    jdbc.execute(
        "CREATE FUNCTION test_fail_capacity_readiness_audit() RETURNS trigger LANGUAGE plpgsql AS "
            + "'BEGIN IF NEW.action_code = ''CAPACITY_READINESS_EVALUATE'' THEN "
            + "RAISE EXCEPTION ''synthetic readiness audit failure''; END IF; RETURN NEW; END'");
    jdbc.execute(
        "CREATE TRIGGER test_fail_capacity_readiness_audit BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION test_fail_capacity_readiness_audit()");

    try {
      evaluateReadiness("[\"COORDINATION_BEDS\"]")
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));
    } finally {
      jdbc.execute(
          "DROP TRIGGER IF EXISTS test_fail_capacity_readiness_audit ON audit.audit_record");
      jdbc.execute("DROP FUNCTION IF EXISTS test_fail_capacity_readiness_audit()");
    }
  }

  @Test
  void matchingBatchReadUsesOneTimeAndFailsClosedForAnyUnreviewedFacility() throws Exception {
    String serviceClient = SERVICE_REPORTER;
    grantService(
        serviceClient,
        "CapacityEvidenceReview",
        organizationId,
        facilityId,
        "matching-capacity-reader-grant");
    String otherFacilityId =
        createFacility("Unreviewed Synthetic Facility", "matching-unreviewed-facility-setup");
    ExecutionContext authorized =
        matchingServiceContext(serviceClient, facilityId, "matching-capacity-batch-01");
    ExecutionContext unreviewed =
        matchingServiceContext(serviceClient, otherFacilityId, "matching-capacity-batch-01");
    Instant evaluatedAt = Instant.now().minusSeconds(1);

    assertThatThrownBy(
            () ->
                capacityReadiness.evaluateOperationalReadinessBatch(
                    List.of(
                        new CapacityReadinessBatchTarget(authorized, facilityReference(facilityId)),
                        new CapacityReadinessBatchTarget(
                            unreviewed, facilityReference(otherFacilityId))),
                    List.of("COORDINATION_BEDS"),
                    evaluatedAt))
        .hasMessageContaining("not authorized");
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'CAPACITY_MATCHING_EVALUATION_READ' AND outcome = 'SUCCEEDED'"))
        .isZero();

    var authorizedBatch =
        capacityReadiness.evaluateOperationalReadinessBatch(
            List.of(new CapacityReadinessBatchTarget(authorized, facilityReference(facilityId))),
            List.of("COORDINATION_BEDS"),
            evaluatedAt);
    assertThat(authorizedBatch.evaluationTime()).isEqualTo(evaluatedAt);
    assertThat(authorizedBatch.facilities()).hasSize(1);
    assertThat(authorizedBatch.facilities().getFirst().facility())
        .isEqualTo(facilityReference(facilityId));
    assertThat(authorizedBatch.facilities().getFirst().dimensions()).hasSize(1);
    assertThat(authorizedBatch.facilities().getFirst().outcome().name()).isEqualTo("UNKNOWN");
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'CAPACITY_MATCHING_EVALUATION_READ' AND outcome = 'SUCCEEDED' "
                    + "AND reason_code = 'MATCHING_EVALUATION'"))
        .isEqualTo(1);
  }

  @Test
  void unavailableEvidenceStoreCannotReturnReady() throws Exception {
    jdbc.execute(
        "ALTER TABLE capacity.capacity_observation "
            + "RENAME TO capacity_observation_temporarily_unavailable");
    try {
      evaluateReadiness("[\"COORDINATION_BEDS\"]")
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("CAPACITY_UNAVAILABLE"));
    } finally {
      jdbc.execute(
          "ALTER TABLE capacity.capacity_observation_temporarily_unavailable "
              + "RENAME TO capacity_observation");
    }
  }

  @Test
  void staleHigherPriorityEvidenceUsesOnlyExplicitlyAllowedFallback() throws Exception {
    configureFallbackPolicy();
    Instant now = Instant.now();
    String staleObservedAt = now.minusSeconds(3_600).toString();
    String stillValid = now.plusSeconds(600).toString();
    submit(
            "stale-primary-source",
            observationBody("source-stale-primary", "automated", 8, staleObservedAt, stillValid))
        .andExpect(status().isCreated());
    submit(
            "current-fallback-source",
            observationBody(
                "source-current-fallback", "manual", 3, now.minusSeconds(10).toString(), null))
        .andExpect(status().isCreated());

    queryCurrent("COORDINATION_BEDS")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[0].freshness").value("CURRENT"))
        .andExpect(jsonPath("$.dimensions[0].quantity").value(3))
        .andExpect(jsonPath("$.dimensions[0].fallbackUsed").value(true))
        .andExpect(jsonPath("$.dimensions[0].reasonCodes[1]").value("FALLBACK_SOURCE_SELECTED"))
        .andExpect(jsonPath("$.dimensions[0].evidence[0].sourceCode").value("manual"));
  }

  @Test
  void equalAuthorityDisagreementAtTheLatestObservationTimeIsConflicted() throws Exception {
    String observedAt = Instant.now().minusSeconds(5).toString();
    submit("equal-tier-observation-a", observationBody("equal-tier-source-a", 4, observedAt))
        .andExpect(status().isCreated());
    submit("equal-tier-observation-b", observationBody("equal-tier-source-b", 7, observedAt))
        .andExpect(status().isCreated());

    queryCurrent("COORDINATION_BEDS")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[0].freshness").value("CONFLICTED"))
        .andExpect(jsonPath("$.dimensions[0].quantity").doesNotExist())
        .andExpect(jsonPath("$.dimensions[0].evidence.length()").value(2))
        .andExpect(
            jsonPath("$.dimensions[0].reasonCodes[0]").value("EQUAL_AUTHORITY_DISAGREEMENT"));
  }

  @Test
  void concurrentSameDimensionObservationsResolveToConflictIndependentOfCommitOrder()
      throws Exception {
    String observedAt = Instant.now().minusSeconds(5).toString();
    CountDownLatch startTogether = new CountDownLatch(1);
    ExecutorService writers = Executors.newFixedThreadPool(2);
    try {
      var first =
          writers.submit(
              () -> {
                startTogether.await();
                return submit(
                        "concurrent-dimension-a",
                        observationBody("source-concurrent-a", 4, observedAt))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });
      var second =
          writers.submit(
              () -> {
                startTogether.await();
                return submit(
                        "concurrent-dimension-b",
                        observationBody("source-concurrent-b", 7, observedAt))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });
      startTogether.countDown();
      assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(201);
      assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(201);
    } finally {
      writers.shutdownNow();
    }

    queryCurrent("COORDINATION_BEDS")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[0].freshness").value("CONFLICTED"))
        .andExpect(jsonPath("$.dimensions[0].evidence.length()").value(2));
  }

  @Test
  void laterReceivedOlderObservationCannotReplaceNewerObservedEvidence() throws Exception {
    Instant now = Instant.now();
    submit(
            "observed-newer-received-first",
            observationBody("source-newer-observed", 6, now.minusSeconds(20).toString()))
        .andExpect(status().isCreated());
    submit(
            "observed-older-received-later",
            observationBody("source-older-observed", 2, now.minusSeconds(40).toString()))
        .andExpect(status().isCreated());

    queryCurrent("COORDINATION_BEDS")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[0].freshness").value("CURRENT"))
        .andExpect(jsonPath("$.dimensions[0].quantity").value(6))
        .andExpect(jsonPath("$.dimensions[0].evidence.length()").value(1))
        .andExpect(
            jsonPath("$.dimensions[0].evidence[0].sourceObservation.value")
                .value("source-newer-observed"));
  }

  @Test
  void explicitZeroRemainsCurrentWhileAnUnconfiguredDimensionRemainsUnknown() throws Exception {
    submit(
            "explicit-zero-observation",
            observationBody("source-explicit-zero", 0, Instant.now().minusSeconds(5).toString()))
        .andExpect(status().isCreated());

    queryCurrent("COORDINATION_BEDS", "UNCONFIGURED_RESOURCE")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions.length()").value(2))
        .andExpect(jsonPath("$.dimensions[0].freshness").value("CURRENT"))
        .andExpect(jsonPath("$.dimensions[0].quantity").value(0))
        .andExpect(jsonPath("$.dimensions[1].freshness").value("UNKNOWN"))
        .andExpect(jsonPath("$.dimensions[1].quantity").doesNotExist())
        .andExpect(jsonPath("$.dimensions[1].reasonCodes[0]").value("DIMENSION_UNKNOWN"));
  }

  @Test
  void staleEvidenceIsNotReportedAsCurrent() throws Exception {
    Instant now = Instant.now();
    submit(
            "stale-evidence",
            observationBody(
                "source-stale-evidence",
                "manual",
                8,
                now.minusSeconds(3_600).toString(),
                now.plusSeconds(600).toString()))
        .andExpect(status().isCreated());

    queryCurrent("COORDINATION_BEDS")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[0].freshness").value("STALE"))
        .andExpect(jsonPath("$.dimensions[0].quantity").doesNotExist())
        .andExpect(jsonPath("$.dimensions[0].evidence.length()").value(1))
        .andExpect(jsonPath("$.dimensions[0].reasonCodes[0]").value("OBSERVATION_TOO_OLD"));
  }

  @Test
  void validityExpiryIsExclusiveAndDistinctFromStaleEvidence() throws Exception {
    Instant now = Instant.now();
    Instant observedAt = now.minusSeconds(20);
    Instant validUntil = now.minusSeconds(1);
    submit(
            "expired-evidence",
            observationBody(
                "source-expired-evidence",
                "manual",
                8,
                observedAt.toString(),
                validUntil.toString()))
        .andExpect(status().isCreated());

    queryCurrentAt(now.toString(), "COORDINATION_BEDS")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[0].freshness").value("EXPIRED"))
        .andExpect(jsonPath("$.dimensions[0].quantity").doesNotExist())
        .andExpect(jsonPath("$.dimensions[0].reasonCodes[0]").value("VALIDITY_ELAPSED"));
  }

  @Test
  void futureSkewedEvidenceCannotBecomePositiveCurrentEvidence() throws Exception {
    submit(
            "future-skewed-evidence",
            observationBody("source-future-skewed", 9, Instant.now().plusSeconds(120).toString()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.futureSkewed").value(true));

    queryCurrent("COORDINATION_BEDS")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[0].freshness").value("UNKNOWN"))
        .andExpect(jsonPath("$.dimensions[0].quantity").doesNotExist())
        .andExpect(jsonPath("$.dimensions[0].reasonCodes[0]").value("FUTURE_SKEW_EXCLUDED"));
  }

  @Test
  void correctionSupersedesCurrentEvidenceAndRemainsVisibleInHistory() throws Exception {
    Instant now = Instant.now();
    var original =
        submit(
                "current-original-observation",
                observationBody("source-current-original", 4, now.minusSeconds(20).toString()))
            .andExpect(status().isCreated())
            .andReturn();
    String originalId =
        com.jayway.jsonpath.JsonPath.read(original.getResponse().getContentAsString(), "$.id");
    var correction =
        correct(
                originalId,
                "current-correction",
                correctionBody("source-current-correction", 3, now.minusSeconds(10).toString()))
            .andExpect(status().isCreated())
            .andReturn();
    String correctionId =
        com.jayway.jsonpath.JsonPath.read(correction.getResponse().getContentAsString(), "$.id");

    queryCurrentAt(now.toString(), "COORDINATION_BEDS")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[0].freshness").value("CURRENT"))
        .andExpect(jsonPath("$.dimensions[0].quantity").value(3))
        .andExpect(jsonPath("$.dimensions[0].evidence[0].observationId").value(correctionId))
        .andExpect(
            jsonPath("$.dimensions[0].evidence[0].supersedesObservationId").value(originalId));
    queryHistory(REPORTER, "page", "0", "size", "10")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].observation.observationId").value(correctionId))
        .andExpect(jsonPath("$.items[0].superseded").value(false))
        .andExpect(jsonPath("$.items[1].observation.observationId").value(originalId))
        .andExpect(jsonPath("$.items[1].superseded").value(true));
  }

  @Test
  void historyIsAuthorizedFilteredBoundedAndOrderedWithExclusiveEnd() throws Exception {
    Instant newest = Instant.now().minusSeconds(5);
    Instant middle = newest.minusSeconds(10);
    Instant oldest = middle.minusSeconds(10);
    submit("history-oldest", observationBody("source-history-oldest", 1, oldest.toString()))
        .andExpect(status().isCreated());
    submit("history-middle", observationBody("source-history-middle", 2, middle.toString()))
        .andExpect(status().isCreated());
    submit("history-newest", observationBody("source-history-newest", 3, newest.toString()))
        .andExpect(status().isCreated());

    queryHistory(
            REPORTER,
            "dimension",
            "COORDINATION_BEDS",
            "sourceCode",
            "manual",
            "page",
            "0",
            "size",
            "1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(
            jsonPath("$.items[0].observation.sourceObservation.value")
                .value("source-history-newest"))
        .andExpect(jsonPath("$.hasMore").value(true));
    queryHistory(
            REPORTER,
            "dimension",
            "COORDINATION_BEDS",
            "sourceCode",
            "manual",
            "page",
            "1",
            "size",
            "1")
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[0].observation.sourceObservation.value")
                .value("source-history-middle"))
        .andExpect(jsonPath("$.hasMore").value(true));
    queryHistory(
            REPORTER,
            "dimension",
            "COORDINATION_BEDS",
            "sourceCode",
            "manual",
            "page",
            "2",
            "size",
            "1")
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[0].observation.sourceObservation.value")
                .value("source-history-oldest"))
        .andExpect(jsonPath("$.hasMore").value(false));
    queryHistory(
            REPORTER,
            "from",
            oldest.toString(),
            "until",
            middle.toString(),
            "page",
            "0",
            "size",
            "10")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(
            jsonPath("$.items[0].observation.sourceObservation.value")
                .value("source-history-oldest"));
    queryHistory(REPORTER, "unsupported", "value")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CAPACITY_VALUE_INVALID"));
    queryHistory(REPORTER, "size", "101")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CAPACITY_VALUE_INVALID"));
    queryHistory(HISTORY_REPORTER, "size", "1")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'CAPACITY_OBSERVATION_HISTORY_READ' AND outcome = 'DENIED'"))
        .isEqualTo(1);
  }

  @Test
  void repeatedQueryAtTheSameInstantRebuildsTheSameProjection() throws Exception {
    Instant observedAt = Instant.now().minusSeconds(5);
    submit(
            "deterministic-evidence",
            observationBody("source-deterministic", 9, observedAt.toString()))
        .andExpect(status().isCreated());
    String evaluatedAt = Instant.now().toString();

    String first =
        queryCurrentAt(evaluatedAt, "COORDINATION_BEDS")
            .andReturn()
            .getResponse()
            .getContentAsString();
    String second =
        queryCurrentAt(evaluatedAt, "COORDINATION_BEDS")
            .andReturn()
            .getResponse()
            .getContentAsString();

    Object firstDimension = com.jayway.jsonpath.JsonPath.read(first, "$.dimensions[0]");
    Object secondDimension = com.jayway.jsonpath.JsonPath.read(second, "$.dimensions[0]");
    assertThat(firstDimension).isEqualTo(secondDimension);
    assertThat((Object) com.jayway.jsonpath.JsonPath.read(first, "$.dimensions[0].policyVersion"))
        .isEqualTo(1);
    assertThat(
            (Object) com.jayway.jsonpath.JsonPath.read(first, "$.dimensions[0].projectionVersion"))
        .isEqualTo("capacity-evidence-v1");
  }

  @Test
  void projectionRebuildUsesNewerCommittedObservationWithoutReplacingHistory() throws Exception {
    Instant now = Instant.now();
    submit(
            "projection-before-new-observation",
            observationBody("source-projection-old", 4, now.minusSeconds(20).toString()))
        .andExpect(status().isCreated());
    queryCurrentAt(now.toString(), "COORDINATION_BEDS")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[0].quantity").value(4));

    submit(
            "projection-after-new-observation",
            observationBody("source-projection-new", 7, now.minusSeconds(10).toString()))
        .andExpect(status().isCreated());

    queryCurrentAt(now.toString(), "COORDINATION_BEDS")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[0].quantity").value(7))
        .andExpect(
            jsonPath("$.dimensions[0].evidence[0].sourceObservation.value")
                .value("source-projection-new"));
    assertThat(count("SELECT COUNT(*) FROM capacity.capacity_observation")).isEqualTo(2);
  }

  @Test
  void policyRevisionDoesNotRewriteObservedFactsAndCurrentProjectionUsesActivePolicy()
      throws Exception {
    submit(
            "policy-revision-evidence",
            observationBody("source-under-policy-one", 5, Instant.now().minusSeconds(5).toString()))
        .andExpect(status().isCreated());

    replacePolicySource();

    queryCurrent("COORDINATION_BEDS")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[0].freshness").value("UNKNOWN"))
        .andExpect(jsonPath("$.dimensions[0].policyVersion").value(2))
        .andExpect(jsonPath("$.dimensions[0].evidence.length()").value(0));
    queryHistory(REPORTER, "sourceCode", "manual", "size", "1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].observation.sourcePolicyVersion").value(1))
        .andExpect(jsonPath("$.items[0].observation.quantity").value(5));
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

  private org.springframework.test.web.servlet.ResultActions queryCurrent(String... dimensions)
      throws Exception {
    return queryCurrentAt(null, dimensions);
  }

  private org.springframework.test.web.servlet.ResultActions queryCurrentAt(
      String evaluatedAt, String... dimensions) throws Exception {
    var request =
        get("/api/v1/capacity/facilities/{facilityId}/current", facilityId)
            .header("Authorization", bearer(token(REPORTER)))
            .header("X-AyuVahak-Execution-Scope", "FACILITY")
            .header("X-AyuVahak-Organization-Id", organizationId)
            .header("X-AyuVahak-Facility-Id", facilityId);
    if (evaluatedAt != null) {
      request.param("at", evaluatedAt);
    }
    for (String dimension : dimensions) {
      request.param("dimension", dimension);
    }
    return mockMvc.perform(request);
  }

  private org.springframework.test.web.servlet.ResultActions evaluateReadiness(String dimensions)
      throws Exception {
    return evaluateReadinessRequest(
        REPORTER,
        facilityId,
        "{\"facilityId\":\""
            + facilityId
            + "\",\"requiredDimensions\":"
            + dimensions
            + ",\"evaluationTime\":\""
            + Instant.now()
            + "\"}");
  }

  private org.springframework.test.web.servlet.ResultActions evaluateReadinessRequest(
      String subject, String scopeFacilityId, String body) throws Exception {
    return mockMvc.perform(
        post("/api/v1/capacity/readiness-evaluations")
            .header("Authorization", bearer(token(subject)))
            .header("X-AyuVahak-Execution-Scope", "FACILITY")
            .header("X-AyuVahak-Organization-Id", organizationId)
            .header("X-AyuVahak-Facility-Id", scopeFacilityId)
            .header("X-Correlation-ID", "readiness-invalid-correlation")
            .contentType("application/json")
            .content(body));
  }

  private org.springframework.test.web.servlet.ResultActions queryHistory(
      String subject, String... parameters) throws Exception {
    var request =
        get("/api/v1/capacity/facilities/{facilityId}/observations", facilityId)
            .header("Authorization", bearer(token(subject)))
            .header("X-AyuVahak-Execution-Scope", "FACILITY")
            .header("X-AyuVahak-Organization-Id", organizationId)
            .header("X-AyuVahak-Facility-Id", facilityId);
    for (int index = 0; index < parameters.length; index += 2) {
      request.param(parameters[index], parameters[index + 1]);
    }
    return mockMvc.perform(request);
  }

  private void configureFallbackPolicy() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/capacity/policies/dimensions/COORDINATION_BEDS/policy")
                .header("Authorization", bearer(token(ADMIN)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "policy-fallback-configuration")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":1,\"dimensionVersion\":1,\"sources\":["
                        + "{\"sourceCode\":\"automated\",\"priority\":0,"
                        + "\"fallbackWhenHigherPriorityAbsent\":false,"
                        + "\"fallbackWhenHigherPriorityStale\":false},{"
                        + "\"sourceCode\":\"manual\",\"priority\":1,"
                        + "\"fallbackWhenHigherPriorityAbsent\":true,"
                        + "\"fallbackWhenHigherPriorityStale\":true}],"
                        + "\"maximumObservationAgeSeconds\":600,\"defaultValiditySeconds\":300,"
                        + "\"futureSkewToleranceSeconds\":30,\"minimumPassingQuantity\":1,"
                        + "\"passingStates\":[],\"reasonCode\":\"TEST_CONFIGURATION\"}"))
        .andExpect(status().isOk());
  }

  private void configureEqualPriorityPolicy() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/capacity/policies/dimensions/COORDINATION_BEDS/policy")
                .header("Authorization", bearer(token(ADMIN)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "readiness-equal-priority-policy")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":1,\"dimensionVersion\":1,\"sources\":["
                        + "{\"sourceCode\":\"manual\",\"priority\":0,"
                        + "\"fallbackWhenHigherPriorityAbsent\":false,"
                        + "\"fallbackWhenHigherPriorityStale\":false},{"
                        + "\"sourceCode\":\"manual-replica\",\"priority\":0,"
                        + "\"fallbackWhenHigherPriorityAbsent\":false,"
                        + "\"fallbackWhenHigherPriorityStale\":false}],"
                        + "\"maximumObservationAgeSeconds\":600,\"defaultValiditySeconds\":300,"
                        + "\"futureSkewToleranceSeconds\":30,\"minimumPassingQuantity\":1,"
                        + "\"passingStates\":[],\"reasonCode\":\"TEST_CONFIGURATION\"}"))
        .andExpect(status().isOk());
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

  private void defineDimensionWithoutPolicy(String code) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/capacity/policies/dimensions")
                .header("Authorization", bearer(token(ADMIN)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "dimension-without-policy-" + code)
                .contentType("application/json")
                .content(
                    "{\"code\":\""
                        + code
                        + "\",\"expectedVersion\":0,\"displayName\":\"Synthetic resource\","
                        + "\"meaning\":\"Synthetic capacity evidence requiring configuration\","
                        + "\"valueKind\":\"QUANTITY\",\"unit\":\"unit\",\"minimumValue\":0,"
                        + "\"maximumValue\":500,\"allowedStates\":[],\"usedForReadiness\":true,"
                        + "\"reservable\":false,\"reasonCode\":\"TEST_CONFIGURATION\"}"))
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
    return createFacility(name, "facility-setup");
  }

  private String createFacility(String name, String idempotencyKey) throws Exception {
    var response =
        mockMvc
            .perform(
                post("/api/v1/directory/facilities")
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType("application/json")
                    .content(directoryFacilityBody(organizationId, name)))
            .andExpect(status().isCreated())
            .andReturn();
    return com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.id");
  }

  private ExecutionContext matchingServiceContext(
      String clientId, String facilityId, String correlationId) {
    return executionContextFactory.forBackgroundWork(
        ActorReference.service(new IssuerId(ISSUER), clientId),
        RequestedExecutionScope.facility(
            new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId),
            facilityReference(facilityId)),
        new CorrelationId(correlationId),
        Optional.empty());
  }

  private static ExternalReference facilityReference(String facilityId) {
    return new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facilityId);
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

  private void seedCapacityEvidenceReviewer() {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, organization_issuer, organization_identifier_type, "
            + "organization_identifier, facility_issuer, facility_identifier_type, "
            + "facility_identifier, grant_status, created_at) VALUES (gen_random_uuid(), "
            + "'HUMAN', ?, 'subject', ?, 'CapacityEvidenceReview', 'FACILITY', ?, 'Organization', ?, ?, "
            + "'Facility', ?, 'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        REPORTER,
        DIRECTORY_ISSUER,
        organizationId,
        DIRECTORY_ISSUER,
        facilityId);
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

  private String observationBody(
      String sourceObservationId,
      String sourceCode,
      int quantity,
      String observedAt,
      String validUntil) {
    String reportingMethod = "automated".equals(sourceCode) ? "AUTOMATED" : "MANUAL_ENTRY";
    return "{\"dimensionCode\":\"COORDINATION_BEDS\",\"sourceCode\":\""
        + sourceCode
        + "\",\"sourceIssuer\":\"urn:coordination:synthetic-source\","
        + "\"sourceObservationId\":\""
        + sourceObservationId
        + "\",\"observationKind\":\"VALUE\",\"valueKind\":\"QUANTITY\","
        + "\"quantity\":"
        + quantity
        + ",\"state\":null,\"unit\":\"bed\",\"observedAt\":\""
        + observedAt
        + "\",\"validUntil\":"
        + (validUntil == null ? "null" : "\"" + validUntil + "\"")
        + ",\"reportingMethod\":\""
        + reportingMethod
        + "\",\"reasonCode\":\"CAPACITY_REPORT\"}";
  }

  private String correctionBody(String sourceObservationId, int quantity) {
    return correctionBody(sourceObservationId, quantity, "2026-10-08T09:30:00Z");
  }

  private String correctionBody(String sourceObservationId, int quantity, String observedAt) {
    return "{\"dimensionCode\":\"COORDINATION_BEDS\",\"sourceCode\":\"manual\",\"sourceIssuer\":\"urn:coordination:manual-entry\","
        + "\"sourceObservationId\":\""
        + sourceObservationId
        + "\",\"observationKind\":\"VALUE\",\"valueKind\":\"QUANTITY\","
        + "\"quantity\":"
        + quantity
        + ",\"state\":null,\"unit\":\"bed\",\"observedAt\":\""
        + observedAt
        + "\","
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
