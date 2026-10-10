package org.ayuvahak.transfer.adapter.api;

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
import java.sql.Connection;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.ayuvahak.capacity.application.CapacityReservationCommands;
import org.ayuvahak.capacity.domain.CapacityReservationRequest;
import org.ayuvahak.capacity.domain.CapacityReservationResult;
import org.ayuvahak.directory.application.DirectoryStore;
import org.ayuvahak.directory.domain.DirectoryLifecycleStatus;
import org.ayuvahak.directory.domain.DirectoryProvenance;
import org.ayuvahak.directory.domain.DirectorySource;
import org.ayuvahak.directory.domain.Facility;
import org.ayuvahak.directory.domain.Organization;
import org.ayuvahak.matching.application.RecommendationApplicabilityResult;
import org.ayuvahak.platform.AyuVahakApplication;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.transfer.adapter.configuration.TransferProperties;
import org.ayuvahak.transfer.application.MatchingApplicabilityPort;
import org.ayuvahak.transfer.application.TransferCapacityReservationRecovery;
import org.ayuvahak.transfer.application.TransferRequestExpirySweep;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Transfer's first-request command over HTTP and real disposable PostgreSQL/PostGIS. */
@SpringBootTest(
    classes = AyuVahakApplication.class,
    properties = {
      "management.tracing.sampling.probability=0.0",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-transfer-worker",
      "ayuvahak.authentication.allow-http=true",
      "ayuvahak.transfer.request-deadline=PT1H",
      "ayuvahak.transfer.reservation-dimension=TRANSFER_COORDINATION_SLOT",
      "ayuvahak.transfer.reservation-unit=slot",
      "ayuvahak.transfer.reservation-quantity=1",
      "ayuvahak.transfer.reservation-duration=PT2H",
      "ayuvahak.transfer.expiry-polling-enabled=false",
      "ayuvahak.transfer.reservation-polling-enabled=false"
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(TransferJourneyCommandsApiIT.TestPorts.class)
class TransferJourneyCommandsApiIT {

  private static final String ISSUER = "https://identity.example.test/realms/transfer-fixture";
  private static final String AUDIENCE = "ayuvahak-api";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String SENDER = "synthetic-transfer-sender";
  private static final String TEST_PASSWORD = "local-test-only";
  private static final RSAKey SIGNING_KEY = newSigningKey("transfer-first-request-fixture");
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
  @Autowired private DirectoryStore directory;
  @Autowired private TransferProperties transferProperties;
  @Autowired private StubMatchingApplicability matching;
  @Autowired private TransferRequestExpirySweep expirySweep;
  @Autowired private TransferCapacityReservationRecovery reservationRecovery;
  @Autowired private DataSource dataSource;
  @Autowired private CapacityReservationCommands capacityReservations;
  @Autowired private ExecutionContextFactory executionContexts;
  @Autowired private AutowireCapableBeanFactory beanFactory;
  @Autowired private StubCapacityReservationPort capacityPort;

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

  @BeforeEach
  void resetTransferState() {
    jdbc.execute(
        "TRUNCATE capacity.reservation_command_receipt, "
            + "capacity.coordination_reservation, capacity.reservation_operation");
    jdbc.update("DELETE FROM transfer.progress_command_receipt");
    jdbc.update("DELETE FROM transfer.progress_correction");
    jdbc.update("DELETE FROM transfer.progress_fact");
    jdbc.update("DELETE FROM transfer.command_receipt");
    jdbc.update("DELETE FROM transfer.capacity_reservation_request");
    jdbc.update("DELETE FROM transfer.request_decision");
    jdbc.update("DELETE FROM transfer.transfer_record");
    jdbc.update("DELETE FROM transfer.receiving_assessment");
    jdbc.update("DELETE FROM transfer.request_attempt");
    jdbc.update("DELETE FROM transfer.journey");
    jdbc.update("DELETE FROM audit.audit_record");
    jdbc.update("DELETE FROM identity_access.grant_command_receipt");
    jdbc.update("DELETE FROM identity_access.bootstrap_marker");
    jdbc.update("DELETE FROM identity_access.authorization_grant");
    transferProperties.setRequestDeadline(java.time.Duration.ofHours(1));
    transferProperties.setReservationPollInterval(java.time.Duration.ofSeconds(15));
    matching.reset();
    capacityPort.reset();
  }

  @Test
  void authorizedSenderCreatesOneJourneyAndFirstRequestOnlyAfterExactApplicableCheck()
      throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());

    var result =
        createRequest(scope, "first-request-01")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.attemptNumber").value(1))
            .andExpect(jsonPath("$.referralId").value(scope.referralId()))
            .andExpect(jsonPath("$.referralRevision").value(4))
            .andExpect(jsonPath("$.recommendationId").value(scope.recommendationId()))
            .andExpect(jsonPath("$.destinationFacilityId").value(scope.destinationFacilityId()))
            .andReturn();

    assertThat(matching.calls()).isEqualTo(1);
    assertThat(matching.lastRecommendationId())
        .isEqualTo(InternalId.parse(scope.recommendationId()));
    assertThat(matching.lastCandidateFacility())
        .isEqualTo(directoryReference("Facility", scope.destinationFacilityId()));
    assertThat(matching.lastReferralRevision()).isEqualTo(4L);
    assertThat(matching.lastExpectedReferralId()).isEqualTo(InternalId.parse(scope.referralId()));
    assertThat(matching.lastCaller().scope().organization().orElseThrow().value())
        .isEqualTo(scope.originOrganizationId());

    String response = result.getResponse().getContentAsString();
    assertThat(com.jayway.jsonpath.JsonPath.read(response, "$.journeyId").toString()).isNotBlank();
    assertThat(com.jayway.jsonpath.JsonPath.read(response, "$.requestAttemptId").toString())
        .isNotBlank();
    assertThat(count("SELECT COUNT(*) FROM transfer.journey")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM transfer.request_attempt")).isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'TRANSFER_REQUEST_CREATE'"))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT request_status FROM transfer.request_attempt", String.class))
        .isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'transfer' "
                    + "AND column_name ILIKE '%patient%'",
                Integer.class))
        .isZero();
  }

  @Test
  void exactRetryReplaysAndDifferentCandidateWithSameKeyConflicts() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());

    var first =
        createRequest(scope, "stable-request-key").andExpect(status().isCreated()).andReturn();
    String journeyId =
        com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.journeyId");

    int callsAfterFirst = matching.calls();
    createRequest(scope, "stable-request-key")
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.journeyId").value(journeyId));
    assertThat(matching.calls()).isEqualTo(callsAfterFirst);

    Scope changed = scope.withDifferentDestination();
    createRequest(changed, "stable-request-key")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    assertThat(count("SELECT COUNT(*) FROM transfer.journey")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM transfer.request_attempt")).isEqualTo(1);
  }

  @Test
  void nonApplicableOrIndeterminateEvidenceFailsClosedWithoutCreatingJourney() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnStatus(RecommendationApplicabilityResult.Status.NOT_APPLICABLE);

    createRequest(scope, "stale-recommendation")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSFER_RECOMMENDATION_STALE"));
    assertThat(count("SELECT COUNT(*) FROM transfer.journey")).isZero();

    matching.returnStatus(RecommendationApplicabilityResult.Status.INDETERMINATE);
    createRequest(scope, "indeterminate-recommendation")
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("TRANSFER_MATCHING_UNAVAILABLE"));
    assertThat(count("SELECT COUNT(*) FROM transfer.journey")).isZero();
    assertThat(count("SELECT COUNT(*) FROM transfer.request_attempt")).isZero();
  }

  @Test
  void recommendationForDifferentReferralCannotAuthorizeJourneyCreation() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());

    createRequest(scope.withDifferentReferralId(), "recommendation-for-another-referral")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSFER_RECOMMENDATION_STALE"));

    assertThat(matching.calls()).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM transfer.journey")).isZero();
    assertThat(count("SELECT COUNT(*) FROM transfer.request_attempt")).isZero();
  }

  @Test
  void missingDeadlinePolicyAndUnauthorizedSenderFailBeforePersistence() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    transferProperties.setRequestDeadline(null);

    createRequest(scope, "missing-deadline")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("TRANSFER_EXPIRY_POLICY_UNCONFIGURED"));
    assertThat(count("SELECT COUNT(*) FROM transfer.journey")).isZero();
    assertThat(matching.calls()).isZero();

    createRequest(scope, "unauthorized-sender", "synthetic-transfer-unprivileged")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));
    assertThat(count("SELECT COUNT(*) FROM transfer.journey")).isZero();
    assertThat(matching.calls()).isZero();
  }

  @Test
  void separatePositiveReceivingAssessmentsPermitExplicitAcceptanceOnly() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String requestAttemptId =
        com.jayway.jsonpath.JsonPath.read(
            createRequest(scope, "assessed-request-01")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.requestAttemptId");

    grantReceivingAuthority(scope, "synthetic-clinical-reviewer", "ClinicalSuitabilityAuthority");
    grantReceivingAuthority(
        scope, "synthetic-readiness-coordinator", "OperationalReadinessAuthority");
    grantReceivingAuthority(scope, "synthetic-receiving-authority", "AcceptanceAuthority");

    receivingCommand(
            scope,
            requestAttemptId,
            "clinical-suitability-assessments",
            "synthetic-clinical-reviewer",
            "clinical-support-01",
            "{\"expectedVersion\":1,\"outcome\":\"SUPPORTS\","
                + "\"reasonCode\":\"CLINICAL_REVIEW_COMPLETE\","
                + "\"occurredAt\":\"2026-10-10T10:00:00Z\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.assessmentKind").value("CLINICAL_SUITABILITY"))
        .andExpect(jsonPath("$.outcome").value("SUPPORTS"))
        .andExpect(jsonPath("$.requestVersion").value(2));

    receivingCommand(
            scope,
            requestAttemptId,
            "operational-readiness-assessments",
            "synthetic-readiness-coordinator",
            "readiness-support-01",
            "{\"expectedVersion\":2,\"outcome\":\"SUPPORTS\","
                + "\"reasonCode\":\"READINESS_CONFIRMED\","
                + "\"occurredAt\":\"2026-10-10T10:01:00Z\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.assessmentKind").value("OPERATIONAL_READINESS"))
        .andExpect(jsonPath("$.outcome").value("SUPPORTS"))
        .andExpect(jsonPath("$.requestVersion").value(3));

    var accepted =
        receivingCommand(
                scope,
                requestAttemptId,
                "acceptance",
                "synthetic-receiving-authority",
                "acceptance-01",
                "{\"expectedVersion\":3,\"occurredAt\":\"2026-10-10T10:02:00Z\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("AWAITING_RESERVATION"))
            .andReturn();
    String transferId =
        com.jayway.jsonpath.JsonPath.read(
            accepted.getResponse().getContentAsString(), "$.transferId");

    receivingCommand(
            scope,
            requestAttemptId,
            "acceptance",
            "synthetic-receiving-authority",
            "acceptance-01",
            "{\"expectedVersion\":3,\"occurredAt\":\"2026-10-10T10:02:00Z\"}")
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.transferId").value(transferId));

    assertThat(count("SELECT COUNT(*) FROM transfer.receiving_assessment")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT request_status FROM transfer.request_attempt WHERE request_attempt_id = ?",
                String.class,
                UUID.fromString(requestAttemptId)))
        .isEqualTo("ACCEPTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM transfer.request_attempt WHERE request_attempt_id = ?",
                Long.class,
                UUID.fromString(requestAttemptId)))
        .isEqualTo(4L);
    assertThat(
            count(
                "SELECT COUNT(*) FROM transfer.request_decision WHERE decision_kind = 'ACCEPTED'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM transfer.transfer_record WHERE transfer_status = 'AWAITING_RESERVATION'"))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM transfer.command_receipt")).isEqualTo(4);
    assertThat(count("SELECT COUNT(*) FROM audit.audit_record WHERE action_code LIKE 'TRANSFER_%'"))
        .isEqualTo(5);
    assertThat(count("SELECT COUNT(*) FROM platform_eventing.internal_event")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.transfer_record WHERE transfer_id = ? "
                    + "AND transfer_status = 'AWAITING_RESERVATION'",
                Integer.class,
                UUID.fromString(transferId)))
        .isEqualTo(1);
  }

  @Test
  void acceptancePersistsConfiguredReservationOperationForRecovery() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String requestAttemptId =
        com.jayway.jsonpath.JsonPath.read(
            createRequest(scope, "reservation-intent-request")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.requestAttemptId");
    preparePositiveAcceptance(scope, requestAttemptId);

    String response =
        receivingCommand(
                scope,
                requestAttemptId,
                "acceptance",
                "synthetic-receiver",
                "reservation-intent-acceptance",
                "{\"expectedVersion\":3,\"occurredAt\":\"2026-10-10T10:50:00Z\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("AWAITING_RESERVATION"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID transferId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.transferId"));

    var intent =
        jdbc.queryForMap(
            "SELECT operation_id, facility_identifier, dimension_code, unit_code, quantity, "
                + "interval_end > interval_start AS valid_interval, expires_at = interval_end "
                + "AS expiry_matches_interval, outcome FROM transfer.capacity_reservation_request "
                + "WHERE transfer_id = ?",
            transferId);

    assertThat(intent.get("operation_id")).isEqualTo(transferId);
    assertThat(intent.get("facility_identifier"))
        .isEqualTo(UUID.fromString(scope.destinationFacilityId()));
    assertThat(intent.get("dimension_code")).isEqualTo("TRANSFER_COORDINATION_SLOT");
    assertThat(intent.get("unit_code")).isEqualTo("slot");
    assertThat((java.math.BigDecimal) intent.get("quantity")).isEqualByComparingTo("1");
    assertThat(intent.get("valid_interval")).isEqualTo(true);
    assertThat(intent.get("expiry_matches_interval")).isEqualTo(true);
    assertThat(intent.get("outcome")).isEqualTo("PENDING");
  }

  @Test
  void capacityConfirmationPromotesAcceptedTransferAndLastUnitConflictStaysUnreserved()
      throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    ensureReservableCapacityCatalog();
    grantCapacityReporter(scope);
    recordCoordinationSlotObservation(scope, 1, "capacity-one-unit");
    grantReservationWorker(scope);

    String firstRequest = createRequestAttempt(scope, "capacity-first-request");
    preparePositiveAcceptance(scope, firstRequest);
    String firstResponse = acceptRequest(scope, firstRequest, "capacity-first-accept");
    UUID firstTransfer =
        UUID.fromString(com.jayway.jsonpath.JsonPath.read(firstResponse, "$.transferId"));

    assertThat(com.jayway.jsonpath.JsonPath.read(firstResponse, "$.status").toString())
        .isEqualTo("READY");
    assertThat(count("SELECT COUNT(*) FROM capacity.coordination_reservation")).isEqualTo(1);
    assertThat(
            count("SELECT COUNT(*) FROM capacity.reservation_operation WHERE outcome = 'RESERVED'"))
        .isEqualTo(1);

    Scope secondScope = scope.withDifferentReferralId();
    matching.returnApplicable(secondScope.referralId());
    String secondRequest = createRequestAttempt(secondScope, "capacity-second-request");
    preparePositiveAcceptance(secondScope, secondRequest);
    String secondResponse = acceptRequest(secondScope, secondRequest, "capacity-second-accept");
    UUID secondTransfer =
        UUID.fromString(com.jayway.jsonpath.JsonPath.read(secondResponse, "$.transferId"));

    assertThat(com.jayway.jsonpath.JsonPath.read(secondResponse, "$.status").toString())
        .isEqualTo("AWAITING_RESERVATION");
    assertThat(count("SELECT COUNT(*) FROM capacity.coordination_reservation")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                secondTransfer))
        .isEqualTo("CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                secondTransfer))
        .isEqualTo("AWAITING_RESERVATION");
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                firstTransfer))
        .isEqualTo("READY");
  }

  @Test
  void transferProgressKeepsArrivalHandoverAndCompletionDistinctAndPublishesOnce()
      throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "progress-happy");
    grantFacilityAuthority("synthetic-transfer-mover", "TransferProgressAuthority", scope);
    grantFacilityAuthority("synthetic-transfer-receiver", "TransferProgressAuthority", scope);
    grantFacilityAuthority("synthetic-transfer-receiver", "TransferHandoverAuthority", scope);
    grantFacilityAuthority("synthetic-transfer-receiver", "TransferCompletionAuthority", scope);

    progressCommand(
            scope,
            transferId,
            "departure",
            "synthetic-transfer-mover",
            "progress-departure",
            "{\"expectedVersion\":2,\"occurredAt\":\"2026-10-10T10:00:00Z\","
                + "\"sourceReference\":\"synthetic:departure-1\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
        .andExpect(jsonPath("$.version").value(3));
    progressCommand(
            scope,
            transferId,
            "arrival",
            "synthetic-transfer-mover",
            "progress-arrival",
            "{\"expectedVersion\":3,\"occurredAt\":\"2026-10-10T10:45:00Z\","
                + "\"sourceReference\":\"synthetic:arrival-1\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ARRIVED"));
    progressCommand(
            scope,
            transferId,
            "handover",
            "synthetic-transfer-receiver",
            "progress-handover",
            "{\"expectedVersion\":4,\"occurredAt\":\"2026-10-10T10:50:00Z\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("HANDED_OVER"));
    var completed =
        progressCommand(
                scope,
                transferId,
                "completion",
                "synthetic-transfer-receiver",
                "progress-completion",
                "{\"expectedVersion\":5,\"occurredAt\":\"2026-10-10T10:55:00Z\","
                    + "\"reasonCode\":\"COORDINATION_CLOSED\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andReturn();
    String journeyId =
        com.jayway.jsonpath.JsonPath.read(
            completed.getResponse().getContentAsString(), "$.journeyId");

    progressCommand(
            scope,
            transferId,
            "completion",
            "synthetic-transfer-receiver",
            "progress-completion",
            "{\"expectedVersion\":5,\"occurredAt\":\"2026-10-10T10:55:00Z\","
                + "\"reasonCode\":\"COORDINATION_CLOSED\"}")
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"));

    mockMvc
        .perform(
            get("/api/v1/transfers/{transferId}/progress", transferId)
                .header("Authorization", "Bearer " + token("synthetic-transfer-receiver"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.destinationFacilityId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.facts.length()").value(4))
        .andExpect(jsonPath("$.facts[0].kind").value("DEPARTURE"))
        .andExpect(jsonPath("$.facts[1].kind").value("ARRIVAL"))
        .andExpect(jsonPath("$.facts[2].kind").value("HANDOVER"))
        .andExpect(jsonPath("$.facts[3].kind").value("COMPLETION"));

    assertThat(
            count(
                "SELECT COUNT(*) FROM platform_eventing.internal_event WHERE event_type = "
                    + "'transfer.completed.v1' AND aggregate_ref = 'transfer-journey/"
                    + journeyId
                    + "'"))
        .isEqualTo(1);
  }

  @Test
  void failedProgressHistoryQueryIsNotAuditedAsSuccessful() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "progress-history-query-failure");
    grantFacilityAuthority("synthetic-transfer-receiver", "TransferProgressAuthority", scope);

    jdbc.execute(
        "ALTER TABLE transfer.progress_correction "
            + "RENAME TO progress_correction_temporarily_unavailable");
    try {
      mockMvc
          .perform(
              get("/api/v1/transfers/{transferId}/progress", transferId)
                  .header("Authorization", "Bearer " + token("synthetic-transfer-receiver"))
                  .header("X-AyuVahak-Execution-Scope", "FACILITY")
                  .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                  .header("X-AyuVahak-Facility-Id", scope.destinationFacilityId()))
          .andExpect(status().is5xxServerError());

      assertThat(
              count(
                  "SELECT COUNT(*) FROM audit.audit_record WHERE "
                      + "action_code = 'TRANSFER_PROGRESS_READ' AND outcome = 'SUCCEEDED'"))
          .isZero();
    } finally {
      jdbc.execute(
          "ALTER TABLE transfer.progress_correction_temporarily_unavailable "
              + "RENAME TO progress_correction");
    }
  }

  @Test
  void lateArrivalAfterInterruptionPreservesBothFactsAndMarksTheException() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "arrival-after-interruption");
    grantFacilityAuthority("synthetic-transfer-receiver", "TransferProgressAuthority", scope);

    progressCommand(
            scope,
            transferId,
            "departure",
            "synthetic-transfer-receiver",
            "interrupted-progress-departure",
            "{\"expectedVersion\":2,\"occurredAt\":\"2026-10-10T10:00:00Z\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
    progressCommand(
            scope,
            transferId,
            "interruption",
            "synthetic-transfer-receiver",
            "interrupted-progress-interruption",
            "{\"expectedVersion\":3,\"occurredAt\":\"2026-10-10T10:20:00Z\","
                + "\"reasonCode\":\"ROUTE_INTERRUPTED\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("INTERRUPTED"));
    progressCommand(
            scope,
            transferId,
            "arrival",
            "synthetic-transfer-receiver",
            "interrupted-progress-late-arrival",
            "{\"expectedVersion\":4,\"occurredAt\":\"2026-10-10T10:45:00Z\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ARRIVED"))
        .andExpect(jsonPath("$.exceptionCode").value("ARRIVAL_REPORTED_AFTER_INTERRUPTION"));

    mockMvc
        .perform(
            get("/api/v1/transfers/{transferId}/progress", transferId)
                .header("Authorization", "Bearer " + token("synthetic-transfer-receiver"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.destinationFacilityId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.facts.length()").value(3))
        .andExpect(jsonPath("$.facts[0].kind").value("DEPARTURE"))
        .andExpect(jsonPath("$.facts[1].kind").value("INTERRUPTION"))
        .andExpect(jsonPath("$.facts[2].kind").value("ARRIVAL"))
        .andExpect(
            jsonPath("$.facts[2].exceptionCode").value("ARRIVAL_REPORTED_AFTER_INTERRUPTION"));
  }

  @Test
  void actualArrivalBeforeReservationOrDigitalDepartureIsRecordedWithoutFabricatedMovement()
      throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    ensureReservableCapacityCatalog();
    grantCapacityReporter(scope);
    recordCoordinationSlotObservation(scope, 1, "late-arrival-capacity");
    grantReservationWorker(scope);
    String requestAttempt = createRequestAttempt(scope, "late-arrival-request");
    preparePositiveAcceptance(scope, requestAttempt);
    capacityPort.failNext(new IllegalStateException("Synthetic delayed Capacity response"));
    String accepted = acceptRequest(scope, requestAttempt, "late-arrival-acceptance");
    String transferId = com.jayway.jsonpath.JsonPath.read(accepted, "$.transferId");
    assertThat(com.jayway.jsonpath.JsonPath.read(accepted, "$.status").toString())
        .isEqualTo("AWAITING_RESERVATION");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                UUID.fromString(transferId)))
        .isEqualTo("UNAVAILABLE");
    grantFacilityAuthority("synthetic-transfer-receiver", "TransferProgressAuthority", scope);

    progressCommand(
            scope,
            transferId,
            "arrival",
            "synthetic-transfer-receiver",
            "late-arrival-fact",
            "{\"expectedVersion\":1,\"occurredAt\":\"2026-10-10T09:30:00Z\","
                + "\"sourceReference\":\"synthetic:arrival-before-update\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ARRIVED"))
        .andExpect(jsonPath("$.exceptionCode").value("ARRIVAL_BEFORE_RESERVATION_CONFIRMATION"));

    jdbc.update(
        "UPDATE transfer.capacity_reservation_request SET next_attempt_at = CURRENT_TIMESTAMP - "
            + "INTERVAL '1 second' WHERE transfer_id = ?",
        UUID.fromString(transferId));
    assertThat(reservationRecovery.reconcileDue()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                UUID.fromString(transferId)))
        .isEqualTo("RESERVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                UUID.fromString(transferId)))
        .isEqualTo("ARRIVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM transfer.transfer_record WHERE transfer_id = ?",
                Long.class,
                UUID.fromString(transferId)))
        .isEqualTo(2L);

    mockMvc
        .perform(
            get("/api/v1/transfers/{transferId}/progress", transferId)
                .header("Authorization", "Bearer " + token("synthetic-transfer-receiver"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.destinationFacilityId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.facts.length()").value(1))
        .andExpect(jsonPath("$.facts[0].kind").value("ARRIVAL"));
  }

  @Test
  void progressCorrectionReferencesOriginalWithoutRewritingFactOrTransferState() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "progress-correction");
    grantFacilityAuthority("synthetic-transfer-receiver", "TransferProgressAuthority", scope);

    progressCommand(
            scope,
            transferId,
            "departure",
            "synthetic-transfer-receiver",
            "correction-departure",
            "{\"expectedVersion\":2,\"occurredAt\":\"2026-10-10T10:00:00Z\"}")
        .andExpect(status().isCreated());
    var arrival =
        progressCommand(
                scope,
                transferId,
                "arrival",
                "synthetic-transfer-receiver",
                "correction-arrival",
                "{\"expectedVersion\":3,\"occurredAt\":\"2026-10-10T10:45:00Z\"}")
            .andExpect(status().isCreated())
            .andReturn();
    String arrivalFactId =
        com.jayway.jsonpath.JsonPath.read(
            arrival.getResponse().getContentAsString(), "$.progressFactId");

    mockMvc
        .perform(
            post(
                    "/api/v1/transfers/{transferId}/progress/{progressFactId}/corrections",
                    transferId,
                    arrivalFactId)
                .header("Authorization", "Bearer " + token("synthetic-unauthorized-corrector"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.destinationFacilityId())
                .header("Idempotency-Key", "unauthorized-arrival-correction")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":4,\"correctionKind\":\"OCCURRENCE_TIME\","
                        + "\"correctedOccurredAt\":\"2026-10-10T10:42:00Z\","
                        + "\"reasonCode\":\"SOURCE_TIME_CORRECTED\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));

    var correction =
        mockMvc
            .perform(
                post(
                        "/api/v1/transfers/{transferId}/progress/{progressFactId}/corrections",
                        transferId,
                        arrivalFactId)
                    .header("Authorization", "Bearer " + token("synthetic-transfer-receiver"))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", scope.destinationFacilityId())
                    .header("Idempotency-Key", "arrival-time-correction")
                    .contentType("application/json")
                    .content(
                        "{\"expectedVersion\":4,\"correctionKind\":\"OCCURRENCE_TIME\","
                            + "\"correctedOccurredAt\":\"2026-10-10T10:42:00Z\","
                            + "\"reasonCode\":\"SOURCE_TIME_CORRECTED\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.correctsProgressFactId").value(arrivalFactId))
            .andExpect(jsonPath("$.correctionKind").value("OCCURRENCE_TIME"))
            .andExpect(jsonPath("$.correctedOccurredAt").value("2026-10-10T10:42:00Z"))
            .andReturn();
    String correctionId =
        com.jayway.jsonpath.JsonPath.read(
            correction.getResponse().getContentAsString(), "$.correctionId");

    mockMvc
        .perform(
            post(
                    "/api/v1/transfers/{transferId}/progress/{progressFactId}/corrections",
                    transferId,
                    arrivalFactId)
                .header("Authorization", "Bearer " + token("synthetic-transfer-receiver"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.destinationFacilityId())
                .header("Idempotency-Key", "arrival-time-correction")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":4,\"correctionKind\":\"OCCURRENCE_TIME\","
                        + "\"correctedOccurredAt\":\"2026-10-10T10:42:00Z\","
                        + "\"reasonCode\":\"SOURCE_TIME_CORRECTED\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"));

    mockMvc
        .perform(
            post(
                    "/api/v1/transfers/{transferId}/progress/{progressFactId}/corrections",
                    transferId,
                    arrivalFactId)
                .header("Authorization", "Bearer " + token("synthetic-transfer-receiver"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.destinationFacilityId())
                .header("Idempotency-Key", "arrival-result-correction")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":4,\"correctionKind\":\"REPORTED_RESULT\","
                        + "\"correctedResultCode\":\"ARRIVAL_REPORT_RETRACTED\","
                        + "\"reasonCode\":\"REPORT_RETRACTED\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.correctsProgressFactId").value(arrivalFactId))
        .andExpect(jsonPath("$.correctionKind").value("REPORTED_RESULT"))
        .andExpect(jsonPath("$.correctedResultCode").value("ARRIVAL_REPORT_RETRACTED"));

    mockMvc
        .perform(
            get("/api/v1/transfers/{transferId}/progress", transferId)
                .header("Authorization", "Bearer " + token("synthetic-transfer-receiver"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.destinationFacilityId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ARRIVED"))
        .andExpect(jsonPath("$.version").value(4))
        .andExpect(jsonPath("$.facts[1].occurredAt").value("2026-10-10T10:45:00Z"))
        .andExpect(jsonPath("$.corrections.length()").value(2))
        .andExpect(jsonPath("$.corrections[0].correctionId").value((Object) correctionId))
        .andExpect(
            jsonPath("$.corrections[1].correctedResultCode").value("ARRIVAL_REPORT_RETRACTED"));
  }

  @Test
  void staleCapacityEvidenceCannotReserveOrPromoteAcceptedTransfer() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    ensureReservableCapacityCatalog();
    grantCapacityReporter(scope);
    Instant now = Instant.now();
    recordCoordinationSlotObservation(
        scope, 1, "capacity-stale-observation", now.minusSeconds(1_200), now.plusSeconds(1_200));
    grantReservationWorker(scope);

    String requestAttempt = createRequestAttempt(scope, "capacity-stale-request");
    preparePositiveAcceptance(scope, requestAttempt);
    String response = acceptRequest(scope, requestAttempt, "capacity-stale-accept");
    UUID transferId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.transferId"));

    assertThat(com.jayway.jsonpath.JsonPath.read(response, "$.status").toString())
        .isEqualTo("AWAITING_RESERVATION");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("UNKNOWN");
    assertThat(
            jdbc.queryForObject(
                "SELECT reason_code FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("OBSERVATION_TOO_OLD");
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("AWAITING_RESERVATION");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM capacity.reservation_operation WHERE operation_id = ?",
                String.class,
                transferId))
        .isEqualTo("UNKNOWN");
    assertThat(count("SELECT COUNT(*) FROM capacity.coordination_reservation")).isZero();
  }

  @Test
  void downstreamAuthorizationDenialAfterAcceptanceIsPersistedAsNonRetryable() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    ensureReservableCapacityCatalog();
    grantCapacityReporter(scope);
    recordCoordinationSlotObservation(scope, 1, "capacity-refusal-one-unit");
    grantTransferReservationWorker(scope); // CapacityReservation deliberately absent.

    String requestAttempt = createRequestAttempt(scope, "capacity-refusal-request");
    preparePositiveAcceptance(scope, requestAttempt);
    String response = acceptRequest(scope, requestAttempt, "capacity-refusal-accept");
    UUID transferId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.transferId"));

    assertThat(com.jayway.jsonpath.JsonPath.read(response, "$.status").toString())
        .isEqualTo("AWAITING_RESERVATION");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("INVALID");
    assertThat(
            jdbc.queryForObject(
                "SELECT reason_code FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("AUTHORIZATION_DENIED");
    assertThat(count("SELECT COUNT(*) FROM capacity.reservation_operation")).isZero();
    assertThat(count("SELECT COUNT(*) FROM capacity.coordination_reservation")).isZero();
    assertThat(count("SELECT COUNT(*) FROM capacity.reservation_command_receipt")).isZero();
  }

  @Test
  void missingTransferReservationAuthorityIsAuditedAndLeavesTransferUnreserved() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    ensureReservableCapacityCatalog();
    grantCapacityReporter(scope);
    recordCoordinationSlotObservation(scope, 1, "capacity-transfer-authority-denial");

    String requestAttempt = createRequestAttempt(scope, "transfer-authority-denial-request");
    preparePositiveAcceptance(scope, requestAttempt);
    String response = acceptRequest(scope, requestAttempt, "transfer-authority-denial-accept");
    UUID transferId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.transferId"));

    assertThat(com.jayway.jsonpath.JsonPath.read(response, "$.status").toString())
        .isEqualTo("AWAITING_RESERVATION");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("PENDING");
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record "
                    + "WHERE action_code = 'TRANSFER_RESERVATION_RECONCILIATION_DENIED'"))
        .isEqualTo(1);
    assertThat(capacityPort.idempotencyKeys()).isEmpty();
    assertThat(count("SELECT COUNT(*) FROM capacity.reservation_operation")).isZero();
    assertThat(count("SELECT COUNT(*) FROM capacity.coordination_reservation")).isZero();
    assertThat(count("SELECT COUNT(*) FROM capacity.reservation_command_receipt")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("AWAITING_RESERVATION");
  }

  @Test
  void unexpectedCapacityServerFailureRemainsUnavailableAndNeverClaimsReadiness() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    grantTransferReservationWorker(scope);

    String requestAttempt = createRequestAttempt(scope, "capacity-unexpected-failure-request");
    preparePositiveAcceptance(scope, requestAttempt);
    capacityPort.failNext(
        new PlatformProblemException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            PlatformErrorCode.INTERNAL_ERROR,
            "Synthetic owner failure"));
    String response = acceptRequest(scope, requestAttempt, "capacity-unexpected-failure-accept");
    UUID transferId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.transferId"));

    assertThat(com.jayway.jsonpath.JsonPath.read(response, "$.status").toString())
        .isEqualTo("AWAITING_RESERVATION");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("UNAVAILABLE");
    assertThat(
            jdbc.queryForObject(
                "SELECT reason_code FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("INTERNAL_ERROR");
    assertThat(capacityPort.idempotencyKeys()).containsExactly("transfer-capacity-" + transferId);
    assertThat(count("SELECT COUNT(*) FROM capacity.coordination_reservation")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("AWAITING_RESERVATION");
  }

  @Test
  void invalidCapacityReservationRequestIsNonRetryableAndNeverClaimsReadiness() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    grantTransferReservationWorker(scope);

    String requestAttempt = createRequestAttempt(scope, "capacity-invalid-request");
    preparePositiveAcceptance(scope, requestAttempt);
    capacityPort.failNext(new IllegalArgumentException("Synthetic invalid reservation request"));
    String response = acceptRequest(scope, requestAttempt, "capacity-invalid-accept");
    UUID transferId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.transferId"));

    assertThat(com.jayway.jsonpath.JsonPath.read(response, "$.status").toString())
        .isEqualTo("AWAITING_RESERVATION");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("INVALID");
    assertThat(
            jdbc.queryForObject(
                "SELECT reason_code FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("CAPACITY_REQUEST_INVALID");
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("AWAITING_RESERVATION");
    assertThat(capacityPort.idempotencyKeys()).containsExactly("transfer-capacity-" + transferId);
    assertThat(count("SELECT COUNT(*) FROM capacity.coordination_reservation")).isZero();
  }

  @Test
  void capacityLastUnitIsSerializedAndExactKeyReplayKeepsItsRecordedOutcome() throws Exception {
    Scope scope = createOriginAndDestination();
    ensureReservableCapacityCatalog();
    grantCapacityReporter(scope);
    recordCoordinationSlotObservation(scope, 1, "capacity-concurrent-one-unit");
    grantReservationWorker(scope);

    var context = reservationContext(scope);
    Instant intervalStart = Instant.now().plusSeconds(120);
    Instant intervalEnd = intervalStart.plusSeconds(3600);
    InternalId transferOne = InternalId.generate();
    InternalId transferTwo = InternalId.generate();
    var start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<CapacityReservationResult> first =
          executor.submit(
              () ->
                  reserve(
                      context,
                      transferOne,
                      scope.destinationFacilityId(),
                      intervalStart,
                      intervalEnd,
                      start));
      Future<CapacityReservationResult> second =
          executor.submit(
              () ->
                  reserve(
                      context,
                      transferTwo,
                      scope.destinationFacilityId(),
                      intervalStart,
                      intervalEnd,
                      start));
      start.countDown();
      var outcomes =
          java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
      assertThat(
              outcomes.stream()
                  .filter(r -> r.outcome() == CapacityReservationResult.Outcome.RESERVED))
          .hasSize(1);
      assertThat(
              outcomes.stream()
                  .filter(r -> r.outcome() == CapacityReservationResult.Outcome.CONFLICT))
          .hasSize(1);
      assertThat(count("SELECT COUNT(*) FROM capacity.coordination_reservation")).isEqualTo(1);

      CapacityReservationResult conflict =
          outcomes.stream()
              .filter(r -> r.outcome() == CapacityReservationResult.Outcome.CONFLICT)
              .findFirst()
              .orElseThrow();
      CapacityReservationRequest sameOperation =
          reservationRequest(
              conflict.operationId(), intervalStart, intervalEnd, scope.destinationFacilityId());
      CapacityReservationResult exactReplay =
          capacityReservations.reserve(
              context, sameOperation, "transfer-capacity-" + conflict.operationId());
      assertThat(exactReplay.outcome()).isEqualTo(CapacityReservationResult.Outcome.CONFLICT);
      assertThat(exactReplay.replayed()).isTrue();

      recordCoordinationSlotObservation(scope, 2, "capacity-operation-outcome-immutable");
      CapacityReservationResult newKeyOperationReplay =
          capacityReservations.reserve(
              context, sameOperation, "new-key-same-operation-" + conflict.operationId());
      assertThat(newKeyOperationReplay.outcome())
          .isEqualTo(CapacityReservationResult.Outcome.CONFLICT);
      assertThat(newKeyOperationReplay.replayed()).isTrue();
      CapacityReservationResult immutableReceiptReplay =
          capacityReservations.reserve(
              context, sameOperation, "transfer-capacity-" + conflict.operationId());
      assertThat(immutableReceiptReplay.outcome())
          .isEqualTo(CapacityReservationResult.Outcome.CONFLICT);
      assertThat(immutableReceiptReplay.reservationId()).isEmpty();
      assertThat(immutableReceiptReplay.replayed()).isTrue();
      assertThat(count("SELECT COUNT(*) FROM capacity.coordination_reservation")).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void expiredCapacityReservationRequestIsRejectedWithoutCreatingHold() throws Exception {
    Scope scope = createOriginAndDestination();
    ensureReservableCapacityCatalog();
    grantCapacityReporter(scope);
    recordCoordinationSlotObservation(scope, 2, "capacity-expired-request");
    grantReservationWorker(scope);

    var context = reservationContext(scope);
    Instant intervalEnd = Instant.now().minusSeconds(60);
    Instant intervalStart = intervalEnd.minusSeconds(3600);
    InternalId transferId = InternalId.generate();
    CapacityReservationRequest expiredRequest =
        reservationRequest(transferId, intervalStart, intervalEnd, scope.destinationFacilityId());

    CapacityReservationResult result =
        capacityReservations.reserve(
            context, expiredRequest, "expired-capacity-request-" + transferId);

    assertThat(result.outcome()).isEqualTo(CapacityReservationResult.Outcome.INVALID);
    assertThat(result.reasonCode()).contains("RESERVATION_EXPIRY_NOT_FUTURE");
    assertThat(result.reservationId()).isEmpty();
    assertThat(count("SELECT COUNT(*) FROM capacity.coordination_reservation")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM capacity.reservation_operation WHERE operation_id = ?",
                String.class,
                transferId.value()))
        .isEqualTo("INVALID");

    CapacityReservationRequest conflictingOperation =
        new CapacityReservationRequest(
            InternalId.generate(),
            expiredRequest.transferId(),
            expiredRequest.facility(),
            expiredRequest.dimensionCode(),
            expiredRequest.unit(),
            expiredRequest.quantity(),
            expiredRequest.intervalStart(),
            expiredRequest.intervalEnd(),
            expiredRequest.expiresAt());
    assertThatThrownBy(
            () ->
                capacityReservations.reserve(
                    context, conflictingOperation, "different-operation-same-transfer"))
        .isInstanceOf(PlatformProblemException.class)
        .satisfies(
            failure ->
                assertThat(((PlatformProblemException) failure).code())
                    .isEqualTo(PlatformErrorCode.CAPACITY_RESERVATION_CONFLICT));

    CapacityReservationResult newKeyReplay =
        capacityReservations.reserve(
            context, expiredRequest, "expired-capacity-request-retry-" + transferId);
    assertThat(newKeyReplay.outcome()).isEqualTo(CapacityReservationResult.Outcome.INVALID);
    assertThat(newKeyReplay.reasonCode()).contains("RESERVATION_EXPIRY_NOT_FUTURE");
    assertThat(newKeyReplay.replayed()).isTrue();
    assertThat(count("SELECT COUNT(*) FROM capacity.coordination_reservation")).isZero();
  }

  @Test
  void freshRecoveryInstanceReconcilesCapacityCommitAfterLostResponseUsingSameKey()
      throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    ensureReservableCapacityCatalog();
    grantCapacityReporter(scope);
    recordCoordinationSlotObservation(scope, 2, "capacity-lost-response");
    grantReservationWorker(scope);

    String requestAttempt = createRequestAttempt(scope, "capacity-lost-response-request");
    preparePositiveAcceptance(scope, requestAttempt);
    transferProperties.setReservationPollInterval(java.time.Duration.ZERO);
    capacityPort.loseNextResponseAfterCommit();
    String accepted = acceptRequest(scope, requestAttempt, "capacity-lost-response-accept");
    UUID transferId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(accepted, "$.transferId"));
    assertThat(com.jayway.jsonpath.JsonPath.read(accepted, "$.status").toString())
        .isEqualTo("AWAITING_RESERVATION");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("UNAVAILABLE");
    assertThat(
            count(
                "SELECT COUNT(*) FROM capacity.coordination_reservation WHERE transfer_id = '"
                    + transferId
                    + "'::uuid"))
        .isEqualTo(1);

    TransferCapacityReservationRecovery restarted =
        beanFactory.createBean(TransferCapacityReservationRecovery.class);
    assertThat(restarted.reconcileDue()).isEqualTo(1);
    assertThat(capacityPort.idempotencyKeys())
        .containsExactly("transfer-capacity-" + transferId, "transfer-capacity-" + transferId);

    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("READY");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("RESERVED");
    assertThat(
            count(
                "SELECT COUNT(*) FROM capacity.coordination_reservation WHERE transfer_id = '"
                    + transferId
                    + "'::uuid"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM capacity.reservation_command_receipt WHERE operation_id = '"
                    + transferId
                    + "'::uuid"))
        .isEqualTo(1);
  }

  @Test
  void lostCapacityResponseRecoveredAfterExpiryDoesNotMarkTransferReady() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    ensureReservableCapacityCatalog();
    grantCapacityReporter(scope);
    recordCoordinationSlotObservation(scope, 2, "capacity-expired-after-commit");
    grantReservationWorker(scope);

    transferProperties.setReservationDuration(java.time.Duration.ofSeconds(5));
    transferProperties.setReservationPollInterval(java.time.Duration.ZERO);
    String requestAttempt = createRequestAttempt(scope, "capacity-expired-after-commit-request");
    preparePositiveAcceptance(scope, requestAttempt);
    capacityPort.loseNextResponseAfterCommit();
    String accepted = acceptRequest(scope, requestAttempt, "capacity-expired-after-commit-accept");
    UUID transferId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(accepted, "$.transferId"));

    assertThat(com.jayway.jsonpath.JsonPath.read(accepted, "$.status").toString())
        .isEqualTo("AWAITING_RESERVATION");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("UNAVAILABLE");
    assertThat(
            count(
                "SELECT COUNT(*) FROM capacity.coordination_reservation WHERE transfer_id = '"
                    + transferId
                    + "'::uuid"))
        .isEqualTo(1);

    Instant expiresAt =
        jdbc.queryForObject(
                "SELECT expires_at FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                java.sql.Timestamp.class,
                transferId)
            .toInstant();
    assertThat(expiresAt).isAfter(Instant.now());
    while (Instant.now().isBefore(expiresAt.plusMillis(250))) {
      Thread.sleep(20);
    }
    assertThat(
            count(
                "SELECT COUNT(*) FROM capacity.coordination_reservation "
                    + "WHERE transfer_id = '"
                    + transferId
                    + "'::uuid AND expires_at > CURRENT_TIMESTAMP"))
        .isZero();

    var intent =
        jdbc.queryForMap(
            "SELECT dimension_code, unit_code, quantity, interval_start, interval_end, expires_at "
                + "FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
            transferId);
    CapacityReservationRequest expiredReplayRequest =
        new CapacityReservationRequest(
            InternalId.parse(transferId.toString()),
            InternalId.parse(transferId.toString()),
            directoryReference("Facility", scope.destinationFacilityId()),
            (String) intent.get("dimension_code"),
            (String) intent.get("unit_code"),
            (java.math.BigDecimal) intent.get("quantity"),
            ((java.sql.Timestamp) intent.get("interval_start")).toInstant(),
            ((java.sql.Timestamp) intent.get("interval_end")).toInstant(),
            ((java.sql.Timestamp) intent.get("expires_at")).toInstant());
    CapacityReservationResult capacityReplay =
        capacityReservations.reserve(
            reservationContext(scope), expiredReplayRequest, "transfer-capacity-" + transferId);
    assertThat(capacityReplay.outcome()).isEqualTo(CapacityReservationResult.Outcome.RESERVED);
    assertThat(capacityReplay.replayed()).isTrue();

    TransferCapacityReservationRecovery restarted =
        beanFactory.createBean(TransferCapacityReservationRecovery.class);
    assertThat(restarted.reconcileDue()).isEqualTo(1);
    assertThat(capacityPort.idempotencyKeys()).containsExactly("transfer-capacity-" + transferId);

    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("AWAITING_RESERVATION");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("UNKNOWN");
    assertThat(
            jdbc.queryForObject(
                "SELECT reason_code FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("CAPACITY_RESERVATION_EXPIRED_BEFORE_CONFIRMATION");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_id FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                UUID.class,
                transferId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM capacity.reservation_operation WHERE operation_id = ?",
                String.class,
                transferId))
        .isEqualTo("RESERVED");
    assertThat(
            count(
                "SELECT COUNT(*) FROM capacity.coordination_reservation WHERE transfer_id = '"
                    + transferId
                    + "'::uuid AND expires_at > CURRENT_TIMESTAMP"))
        .isZero();
  }

  private void ensureReservableCapacityCatalog() throws Exception {
    Integer dimensions =
        count(
            "SELECT COUNT(*) FROM capacity.capacity_dimension WHERE dimension_code = 'TRANSFER_COORDINATION_SLOT'");
    if (dimensions == 0) {
      grantPlatformAuthority("synthetic-transfer-capacity-admin", "CapacityPolicyAdministration");
      mockMvc
          .perform(
              post("/api/v1/capacity/policies/dimensions")
                  .header("Authorization", "Bearer " + token("synthetic-transfer-capacity-admin"))
                  .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                  .header("Idempotency-Key", "transfer-capacity-dimension")
                  .contentType("application/json")
                  .content(
                      "{\"code\":\"TRANSFER_COORDINATION_SLOT\",\"expectedVersion\":0,"
                          + "\"displayName\":\"Transfer coordination slot\","
                          + "\"meaning\":\"Synthetic coordination hold capacity\","
                          + "\"valueKind\":\"QUANTITY\",\"unit\":\"slot\","
                          + "\"minimumValue\":0,\"maximumValue\":100,\"allowedStates\":[],"
                          + "\"usedForReadiness\":true,\"reservable\":true,"
                          + "\"reasonCode\":\"SYNTHETIC_SETUP\"}"))
          .andExpect(status().isCreated());
      mockMvc
          .perform(
              post(
                      "/api/v1/capacity/policies/dimensions/{dimension}/policy",
                      "TRANSFER_COORDINATION_SLOT")
                  .header("Authorization", "Bearer " + token("synthetic-transfer-capacity-admin"))
                  .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                  .header("Idempotency-Key", "transfer-capacity-policy")
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
  }

  private void grantPlatformAuthority(String subject, String authority) {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, grant_status, created_at) VALUES (gen_random_uuid(), 'HUMAN', ?, "
            + "'subject', ?, ?, 'PLATFORM', 'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        subject,
        authority);
  }

  private void grantCapacityReporter(Scope scope) {
    grantFacilityAuthority("synthetic-transfer-capacity-reporter", "CapacityReporting", scope);
  }

  private void grantReservationWorker(Scope scope) {
    grantTransferReservationWorker(scope);
    grantFacilityServiceAuthority("CapacityReservation", scope);
  }

  private void grantTransferReservationWorker(Scope scope) {
    grantFacilityServiceAuthority("TransferReservationReconciliation", scope);
  }

  private void grantFacilityAuthority(String subject, String authority, Scope scope) {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, organization_issuer, organization_identifier_type, organization_identifier, "
            + "facility_issuer, facility_identifier_type, facility_identifier, grant_status, created_at) "
            + "VALUES (gen_random_uuid(), 'HUMAN', ?, 'subject', ?, ?, 'FACILITY', ?, "
            + "'Organization', ?, ?, 'Facility', ?, 'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        subject,
        authority,
        DIRECTORY_ISSUER,
        scope.originOrganizationId(),
        DIRECTORY_ISSUER,
        scope.destinationFacilityId());
  }

  private void grantFacilityServiceAuthority(String authority, Scope scope) {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, organization_issuer, organization_identifier_type, organization_identifier, "
            + "facility_issuer, facility_identifier_type, facility_identifier, grant_status, created_at) "
            + "VALUES (gen_random_uuid(), 'SERVICE', ?, 'client_id', ?, ?, 'FACILITY', ?, "
            + "'Organization', ?, ?, 'Facility', ?, 'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        "ayuvahak-transfer-reservation-worker",
        authority,
        DIRECTORY_ISSUER,
        scope.originOrganizationId(),
        DIRECTORY_ISSUER,
        scope.destinationFacilityId());
  }

  private void recordCoordinationSlotObservation(Scope scope, int quantity, String sourceId)
      throws Exception {
    recordCoordinationSlotObservation(scope, quantity, sourceId, Instant.now().minusSeconds(1));
  }

  private void recordCoordinationSlotObservation(
      Scope scope, int quantity, String sourceId, Instant observedAt) throws Exception {
    recordCoordinationSlotObservation(scope, quantity, sourceId, observedAt, null);
  }

  private void recordCoordinationSlotObservation(
      Scope scope, int quantity, String sourceId, Instant observedAt, Instant validUntil)
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/capacity/observations")
                .header("Authorization", "Bearer " + token("synthetic-transfer-capacity-reporter"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.destinationFacilityId())
                .header("Idempotency-Key", sourceId)
                .contentType("application/json")
                .content(
                    "{\"dimensionCode\":\"TRANSFER_COORDINATION_SLOT\","
                        + "\"sourceCode\":\"manual\",\"sourceIssuer\":\"urn:synthetic:capacity-source\","
                        + "\"sourceObservationId\":\""
                        + sourceId
                        + "\","
                        + "\"observationKind\":\"VALUE\",\"valueKind\":\"QUANTITY\","
                        + "\"quantity\":"
                        + quantity
                        + ",\"state\":null,\"unit\":\"slot\","
                        + "\"observedAt\":\""
                        + observedAt
                        + "\","
                        + "\"validUntil\":"
                        + (validUntil == null ? "null" : "\"" + validUntil + "\"")
                        + ",\"reportingMethod\":\"MANUAL_ENTRY\","
                        + "\"reasonCode\":\"CAPACITY_REPORT\"}"))
        .andExpect(status().isCreated());
  }

  private String acceptRequest(Scope scope, String requestAttemptId, String idempotencyKey)
      throws Exception {
    return receivingCommand(
            scope,
            requestAttemptId,
            "acceptance",
            "synthetic-receiver",
            idempotencyKey,
            "{\"expectedVersion\":3,\"occurredAt\":\"2026-10-10T10:50:00Z\"}")
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String createReadyTransfer(Scope scope, String fixtureName) throws Exception {
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    ensureReservableCapacityCatalog();
    grantCapacityReporter(scope);
    recordCoordinationSlotObservation(scope, 1, fixtureName + "-capacity");
    grantReservationWorker(scope);
    String requestAttempt = createRequestAttempt(scope, fixtureName + "-request");
    preparePositiveAcceptance(scope, requestAttempt);
    String accepted = acceptRequest(scope, requestAttempt, fixtureName + "-acceptance");
    assertThat(com.jayway.jsonpath.JsonPath.read(accepted, "$.status").toString())
        .isEqualTo("READY");
    return com.jayway.jsonpath.JsonPath.read(accepted, "$.transferId").toString();
  }

  private org.springframework.test.web.servlet.ResultActions progressCommand(
      Scope scope,
      String transferId,
      String command,
      String subject,
      String idempotencyKey,
      String body)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/transfers/{transferId}/{command}", transferId, command)
            .header("Authorization", "Bearer " + token(subject))
            .header("X-AyuVahak-Execution-Scope", "FACILITY")
            .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
            .header("X-AyuVahak-Facility-Id", scope.destinationFacilityId())
            .header("Idempotency-Key", idempotencyKey)
            .contentType("application/json")
            .content(body));
  }

  private org.ayuvahak.platform.executioncontext.ExecutionContext reservationContext(Scope scope) {
    return executionContexts.forBackgroundWork(
        ActorReference.service(new IssuerId(ISSUER), "ayuvahak-transfer-reservation-worker"),
        RequestedExecutionScope.facility(
            directoryReference("Organization", scope.originOrganizationId()),
            directoryReference("Facility", scope.destinationFacilityId())),
        CorrelationId.generate(),
        java.util.Optional.empty());
  }

  private CapacityReservationResult reserve(
      org.ayuvahak.platform.executioncontext.ExecutionContext context,
      InternalId transferId,
      String facilityId,
      Instant intervalStart,
      Instant intervalEnd,
      CountDownLatch start)
      throws Exception {
    start.await();
    return capacityReservations.reserve(
        context,
        reservationRequest(transferId, intervalStart, intervalEnd, facilityId),
        "transfer-capacity-" + transferId);
  }

  private static CapacityReservationRequest reservationRequest(
      InternalId transferId, Instant intervalStart, Instant intervalEnd, String facilityId) {
    return new CapacityReservationRequest(
        transferId,
        transferId,
        directoryReference("Facility", facilityId),
        "TRANSFER_COORDINATION_SLOT",
        "slot",
        java.math.BigDecimal.ONE,
        intervalStart,
        intervalEnd,
        intervalEnd);
  }

  @Test
  void missingOrNegativeLatestAssessmentBlocksAcceptanceButExplicitRejectionIsRecorded()
      throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String requestAttemptId =
        com.jayway.jsonpath.JsonPath.read(
            createRequest(scope, "assessment-rejection-request")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.requestAttemptId");
    grantReceivingAuthority(scope, "synthetic-clinical-reviewer", "ClinicalSuitabilityAuthority");
    grantReceivingAuthority(
        scope, "synthetic-readiness-coordinator", "OperationalReadinessAuthority");
    grantReceivingAuthority(scope, "synthetic-receiving-authority", "AcceptanceAuthority");

    receivingCommand(
            scope,
            requestAttemptId,
            "clinical-suitability-assessments",
            "synthetic-clinical-reviewer",
            "clinical-positive-02",
            "{\"expectedVersion\":1,\"outcome\":\"SUPPORTS\","
                + "\"reasonCode\":\"CLINICAL_REVIEW_COMPLETE\","
                + "\"occurredAt\":\"2026-10-10T10:10:00Z\"}")
        .andExpect(status().isCreated());

    receivingCommand(
            scope,
            requestAttemptId,
            "acceptance",
            "synthetic-receiving-authority",
            "missing-readiness-acceptance",
            "{\"expectedVersion\":2,\"occurredAt\":\"2026-10-10T10:11:00Z\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSFER_ASSESSMENT_REQUIRED"));

    receivingCommand(
            scope,
            requestAttemptId,
            "operational-readiness-assessments",
            "synthetic-readiness-coordinator",
            "readiness-negative-02",
            "{\"expectedVersion\":2,\"outcome\":\"DOES_NOT_SUPPORT\","
                + "\"reasonCode\":\"READINESS_NOT_CONFIRMED\","
                + "\"occurredAt\":\"2026-10-10T10:12:00Z\"}")
        .andExpect(status().isCreated());

    receivingCommand(
            scope,
            requestAttemptId,
            "acceptance",
            "synthetic-receiving-authority",
            "negative-readiness-acceptance",
            "{\"expectedVersion\":3,\"occurredAt\":\"2026-10-10T10:13:00Z\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSFER_ASSESSMENT_NOT_POSITIVE"));

    String rejectionBody =
        "{\"expectedVersion\":3,\"reasonCode\":\"DESTINATION_CANNOT_ACCEPT\","
            + "\"occurredAt\":\"2026-10-10T10:14:00Z\"}";
    receivingCommand(
            scope,
            requestAttemptId,
            "rejection",
            "synthetic-receiving-authority",
            "explicit-rejection-02",
            rejectionBody)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.decisionKind").value("REJECTED"))
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.reasonCode").value("DESTINATION_CANNOT_ACCEPT"));
    receivingCommand(
            scope,
            requestAttemptId,
            "rejection",
            "synthetic-receiving-authority",
            "explicit-rejection-02",
            rejectionBody)
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"));

    receivingCommand(
            scope,
            requestAttemptId,
            "acceptance",
            "synthetic-receiving-authority",
            "accept-after-rejection",
            "{\"expectedVersion\":4,\"occurredAt\":\"2026-10-10T10:15:00Z\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSFER_REQUEST_NOT_PENDING"));

    assertThat(
            jdbc.queryForObject(
                "SELECT request_status FROM transfer.request_attempt WHERE request_attempt_id = ?",
                String.class,
                UUID.fromString(requestAttemptId)))
        .isEqualTo("REJECTED");
    assertThat(
            count(
                "SELECT COUNT(*) FROM transfer.request_decision WHERE decision_kind = 'REJECTED'"))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM transfer.transfer_record")).isZero();
    assertThat(count("SELECT COUNT(*) FROM transfer.receiving_assessment")).isEqualTo(2);
    assertThat(count("SELECT COUNT(*) FROM platform_eventing.internal_event")).isZero();
  }

  @Test
  void expiredPendingRequestCannotBeAccepted() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String requestAttemptId =
        com.jayway.jsonpath.JsonPath.read(
            createRequest(scope, "expired-receiving-request")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.requestAttemptId");
    grantReceivingAuthority(scope, "synthetic-receiving-authority", "AcceptanceAuthority");
    jdbc.update(
        "UPDATE transfer.request_attempt SET requested_at = ?, expires_at = ? "
            + "WHERE request_attempt_id = ?",
        java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(120)),
        java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)),
        UUID.fromString(requestAttemptId));

    receivingCommand(
            scope,
            requestAttemptId,
            "acceptance",
            "synthetic-receiving-authority",
            "expired-request-acceptance",
            "{\"expectedVersion\":1,\"occurredAt\":\"2026-10-10T10:40:00Z\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSFER_REQUEST_EXPIRED"));

    assertThat(
            jdbc.queryForObject(
                "SELECT request_status FROM transfer.request_attempt WHERE request_attempt_id = ?",
                String.class,
                UUID.fromString(requestAttemptId)))
        .isEqualTo("PENDING");
    assertThat(count("SELECT COUNT(*) FROM transfer.request_decision")).isZero();
    assertThat(count("SELECT COUNT(*) FROM transfer.transfer_record")).isZero();
  }

  @Test
  void authorizedSenderCanWithdrawPendingRequest() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    int existingEvents = count("SELECT COUNT(*) FROM platform_eventing.internal_event");
    String requestAttemptId =
        com.jayway.jsonpath.JsonPath.read(
            createRequest(scope, "withdrawal-request")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.requestAttemptId");

    withdrawalCommand(
            scope,
            requestAttemptId,
            SENDER,
            "withdrawal-01",
            "{\"expectedVersion\":1,\"reasonCode\":\"SENDER_WITHDREW\","
                + "\"occurredAt\":\"2020-10-10T10:45:00Z\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("WITHDRAWN"));
    Instant withdrawalOccurredAt = Instant.parse("2020-10-10T10:45:00Z");
    TerminalTimes withdrawalTimes = terminalTimes(requestAttemptId);
    assertThat(withdrawalTimes.occurredAt()).isEqualTo(withdrawalOccurredAt);
    assertThat(withdrawalTimes.recordedAt()).isAfter(withdrawalOccurredAt);
    withdrawalCommand(
            scope,
            requestAttemptId,
            SENDER,
            "withdrawal-01",
            "{\"expectedVersion\":1,\"reasonCode\":\"SENDER_WITHDREW\","
                + "\"occurredAt\":\"2020-10-10T10:45:00Z\"}")
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"));

    assertThat(requestStatus(requestAttemptId)).isEqualTo("WITHDRAWN");
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'TRANSFER_REQUEST_WITHDRAW'"))
        .isEqualTo(1);
    AuditTimes withdrawalAudit = auditTimes("TRANSFER_REQUEST_WITHDRAW");
    assertThat(withdrawalAudit.occurredAt()).isAfter(withdrawalOccurredAt);
    assertThat(withdrawalAudit.recordedAt()).isAfter(withdrawalAudit.occurredAt());
    assertThat(withdrawalAudit.actorReference()).isEqualTo(SENDER);
    assertThat(count("SELECT COUNT(*) FROM platform_eventing.internal_event"))
        .isEqualTo(existingEvents);
  }

  @Test
  void duePendingRequestExpiresUsingTransferServiceAuthority() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String requestAttemptId =
        com.jayway.jsonpath.JsonPath.read(
            createRequest(scope, "expiry-request")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.requestAttemptId");
    grantExpiryWorker(scope);
    jdbc.update(
        "UPDATE transfer.request_attempt SET requested_at = ?, expires_at = ? "
            + "WHERE request_attempt_id = ?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(120)),
        java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
        UUID.fromString(requestAttemptId));
    Instant effectiveExpiryAt =
        jdbc.queryForObject(
                "SELECT expires_at FROM transfer.request_attempt WHERE request_attempt_id = ?",
                java.sql.Timestamp.class,
                UUID.fromString(requestAttemptId))
            .toInstant();

    assertThat(expirySweep.expireDueRequests()).isEqualTo(1);

    assertThat(requestStatus(requestAttemptId)).isEqualTo("EXPIRED");
    TerminalTimes expiryTimes = terminalTimes(requestAttemptId);
    assertThat(expiryTimes.occurredAt()).isEqualTo(effectiveExpiryAt);
    assertThat(expiryTimes.recordedAt()).isAfter(expiryTimes.occurredAt());
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'TRANSFER_REQUEST_EXPIRE'"))
        .isEqualTo(1);
    AuditTimes expiryAudit = auditTimes("TRANSFER_REQUEST_EXPIRE");
    assertThat(expiryAudit.occurredAt()).isAfter(effectiveExpiryAt);
    assertThat(expiryAudit.recordedAt()).isAfter(expiryAudit.occurredAt());
    assertThat(expiryAudit.actorReference()).isEqualTo("ayuvahak-transfer-expiry-worker");
    assertThat(count("SELECT COUNT(*) FROM platform_eventing.internal_event")).isZero();
  }

  @Test
  void missingExpiryGrantLeavesDueRequestPendingAndRecordsDenial() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String requestAttemptId = createRequestAttempt(scope, "expiry-denied-request");
    jdbc.update(
        "UPDATE transfer.request_attempt SET requested_at = ?, expires_at = ? "
            + "WHERE request_attempt_id = ?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(120)),
        java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
        UUID.fromString(requestAttemptId));

    assertThat(expirySweep.expireDueRequests()).isZero();

    assertThat(requestStatus(requestAttemptId)).isEqualTo("PENDING");
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'TRANSFER_REQUEST_EXPIRE_DENIED'"))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM transfer.command_receipt")).isEqualTo(1);
  }

  @Test
  void unauthorizedSenderCannotWithdrawAndDenialIsAudited() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String requestAttemptId = createRequestAttempt(scope, "unauthorized-withdrawal-request");

    withdrawalCommand(
            scope,
            requestAttemptId,
            "synthetic-unauthorized-sender",
            "unauthorized-withdrawal",
            "{\"expectedVersion\":1,\"reasonCode\":\"SENDER_WITHDREW\","
                + "\"occurredAt\":\"2026-10-10T10:46:00Z\"}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));

    assertThat(requestStatus(requestAttemptId)).isEqualTo("PENDING");
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'TRANSFER_REQUEST_WITHDRAW_DENIED'"))
        .isEqualTo(1);
  }

  @Test
  void requiredWithdrawalAuditFailureRollsBackStateAndReceiptThenRetries() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String requestAttemptId = createRequestAttempt(scope, "withdraw-audit-request");
    String body =
        "{\"expectedVersion\":1,\"reasonCode\":\"SENDER_WITHDREW\","
            + "\"occurredAt\":\"2026-10-10T10:47:00Z\"}";
    jdbc.execute(
        "CREATE FUNCTION transfer.reject_test_withdrawal_audit() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN "
            + "IF NEW.action_code = 'TRANSFER_REQUEST_WITHDRAW' THEN "
            + "RAISE EXCEPTION 'synthetic audit failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_test_withdrawal_audit BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION transfer.reject_test_withdrawal_audit()");
    try {
      withdrawalCommand(scope, requestAttemptId, SENDER, "withdraw-audit-retry", body)
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));
      assertThat(requestStatus(requestAttemptId)).isEqualTo("PENDING");
      assertThat(count("SELECT COUNT(*) FROM transfer.command_receipt")).isEqualTo(1);
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS reject_test_withdrawal_audit ON audit.audit_record");
      jdbc.execute("DROP FUNCTION IF EXISTS transfer.reject_test_withdrawal_audit()");
    }

    withdrawalCommand(scope, requestAttemptId, SENDER, "withdraw-audit-retry", body)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("WITHDRAWN"));
    assertThat(count("SELECT COUNT(*) FROM transfer.command_receipt")).isEqualTo(2);
    assertThat(requestStatus(requestAttemptId)).isEqualTo("WITHDRAWN");
  }

  @Test
  void acceptanceAndSenderWithdrawalRaceHaveOneTerminalWinnerAndReplay() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String requestAttemptId = createRequestAttempt(scope, "accept-withdraw-race");
    preparePositiveAcceptance(scope, requestAttemptId);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (Connection barrierConnection = dataSource.getConnection()) {
      barrierConnection.setAutoCommit(false);
      try (var lock =
          barrierConnection.prepareStatement(
              "SELECT request_attempt_id FROM transfer.request_attempt "
                  + "WHERE request_attempt_id = ? FOR UPDATE")) {
        lock.setObject(1, UUID.fromString(requestAttemptId));
        try (var ignored = lock.executeQuery()) {
          assertThat(ignored.next()).isTrue();
        }
      }
      Future<MvcResult> acceptance =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return receivingCommand(
                        scope,
                        requestAttemptId,
                        "acceptance",
                        "synthetic-receiver",
                        "race-acceptance",
                        "{\"expectedVersion\":3,\"occurredAt\":\"2026-10-10T10:50:00Z\"}")
                    .andReturn();
              });
      Future<MvcResult> withdrawal =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return withdrawalCommand(
                        scope,
                        requestAttemptId,
                        SENDER,
                        "race-withdrawal",
                        "{\"expectedVersion\":3,\"reasonCode\":\"SENDER_WITHDREW\","
                            + "\"occurredAt\":\"2026-10-10T10:50:00Z\"}")
                    .andReturn();
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      awaitPostgresCasWaiters(2);
      barrierConnection.commit();
      MvcResult accepted = acceptance.get(15, TimeUnit.SECONDS);
      MvcResult withdrawn = withdrawal.get(15, TimeUnit.SECONDS);

      assertThat(
              java.util.List.of(
                  accepted.getResponse().getStatus(), withdrawn.getResponse().getStatus()))
          .containsExactlyInAnyOrder(201, 409);
      String conflictResponse =
          accepted.getResponse().getStatus() == 409
              ? accepted.getResponse().getContentAsString()
              : withdrawn.getResponse().getContentAsString();
      assertThat(com.jayway.jsonpath.JsonPath.read(conflictResponse, "$.code").toString())
          .isIn("TRANSFER_STATE_CONFLICT", "TRANSFER_REQUEST_NOT_PENDING");
      String winner = requestStatus(requestAttemptId);
      assertThat(winner).isIn("ACCEPTED", "WITHDRAWN");
      assertThat(
              count(
                  "SELECT COUNT(*) FROM audit.audit_record WHERE action_code IN "
                      + "('TRANSFER_ACCEPT', 'TRANSFER_REQUEST_WITHDRAW')"))
          .isEqualTo(1);

      if ("ACCEPTED".equals(winner)) {
        assertThat(
                receivingCommand(
                        scope,
                        requestAttemptId,
                        "acceptance",
                        "synthetic-receiver",
                        "race-acceptance",
                        "{\"expectedVersion\":3,\"occurredAt\":\"2026-10-10T10:50:00Z\"}")
                    .andReturn()
                    .getResponse()
                    .getHeader("Idempotency-Replayed"))
            .isEqualTo("true");
        assertThat(count("SELECT COUNT(*) FROM transfer.transfer_record")).isEqualTo(1);
      } else {
        assertThat(
                withdrawalCommand(
                        scope,
                        requestAttemptId,
                        SENDER,
                        "race-withdrawal",
                        "{\"expectedVersion\":3,\"reasonCode\":\"SENDER_WITHDREW\","
                            + "\"occurredAt\":\"2026-10-10T10:50:00Z\"}")
                    .andReturn()
                    .getResponse()
                    .getHeader("Idempotency-Replayed"))
            .isEqualTo("true");
        assertThat(count("SELECT COUNT(*) FROM transfer.transfer_record")).isZero();
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void acceptanceAlreadyLinearizedBeforeDeadlineWinsAgainstConcurrentExpiry() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String requestAttemptId = createRequestAttempt(scope, "accept-expire-race");
    preparePositiveAcceptance(scope, requestAttemptId);
    grantExpiryWorker(scope);

    Instant deadline = Instant.now().plusSeconds(2);
    jdbc.update(
        "UPDATE transfer.request_attempt SET expires_at = ? WHERE request_attempt_id = ?",
        java.sql.Timestamp.from(deadline),
        UUID.fromString(requestAttemptId));
    jdbc.execute(
        "CREATE FUNCTION transfer.delay_test_acceptance() RETURNS trigger LANGUAGE plpgsql AS "
            + "$$ BEGIN IF NEW.request_status = 'ACCEPTED' THEN PERFORM pg_sleep(4); END IF; "
            + "RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER delay_test_acceptance BEFORE UPDATE ON transfer.request_attempt "
            + "FOR EACH ROW EXECUTE FUNCTION transfer.delay_test_acceptance()");

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<MvcResult> acceptance =
          executor.submit(
              () ->
                  receivingCommand(
                          scope,
                          requestAttemptId,
                          "acceptance",
                          "synthetic-receiver",
                          "race-expiry-acceptance",
                          "{\"expectedVersion\":3,\"occurredAt\":\"2026-10-10T10:55:00Z\"}")
                      .andReturn());
      awaitPostgresTriggerSleep();
      while (Instant.now().isBefore(deadline)) {
        Thread.sleep(20);
      }
      assertThat(expirySweep.expireDueRequests()).isZero();
      MvcResult accepted = acceptance.get(10, TimeUnit.SECONDS);
      assertThat(accepted.getResponse().getStatus()).isEqualTo(201);
      assertThat(requestStatus(requestAttemptId)).isEqualTo("ACCEPTED");
      assertThat(count("SELECT COUNT(*) FROM transfer.request_decision")).isEqualTo(1);
      assertThat(
              count(
                  "SELECT COUNT(*) FROM audit.audit_record WHERE action_code IN "
                      + "('TRANSFER_ACCEPT', 'TRANSFER_REQUEST_EXPIRE')"))
          .isEqualTo(1);
      receivingCommand(
              scope,
              requestAttemptId,
              "acceptance",
              "synthetic-receiver",
              "race-expiry-acceptance",
              "{\"expectedVersion\":3,\"occurredAt\":\"2026-10-10T10:55:00Z\"}")
          .andExpect(status().isCreated())
          .andExpect(header().string("Idempotency-Replayed", "true"));
    } finally {
      executor.shutdownNow();
      jdbc.execute("DROP TRIGGER IF EXISTS delay_test_acceptance ON transfer.request_attempt");
      jdbc.execute("DROP FUNCTION IF EXISTS transfer.delay_test_acceptance()");
    }
  }

  @Test
  void receivingAuthoritiesAreIndependentAndBoundToTheAttemptFacility() throws Exception {
    Scope scope = createOriginAndDestination();
    Scope otherFacility = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String requestAttemptId =
        com.jayway.jsonpath.JsonPath.read(
            createRequest(scope, "receiving-scope-request")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.requestAttemptId");
    grantReceivingAuthority(
        otherFacility, "synthetic-clinical-reviewer", "ClinicalSuitabilityAuthority");

    receivingCommand(
            otherFacility,
            requestAttemptId,
            "clinical-suitability-assessments",
            "synthetic-clinical-reviewer",
            "wrong-facility-assessment",
            "{\"expectedVersion\":1,\"outcome\":\"SUPPORTS\","
                + "\"reasonCode\":\"CLINICAL_REVIEW_COMPLETE\","
                + "\"occurredAt\":\"2026-10-10T10:20:00Z\"}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TRANSFER_SCOPE_MISMATCH"));

    grantReceivingAuthority(scope, "synthetic-clinical-reviewer", "ClinicalSuitabilityAuthority");
    receivingCommand(
            scope,
            requestAttemptId,
            "operational-readiness-assessments",
            "synthetic-clinical-reviewer",
            "wrong-functional-authority",
            "{\"expectedVersion\":1,\"outcome\":\"SUPPORTS\","
                + "\"reasonCode\":\"READINESS_CONFIRMED\","
                + "\"occurredAt\":\"2026-10-10T10:21:00Z\"}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));

    assertThat(count("SELECT COUNT(*) FROM transfer.receiving_assessment")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM transfer.request_attempt WHERE request_attempt_id = ?",
                Long.class,
                UUID.fromString(requestAttemptId)))
        .isEqualTo(1L);
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'TRANSFER_SCOPE_MISMATCH'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'TRANSFER_ASSESSMENT_DENIED'"))
        .isEqualTo(1);
  }

  @Test
  void requiredAuditFailureRollsBackAssessmentAndReceiptThenAllowsExactRetry() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String requestAttemptId =
        com.jayway.jsonpath.JsonPath.read(
            createRequest(scope, "assessment-audit-request")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.requestAttemptId");
    grantReceivingAuthority(scope, "synthetic-clinical-reviewer", "ClinicalSuitabilityAuthority");
    String body =
        "{\"expectedVersion\":1,\"outcome\":\"SUPPORTS\","
            + "\"reasonCode\":\"CLINICAL_REVIEW_COMPLETE\","
            + "\"occurredAt\":\"2026-10-10T10:30:00Z\"}";
    jdbc.execute(
        "CREATE FUNCTION transfer.reject_test_audit() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN "
            + "IF NEW.action_code = 'TRANSFER_CLINICAL_SUITABILITY_RECORD' THEN "
            + "RAISE EXCEPTION 'synthetic audit failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_test_audit BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION transfer.reject_test_audit()");
    try {
      receivingCommand(
              scope,
              requestAttemptId,
              "clinical-suitability-assessments",
              "synthetic-clinical-reviewer",
              "assessment-audit-retry",
              body)
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));
      assertThat(count("SELECT COUNT(*) FROM transfer.receiving_assessment")).isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT version FROM transfer.request_attempt WHERE request_attempt_id = ?",
                  Long.class,
                  UUID.fromString(requestAttemptId)))
          .isEqualTo(1L);
      assertThat(count("SELECT COUNT(*) FROM transfer.command_receipt")).isEqualTo(1);
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS reject_test_audit ON audit.audit_record");
      jdbc.execute("DROP FUNCTION IF EXISTS transfer.reject_test_audit()");
    }

    receivingCommand(
            scope,
            requestAttemptId,
            "clinical-suitability-assessments",
            "synthetic-clinical-reviewer",
            "assessment-audit-retry",
            body)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.requestVersion").value(2));
    assertThat(count("SELECT COUNT(*) FROM transfer.receiving_assessment")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM transfer.command_receipt")).isEqualTo(2);
    assertThat(count("SELECT COUNT(*) FROM audit.audit_record WHERE action_code LIKE 'TRANSFER_%'"))
        .isEqualTo(2);
  }

  private org.springframework.test.web.servlet.ResultActions receivingCommand(
      Scope scope,
      String requestAttemptId,
      String command,
      String subject,
      String idempotencyKey,
      String body)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/transfer-request-attempts/{attemptId}/{command}", requestAttemptId, command)
            .header("Authorization", "Bearer " + token(subject))
            .header("X-AyuVahak-Execution-Scope", "FACILITY")
            .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
            .header("X-AyuVahak-Facility-Id", scope.destinationFacilityId())
            .header("Idempotency-Key", idempotencyKey)
            .contentType("application/json")
            .content(body));
  }

  private org.springframework.test.web.servlet.ResultActions withdrawalCommand(
      Scope scope, String requestAttemptId, String subject, String idempotencyKey, String body)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/transfer-request-attempts/{attemptId}/withdrawal", requestAttemptId)
            .header("Authorization", "Bearer " + token(subject))
            .header("X-AyuVahak-Execution-Scope", "FACILITY")
            .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
            .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
            .header("Idempotency-Key", idempotencyKey)
            .contentType("application/json")
            .content(body));
  }

  private String requestStatus(String requestAttemptId) {
    return jdbc.queryForObject(
        "SELECT request_status FROM transfer.request_attempt WHERE request_attempt_id = ?",
        String.class,
        UUID.fromString(requestAttemptId));
  }

  private TerminalTimes terminalTimes(String requestAttemptId) {
    return jdbc.queryForObject(
        "SELECT terminal_occurred_at, terminal_recorded_at FROM transfer.request_attempt "
            + "WHERE request_attempt_id = ?",
        (result, row) ->
            new TerminalTimes(
                result.getTimestamp("terminal_occurred_at").toInstant(),
                result.getTimestamp("terminal_recorded_at").toInstant()),
        UUID.fromString(requestAttemptId));
  }

  private AuditTimes auditTimes(String actionCode) {
    return jdbc.queryForObject(
        "SELECT occurred_at, recorded_at, actor_reference FROM audit.audit_record "
            + "WHERE action_code = ?",
        (result, row) ->
            new AuditTimes(
                result.getTimestamp("occurred_at").toInstant(),
                result.getTimestamp("recorded_at").toInstant(),
                result.getString("actor_reference")),
        actionCode);
  }

  private record TerminalTimes(Instant occurredAt, Instant recordedAt) {}

  private record AuditTimes(Instant occurredAt, Instant recordedAt, String actorReference) {}

  private String createRequestAttempt(Scope scope, String idempotencyKey) throws Exception {
    return com.jayway.jsonpath.JsonPath.read(
        createRequest(scope, idempotencyKey)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        "$.requestAttemptId");
  }

  private void preparePositiveAcceptance(Scope scope, String requestAttemptId) throws Exception {
    grantReceivingAuthority(scope, "synthetic-receiver", "ClinicalSuitabilityAuthority");
    grantReceivingAuthority(scope, "synthetic-receiver", "OperationalReadinessAuthority");
    grantReceivingAuthority(scope, "synthetic-receiver", "AcceptanceAuthority");
    receivingCommand(
            scope,
            requestAttemptId,
            "clinical-suitability-assessments",
            "synthetic-receiver",
            "race-clinical-assessment",
            "{\"expectedVersion\":1,\"outcome\":\"SUPPORTS\","
                + "\"reasonCode\":\"CLINICAL_REVIEW_COMPLETE\","
                + "\"occurredAt\":\"2026-10-10T10:48:00Z\"}")
        .andExpect(status().isCreated());
    receivingCommand(
            scope,
            requestAttemptId,
            "operational-readiness-assessments",
            "synthetic-receiver",
            "race-readiness-assessment",
            "{\"expectedVersion\":2,\"outcome\":\"SUPPORTS\","
                + "\"reasonCode\":\"READINESS_CONFIRMED\","
                + "\"occurredAt\":\"2026-10-10T10:49:00Z\"}")
        .andExpect(status().isCreated());
  }

  private void awaitPostgresTriggerSleep() throws Exception {
    Instant timeout = Instant.now().plusSeconds(3);
    while (Instant.now().isBefore(timeout)) {
      if (count(
              "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() "
                  + "AND wait_event = 'PgSleep'")
          > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Acceptance did not reach the PostgreSQL concurrency barrier.");
  }

  private void awaitPostgresCasWaiters(int expectedWaiters) throws Exception {
    Instant timeout = Instant.now().plusSeconds(10);
    while (Instant.now().isBefore(timeout)) {
      if (count(
              "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() "
                  + "AND wait_event_type = 'Lock' "
                  + "AND query ILIKE 'UPDATE transfer.request_attempt SET request_status = %'")
          >= expectedWaiters) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError(
        "Both Transfer commands did not reach the request state compare-and-set barrier.");
  }

  private org.springframework.test.web.servlet.ResultActions createRequest(
      Scope scope, String idempotencyKey) throws Exception {
    return createRequest(scope, idempotencyKey, SENDER);
  }

  private org.springframework.test.web.servlet.ResultActions createRequest(
      Scope scope, String idempotencyKey, String subject) throws Exception {
    return mockMvc.perform(
        post("/api/v1/transfer-journeys")
            .header("Authorization", "Bearer " + token(subject))
            .header("X-AyuVahak-Execution-Scope", "FACILITY")
            .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
            .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
            .header("Idempotency-Key", idempotencyKey)
            .contentType("application/json")
            .content(
                "{\"referralId\":\""
                    + scope.referralId()
                    + "\",\"referralRevision\":4,\"recommendationId\":\""
                    + scope.recommendationId()
                    + "\",\"candidateFacilityId\":\""
                    + scope.destinationFacilityId()
                    + "\"}"));
  }

  private Scope createOriginAndDestination() {
    InternalId originOrganization = InternalId.generate();
    InternalId originFacility = InternalId.generate();
    InternalId destinationFacility = InternalId.generate();
    Instant now = Instant.now();
    ActorReference actor =
        ActorReference.human(new IssuerId("urn:synthetic:fixture"), "directory-fixture");
    directory.insertOrganization(
        new Organization(
            originOrganization,
            "Synthetic origin",
            java.util.Optional.empty(),
            new DirectorySource(
                new ExternalReference(
                    new IssuerId("urn:synthetic:directory-source"),
                    "Organization",
                    "origin-" + UUID.randomUUID())),
            new DirectoryProvenance("MANUAL_REVIEW", now, actor, now, "TEST_FIXTURE"),
            DirectoryLifecycleStatus.ACTIVE,
            1));
    directory.insertFacility(
        new Facility(
            originFacility,
            originOrganization,
            "Synthetic origin facility",
            new DirectorySource(
                new ExternalReference(
                    new IssuerId("urn:synthetic:directory-source"),
                    "Facility",
                    "origin-facility-" + UUID.randomUUID())),
            new DirectoryProvenance("MANUAL_REVIEW", now, actor, now, "TEST_FIXTURE"),
            DirectoryLifecycleStatus.ACTIVE,
            1));
    directory.insertFacility(
        new Facility(
            destinationFacility,
            originOrganization,
            "Synthetic destination facility",
            new DirectorySource(
                new ExternalReference(
                    new IssuerId("urn:synthetic:directory-source"),
                    "Facility",
                    "destination-facility-" + UUID.randomUUID())),
            new DirectoryProvenance("MANUAL_REVIEW", now, actor, now, "TEST_FIXTURE"),
            DirectoryLifecycleStatus.ACTIVE,
            1));
    return new Scope(
        originOrganization.toString(),
        originFacility.toString(),
        destinationFacility.toString(),
        InternalId.generate().toString(),
        InternalId.generate().toString());
  }

  private void grantSender(Scope scope) {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, organization_issuer, organization_identifier_type, "
            + "organization_identifier, facility_issuer, facility_identifier_type, "
            + "facility_identifier, grant_status, created_at) VALUES (gen_random_uuid(), "
            + "'HUMAN', ?, 'subject', ?, 'TransferRequest', 'FACILITY', ?, 'Organization', ?, "
            + "?, 'Facility', ?, 'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        SENDER,
        DIRECTORY_ISSUER,
        scope.originOrganizationId(),
        DIRECTORY_ISSUER,
        scope.originFacilityId());
  }

  private void grantReceivingAuthority(Scope scope, String subject, String authority) {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, organization_issuer, organization_identifier_type, "
            + "organization_identifier, facility_issuer, facility_identifier_type, "
            + "facility_identifier, grant_status, created_at) VALUES (gen_random_uuid(), "
            + "'HUMAN', ?, 'subject', ?, ?, 'FACILITY', ?, 'Organization', ?, "
            + "?, 'Facility', ?, 'ACTIVE', CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING",
        ISSUER,
        subject,
        authority,
        DIRECTORY_ISSUER,
        scope.originOrganizationId(),
        DIRECTORY_ISSUER,
        scope.destinationFacilityId());
  }

  private void grantExpiryWorker(Scope scope) {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, organization_issuer, organization_identifier_type, "
            + "organization_identifier, grant_status, created_at) VALUES (gen_random_uuid(), "
            + "'SERVICE', ?, 'client_id', 'ayuvahak-transfer-expiry-worker', "
            + "'TransferRequestExpiry', 'ORGANIZATION', ?, 'Organization', ?, 'ACTIVE', "
            + "CURRENT_TIMESTAMP)",
        ISSUER,
        DIRECTORY_ISSUER,
        scope.originOrganizationId());
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

  private static ExternalReference directoryReference(String type, String id) {
    return new ExternalReference(new IssuerId(DIRECTORY_ISSUER), type, id);
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  private record Scope(
      String originOrganizationId,
      String originFacilityId,
      String destinationFacilityId,
      String referralId,
      String recommendationId) {

    Scope withDifferentDestination() {
      return new Scope(
          originOrganizationId,
          originFacilityId,
          InternalId.generate().toString(),
          referralId,
          recommendationId);
    }

    Scope withDifferentReferralId() {
      return new Scope(
          originOrganizationId,
          originFacilityId,
          destinationFacilityId,
          InternalId.generate().toString(),
          recommendationId);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestPorts {

    @Bean
    @Primary
    StubMatchingApplicability matchingApplicabilityPort() {
      return new StubMatchingApplicability();
    }

    @Bean
    @Primary
    StubCapacityReservationPort capacityReservationPort(CapacityReservationCommands commands) {
      return new StubCapacityReservationPort(commands);
    }
  }

  static class StubCapacityReservationPort
      implements org.ayuvahak.transfer.application.CapacityReservationPort {
    private final CapacityReservationCommands commands;
    private final java.util.concurrent.atomic.AtomicBoolean loseResponse =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<RuntimeException> nextFailure =
        new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.CopyOnWriteArrayList<String> idempotencyKeys =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    StubCapacityReservationPort(CapacityReservationCommands commands) {
      this.commands = commands;
    }

    @Override
    public CapacityReservationResult reserve(
        org.ayuvahak.platform.executioncontext.ExecutionContext context,
        CapacityReservationRequest request,
        String idempotencyKey) {
      idempotencyKeys.add(idempotencyKey);
      RuntimeException configuredFailure = nextFailure.getAndSet(null);
      if (configuredFailure != null) {
        throw configuredFailure;
      }
      CapacityReservationResult result = commands.reserve(context, request, idempotencyKey);
      if (loseResponse.compareAndSet(true, false)) {
        throw new IllegalStateException("Synthetic lost Capacity response after commit");
      }
      return result;
    }

    void loseNextResponseAfterCommit() {
      loseResponse.set(true);
    }

    void failNext(RuntimeException failure) {
      nextFailure.set(failure);
    }

    java.util.List<String> idempotencyKeys() {
      return java.util.List.copyOf(idempotencyKeys);
    }

    void reset() {
      loseResponse.set(false);
      nextFailure.set(null);
      idempotencyKeys.clear();
    }
  }

  static class StubMatchingApplicability implements MatchingApplicabilityPort {
    private volatile RecommendationApplicabilityResult.Status status =
        RecommendationApplicabilityResult.Status.APPLICABLE;
    private final java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    private volatile org.ayuvahak.platform.executioncontext.ExecutionContext lastCaller;
    private volatile InternalId lastRecommendationId;
    private volatile ExternalReference lastCandidateFacility;
    private volatile InternalId lastExpectedReferralId;
    private volatile long lastReferralRevision;

    @Override
    public RecommendationApplicabilityResult revalidate(
        org.ayuvahak.platform.executioncontext.ExecutionContext caller,
        InternalId referralId,
        InternalId recommendationId,
        ExternalReference candidateFacility,
        long exactReferralRevision) {
      calls.incrementAndGet();
      lastCaller = caller;
      lastRecommendationId = recommendationId;
      lastCandidateFacility = candidateFacility;
      lastExpectedReferralId = referralId;
      lastReferralRevision = exactReferralRevision;
      RecommendationApplicabilityResult.Status effectiveStatus = status;
      java.util.List<String> reasons = java.util.List.of();
      if (status == RecommendationApplicabilityResult.Status.APPLICABLE
          && !referralId.equals(expectedReferralId)) {
        effectiveStatus = RecommendationApplicabilityResult.Status.NOT_APPLICABLE;
        reasons = java.util.List.of("REFERRAL_ID_MISMATCH");
      }
      return new RecommendationApplicabilityResult(
          recommendationId, candidateFacility, effectiveStatus, reasons, Instant.now());
    }

    void returnApplicable(String expectedReferralId) {
      status = RecommendationApplicabilityResult.Status.APPLICABLE;
      this.expectedReferralId = InternalId.parse(expectedReferralId);
    }

    private volatile InternalId expectedReferralId;

    void returnStatus(RecommendationApplicabilityResult.Status status) {
      this.status = status;
    }

    void reset() {
      status = RecommendationApplicabilityResult.Status.APPLICABLE;
      calls.set(0);
      lastCaller = null;
      lastRecommendationId = null;
      lastCandidateFacility = null;
      lastExpectedReferralId = null;
      lastReferralRevision = 0;
      expectedReferralId = null;
    }

    int calls() {
      return calls.get();
    }

    org.ayuvahak.platform.executioncontext.ExecutionContext lastCaller() {
      return lastCaller;
    }

    InternalId lastRecommendationId() {
      return lastRecommendationId;
    }

    ExternalReference lastCandidateFacility() {
      return lastCandidateFacility;
    }

    long lastReferralRevision() {
      return lastReferralRevision;
    }

    InternalId lastExpectedReferralId() {
      return lastExpectedReferralId;
    }
  }
}
