package org.ayuvahak.transfer.adapter.api;

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
import org.ayuvahak.directory.application.DirectoryStore;
import org.ayuvahak.directory.domain.DirectoryLifecycleStatus;
import org.ayuvahak.directory.domain.DirectoryProvenance;
import org.ayuvahak.directory.domain.DirectorySource;
import org.ayuvahak.directory.domain.Facility;
import org.ayuvahak.directory.domain.Organization;
import org.ayuvahak.matching.application.RecommendationApplicabilityResult;
import org.ayuvahak.platform.AyuVahakApplication;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.transfer.adapter.configuration.TransferProperties;
import org.ayuvahak.transfer.application.MatchingApplicabilityPort;
import org.ayuvahak.transfer.application.TransferRequestExpirySweep;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
      "ayuvahak.transfer.expiry-polling-enabled=false"
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
  @Autowired private DataSource dataSource;

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
    jdbc.update("DELETE FROM transfer.command_receipt");
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
    matching.reset();
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
        .isEqualTo(4);
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
    assertThat(count("SELECT COUNT(*) FROM platform_eventing.internal_event")).isZero();
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
            + "?, 'Facility', ?, 'ACTIVE', CURRENT_TIMESTAMP)",
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
