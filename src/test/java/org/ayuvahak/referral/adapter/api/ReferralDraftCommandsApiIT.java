package org.ayuvahak.referral.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.ayuvahak.platform.AyuVahakApplication;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventEnvelopeJsonCodec;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.InternalEvent;
import org.ayuvahak.platform.eventing.application.OutboxDispatcher;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.referral.application.ReferralMatchingRequirement;
import org.ayuvahak.referral.application.ReferralQueries;
import org.ayuvahak.referral.application.ReferralTransferOutcomeEventHandler;
import org.ayuvahak.transfer.application.TransferStore;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/** Authenticated command-seam tests against disposable local PostgreSQL/PostGIS. */
@SpringBootTest(
    classes = AyuVahakApplication.class,
    properties = {
      "management.tracing.sampling.probability=0.0",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-referral-service",
      "ayuvahak.authentication.allow-http=true",
      "ayuvahak.eventing.outbox.dispatcher-enabled=false"
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
  private static final String TRANSFER_AMENDMENT_GUARD = "ayuvahak-transfer-amendment-guard";
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
  @Autowired private OutboxDispatcher outboxDispatcher;
  @Autowired private ReferralQueries referralQueries;
  @Autowired private ExecutionContextFactory executionContextFactory;
  @Autowired private ReferralTransferOutcomeEventHandler transferOutcomeHandler;
  @Autowired private EventEnvelopeJsonCodec eventEnvelopeCodec;
  @Autowired private TransferStore transferStore;
  @Autowired private ObjectMapper objectMapper;

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
      if (Boolean.TRUE.equals(
          jdbc.queryForObject(
              "SELECT to_regclass('referral.submitted_revision') IS NOT NULL", Boolean.class))) {
        jdbc.update("DELETE FROM referral.consumer_event_receipt");
        jdbc.execute("TRUNCATE TABLE referral.cancellation, referral.resolution");
        jdbc.execute(
            "TRUNCATE TABLE referral.submitted_revision_required_care_capability, "
                + "referral.submitted_revision_permitted_geography_code, "
                + "referral.submitted_revision");
      }
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
    grant(ORIGINATOR, "ReferralSubmit", "FACILITY", organizationId, facilityId);
    grant(ORIGINATOR, "ReferralAmend", "FACILITY", organizationId, facilityId);
    grant(ORIGINATOR, "ReferralResolve", "FACILITY", organizationId, facilityId);
    grant(ORIGINATOR, "ReferralCancel", "FACILITY", organizationId, facilityId);
    grant(ORIGINATOR, "ReferralRead", "FACILITY", organizationId, facilityId);
    grantService(
        TRANSFER_AMENDMENT_GUARD, "TransferReferralAmendmentCheck", organizationId, facilityId);
  }

  @Test
  void authorizedOriginAmendsSubmittedReferralAsNewImmutableRevisionAndExactRetryReplays()
      throws Exception {
    String referralId = createDraft("amend-create", "opaque-synthetic-patient-before-amendment");
    performSubmission(referralId, ORIGINATOR, "amend-submit", "{\"expectedVersion\":1}")
        .andExpect(status().isOk());

    String amendment =
        amendmentBody(2, "ORIGIN_CORRECTION", "opaque-synthetic-patient-after-amendment");
    MvcResult result =
        performAmendment(referralId, ORIGINATOR, "amend-01", amendment)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(referralId))
            .andExpect(jsonPath("$.status").value("SUBMITTED"))
            .andExpect(jsonPath("$.version").value(3))
            .andExpect(jsonPath("$.submittedRevision.revisionNumber").value(2))
            .andExpect(jsonPath("$.submittedRevision.reasonCode").value("ORIGIN_CORRECTION"))
            .andExpect(
                jsonPath("$.submittedRevision.patientReference.identifier")
                    .value("opaque-synthetic-patient-after-amendment"))
            .andReturn();
    String submittedAt =
        com.jayway.jsonpath.JsonPath.read(
            result.getResponse().getContentAsString(), "$.submittedRevision.submittedAt");

    performAmendment(referralId, ORIGINATOR, "amend-01", amendment)
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.submittedRevision.revisionNumber").value(2))
        .andExpect(jsonPath("$.submittedRevision.submittedAt").value(submittedAt));

    assertThat(count("SELECT COUNT(*) FROM referral.submitted_revision")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT patient_identifier FROM referral.submitted_revision "
                    + "WHERE referral_id = ?::uuid AND revision_number = 1",
                String.class,
                referralId))
        .isEqualTo("opaque-synthetic-patient-before-amendment");
    assertThat(
            jdbc.queryForObject(
                "SELECT patient_identifier FROM referral.submitted_revision "
                    + "WHERE referral_id = ?::uuid AND revision_number = 2",
                String.class,
                referralId))
        .isEqualTo("opaque-synthetic-patient-after-amendment");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'REFERRAL_AMEND'",
                Integer.class))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM platform_eventing.internal_event")).isZero();
    performAmendment(
            referralId,
            ORIGINATOR,
            "amend-01",
            amendment.replace("ORIGIN_CORRECTION", "OTHER_REASON"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    performAmendment(
            referralId,
            ORIGINATOR,
            "amend-stale",
            amendmentBody(2, "ORIGIN_CORRECTION", "opaque-synthetic-patient-stale-amendment"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REFERRAL_VERSION_CONFLICT"));
    performAmendment(
            referralId,
            UNAUTHORIZED,
            "amend-unauthorized",
            amendmentBody(
                3, "ORIGIN_CORRECTION", "opaque-synthetic-patient-unauthorized-amendment"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));
    assertThat(count("SELECT COUNT(*) FROM referral.submitted_revision")).isEqualTo(2);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE referral.submitted_revision SET reason_code = 'ALTERED' "
                        + "WHERE referral_id = ?::uuid AND revision_number = 1",
                    referralId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void activeTransferRequestBlocksReferralAmendment() throws Exception {
    String referralId = createDraft("active-transfer-amend-create", "synthetic-patient-ref-01");
    performSubmission(
            referralId, ORIGINATOR, "active-transfer-amend-submit", "{\"expectedVersion\":1}")
        .andExpect(status().isOk());

    InternalId referralReference = InternalId.parse(referralId);
    ExternalReference originOrganization =
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId);
    ExternalReference originFacility =
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facilityId);
    Instant requestedAt = Instant.now();
    InternalId journeyId = InternalId.generate();
    transferStore.insertFirstRequest(
        new TransferJourney(
            journeyId,
            referralReference,
            originOrganization,
            Optional.of(originFacility),
            ActorReference.human(new IssuerId(ISSUER), ORIGINATOR),
            requestedAt,
            1),
        new TransferRequestAttempt(
            InternalId.generate(),
            journeyId,
            1,
            referralReference,
            1,
            InternalId.generate(),
            originFacility,
            TransferRequestAttempt.Status.PENDING,
            ActorReference.human(new IssuerId(ISSUER), ORIGINATOR),
            requestedAt,
            requestedAt.plus(Duration.ofHours(1)),
            Optional.empty(),
            Optional.empty(),
            1));

    performAmendment(
            referralId,
            ORIGINATOR,
            "active-transfer-amend-attempt",
            amendmentBody(2, "ORIGIN_CORRECTION", "synthetic-patient-ref-02"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REFERRAL_AMENDMENT_BLOCKED"));

    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM referral.draft WHERE referral_id = ?::uuid",
                Integer.class,
                referralId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM referral.submitted_revision WHERE referral_id = ?::uuid",
                Integer.class,
                referralId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'REFERRAL_AMEND'",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record "
                    + "WHERE action_code = 'TRANSFER_REFERRAL_AMENDMENT_STATUS_READ' "
                    + "AND outcome = 'SUCCEEDED'",
                Integer.class))
        .isEqualTo(1);

    jdbc.update(
        "UPDATE identity_access.authorization_grant SET grant_status = 'REVOKED', "
            + "revoked_at = CURRENT_TIMESTAMP, aggregate_sequence = aggregate_sequence + 1 "
            + "WHERE principal_kind = 'SERVICE' AND principal_identifier = ? "
            + "AND authority_code = 'TransferReferralAmendmentCheck'",
        TRANSFER_AMENDMENT_GUARD);
    performAmendment(
            referralId,
            ORIGINATOR,
            "active-transfer-amend-status-unavailable",
            amendmentBody(2, "ORIGIN_CORRECTION", "synthetic-patient-ref-03"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("REFERRAL_UNAVAILABLE"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record "
                    + "WHERE action_code = 'TRANSFER_REFERRAL_AMENDMENT_CHECK_DENIED' "
                    + "AND outcome = 'DENIED'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM referral.draft WHERE referral_id = ?::uuid",
                Integer.class,
                referralId))
        .isEqualTo(2);
  }

  @Test
  void originResolvesSubmittedReferralExplicitlyAndTerminalOutcomeIsIdempotent() throws Exception {
    String referralId = createDraft("resolve-create", "opaque-synthetic-patient-resolve");
    performSubmission(referralId, ORIGINATOR, "resolve-submit", "{\"expectedVersion\":1}")
        .andExpect(status().isOk());
    String resolution =
        "{\"expectedVersion\":2,\"outcome\":\"UNFULFILLED\","
            + "\"reasonCode\":\"ORIGIN_CLOSED_UNFULFILLED\"}";

    MvcResult result =
        performResolution(referralId, ORIGINATOR, "resolve-01", resolution)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(referralId))
            .andExpect(jsonPath("$.status").value("RESOLVED"))
            .andExpect(jsonPath("$.version").value(3))
            .andExpect(jsonPath("$.outcome").value("UNFULFILLED"))
            .andExpect(jsonPath("$.reasonCode").value("ORIGIN_CLOSED_UNFULFILLED"))
            .andExpect(jsonPath("$.resolvedBy.identifier").value(ORIGINATOR))
            .andExpect(jsonPath("$.resolvedAt").isNotEmpty())
            .andReturn();
    String resolvedAt =
        com.jayway.jsonpath.JsonPath.read(
            result.getResponse().getContentAsString(), "$.resolvedAt");

    performResolution(referralId, ORIGINATOR, "resolve-01", resolution)
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.status").value("RESOLVED"))
        .andExpect(jsonPath("$.resolvedAt").value(resolvedAt));
    performResolution(
            referralId, ORIGINATOR, "resolve-01", resolution.replace("UNFULFILLED", "FULFILLED"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

    assertThat(count("SELECT COUNT(*) FROM referral.resolution")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'REFERRAL_RESOLVE'",
                Integer.class))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM platform_eventing.internal_event")).isZero();

    performAmendment(
            referralId,
            ORIGINATOR,
            "resolve-after-amend",
            amendmentBody(3, "ORIGIN_CORRECTION", "opaque-synthetic-patient-too-late"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REFERRAL_ALREADY_RESOLVED"));
    performResolution(
            referralId,
            ORIGINATOR,
            "resolve-again",
            "{\"expectedVersion\":3,\"outcome\":\"FULFILLED\","
                + "\"reasonCode\":\"OUTCOME_CHANGED\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REFERRAL_ALREADY_RESOLVED"));
    assertThat(count("SELECT COUNT(*) FROM referral.resolution")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM referral.draft WHERE referral_id = ?::uuid",
                String.class,
                referralId))
        .isEqualTo("RESOLVED");
  }

  @Test
  void submittedCancellationIsAtomicMinimalAndWaitsWithoutDispatchOrReplay() throws Exception {
    String patientReference = "opaque-synthetic-patient-cancel";
    String referralId = createDraft("cancel-create", patientReference);
    performSubmission(referralId, ORIGINATOR, "cancel-submit", "{\"expectedVersion\":1}")
        .andExpect(status().isOk());
    String body = "{\"expectedVersion\":2,\"reasonCode\":\"ORIGIN_NO_LONGER_REQUIRED\"}";

    performCancellation(
            referralId,
            ORIGINATOR,
            "cancel-stale",
            "{\"expectedVersion\":1,\"reasonCode\":\"ORIGIN_NO_LONGER_REQUIRED\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REFERRAL_VERSION_CONFLICT"));

    performCancellation(referralId, ORIGINATOR, "cancel-01", body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(referralId))
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.version").value(3));
    performCancellation(referralId, ORIGINATOR, "cancel-01", body)
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    performCancellation(
            referralId,
            ORIGINATOR,
            "cancel-01",
            body.replace("ORIGIN_NO_LONGER_REQUIRED", "OTHER_REASON"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    performCancellation(referralId, UNAUTHORIZED, "cancel-denied", body)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));

    assertThat(count("SELECT COUNT(*) FROM referral.cancellation")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM referral.command_receipt")).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record "
                    + "WHERE action_code = 'REFERRAL_CANCEL' AND outcome = 'SUCCEEDED'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record "
                    + "WHERE action_code = 'REFERRAL_CANCEL' AND outcome = 'DENIED'",
                Integer.class))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM platform_eventing.internal_event")).isEqualTo(1);
    String eventEnvelope =
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform_eventing.internal_event WHERE event_type = ?",
            String.class,
            "referral.cancelled.v1");
    assertThat(eventEnvelope)
        .contains("ORIGIN_NO_LONGER_REQUIRED", referralId, "revisionNumber")
        .doesNotContain(patientReference, "PatientReference", "clinical");
    assertThat(
            jdbc.queryForObject(
                "SELECT d.state FROM platform_eventing.outbox_delivery d "
                    + "JOIN platform_eventing.internal_event e USING (event_id) "
                    + "WHERE e.event_type = 'referral.cancelled.v1'",
                String.class))
        .isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT d.attempt_count FROM platform_eventing.outbox_delivery d "
                    + "JOIN platform_eventing.internal_event e USING (event_id) "
                    + "WHERE e.event_type = 'referral.cancelled.v1'",
                Integer.class))
        .isZero();
    jdbc.update(
        "UPDATE platform_eventing.outbox_delivery SET state = 'WAITING_FOR_CONSUMER' "
            + "WHERE event_id = (SELECT event_id FROM platform_eventing.internal_event "
            + "WHERE event_type = 'referral.cancelled.v1')");
    assertThat(outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1)).delivered())
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT d.state FROM platform_eventing.outbox_delivery d "
                    + "JOIN platform_eventing.internal_event e USING (event_id) "
                    + "WHERE e.event_type = 'referral.cancelled.v1'",
                String.class))
        .isEqualTo("WAITING_FOR_CONSUMER");
    assertThat(
            jdbc.queryForObject(
                "SELECT d.attempt_count FROM platform_eventing.outbox_delivery d "
                    + "JOIN platform_eventing.internal_event e USING (event_id) "
                    + "WHERE e.event_type = 'referral.cancelled.v1'",
                Integer.class))
        .isZero();
  }

  @Test
  void draftCanBeCancelledWithoutPublishingCoordinationEvent() throws Exception {
    String referralId = createDraft("cancel-draft-create", "opaque-synthetic-patient-draft-cancel");
    String body = "{\"expectedVersion\":1,\"reasonCode\":\"DUPLICATE_REQUEST\"}";

    performCancellation(referralId, ORIGINATOR, "cancel-draft", body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.version").value(2));
    performCancellation(
            referralId,
            ORIGINATOR,
            "cancel-draft-again",
            "{\"expectedVersion\":2,\"reasonCode\":\"DUPLICATE_REQUEST\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REFERRAL_ALREADY_CANCELLED"));
    performAmendment(
            referralId,
            ORIGINATOR,
            "cancel-draft-amend",
            amendmentBody(2, "ORIGIN_CORRECTION", "opaque-synthetic-patient-late-amend"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REFERRAL_STATE_CONFLICT"));

    assertThat(count("SELECT COUNT(*) FROM referral.cancellation")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM platform_eventing.internal_event")).isZero();
  }

  @Test
  void unfulfilledTransferEventResolvesSubmittedReferralExactlyOnce() throws Exception {
    String referralId =
        createDraft("transfer-unfulfilled-create", "opaque-synthetic-patient-unfulfilled");
    performSubmission(
            referralId, ORIGINATOR, "transfer-unfulfilled-submit", "{\"expectedVersion\":1}")
        .andExpect(status().isOk());
    grantService(
        "ayuvahak-transfer-outcome-consumer", "ReferralResolve", organizationId, facilityId);

    InternalId eventId = InternalId.generate();
    EventType eventType = EventType.parse("transfer.unfulfilled.v1");
    CorrelationId eventCorrelation = new CorrelationId("referral-transfer-unfulfilled-correlation");
    var scope =
        RequestedExecutionScope.facility(
            new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId),
            new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facilityId));
    ExecutionContext producer =
        executionContextFactory.forBackgroundWork(
            ActorReference.service(new IssuerId(ISSUER), "synthetic-transfer-producer"),
            scope,
            eventCorrelation,
            Optional.empty());
    var payload = objectMapper.createObjectNode();
    payload.put("referralReference", referralId);
    payload.put("journeyReference", InternalId.generate().toString());
    payload.put("outcome", "UNFULFILLED");
    AggregatePosition position =
        new AggregatePosition("transfer-journey/" + InternalId.generate(), 1);
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            eventType,
            "transfer",
            new OccurredAt(Instant.parse("2026-10-10T10:30:00Z")),
            new RecordedAt(Instant.now()),
            producer,
            Optional.of(position),
            payload);
    InternalEvent event =
        new InternalEvent(
            eventId,
            eventType,
            "transfer",
            Optional.of(position),
            eventEnvelopeCodec.encode(envelope));

    jdbc.execute(
        "CREATE FUNCTION audit.reject_transfer_outcome() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN "
            + "IF NEW.action_code = 'REFERRAL_TRANSFER_OUTCOME_RECONCILE' THEN "
            + "RAISE EXCEPTION 'synthetic outcome audit failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_transfer_outcome BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION audit.reject_transfer_outcome()");
    try {
      assertThatThrownBy(() -> transferOutcomeHandler.handle(event)).isInstanceOf(Exception.class);
      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM referral.draft WHERE referral_id = ?",
                  String.class,
                  UUID.fromString(referralId)))
          .isEqualTo("SUBMITTED");
      assertThat(count("SELECT COUNT(*) FROM referral.resolution")).isZero();
      assertThat(count("SELECT COUNT(*) FROM referral.consumer_event_receipt")).isZero();
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS reject_transfer_outcome ON audit.audit_record");
      jdbc.execute("DROP FUNCTION IF EXISTS audit.reject_transfer_outcome()");
    }

    transferOutcomeHandler.handle(event);

    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM referral.draft WHERE referral_id = ?",
                String.class,
                UUID.fromString(referralId)))
        .isEqualTo("RESOLVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM referral.resolution WHERE referral_id = ?",
                String.class,
                UUID.fromString(referralId)))
        .isEqualTo("UNFULFILLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM referral.consumer_event_receipt WHERE event_id = ?",
                Integer.class,
                eventId.value()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM referral.consumer_event_receipt WHERE event_id = ?",
                String.class,
                eventId.value()))
        .isEqualTo("UNFULFILLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'REFERRAL_TRANSFER_OUTCOME_RECONCILE' AND correlation_id = ?",
                Integer.class,
                eventCorrelation.value()))
        .isEqualTo(1);

    jdbc.update(
        "DELETE FROM identity_access.authorization_grant WHERE principal_kind = 'SERVICE' "
            + "AND principal_issuer = ? AND principal_identifier_type = 'client_id' "
            + "AND principal_identifier = ? AND authority_code = 'ReferralResolve'",
        ISSUER,
        "ayuvahak-transfer-outcome-consumer");
    transferOutcomeHandler.handle(event);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'REFERRAL_TRANSFER_OUTCOME_DENIED' AND correlation_id = ?",
                Integer.class,
                eventCorrelation.value()))
        .isZero();
    grantService(
        "ayuvahak-transfer-outcome-consumer", "ReferralResolve", organizationId, facilityId);

    InternalId lateEventId = InternalId.generate();
    var latePayload = objectMapper.createObjectNode();
    latePayload.put("referralReference", referralId);
    latePayload.put("journeyReference", InternalId.generate().toString());
    latePayload.put("outcome", "UNFULFILLED");
    AggregatePosition latePosition =
        new AggregatePosition(position.aggregateRef(), position.sequence() + 1);
    EventEnvelope lateEnvelope =
        new EventEnvelope(
            lateEventId,
            eventType,
            "transfer",
            new OccurredAt(Instant.parse("2026-10-10T10:31:00Z")),
            new RecordedAt(Instant.now()),
            producer,
            Optional.of(latePosition),
            latePayload);
    transferOutcomeHandler.handle(
        new InternalEvent(
            lateEventId,
            eventType,
            "transfer",
            Optional.of(latePosition),
            eventEnvelopeCodec.encode(lateEnvelope)));
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM referral.resolution WHERE referral_id = ?",
                String.class,
                UUID.fromString(referralId)))
        .isEqualTo("UNFULFILLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM referral.consumer_event_receipt WHERE event_id = ?",
                String.class,
                lateEventId.value()))
        .isEqualTo("TERMINAL_NO_STATE_CHANGE");
  }

  @Test
  void deniedTransferOutcomeIsDurablyAuditedAndCanRetryAfterGrant() throws Exception {
    String referralId =
        createDraft("transfer-outcome-denied-create", "opaque-synthetic-patient-denied-outcome");
    performSubmission(
            referralId, ORIGINATOR, "transfer-outcome-denied-submit", "{\"expectedVersion\":1}")
        .andExpect(status().isOk());

    InternalId eventId = InternalId.generate();
    EventType eventType = EventType.parse("transfer.unfulfilled.v1");
    CorrelationId eventCorrelation =
        new CorrelationId("referral-transfer-outcome-denied-correlation");
    var scope =
        RequestedExecutionScope.facility(
            new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId),
            new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facilityId));
    ExecutionContext producer =
        executionContextFactory.forBackgroundWork(
            ActorReference.service(new IssuerId(ISSUER), "synthetic-transfer-producer"),
            scope,
            eventCorrelation,
            Optional.empty());
    var payload = objectMapper.createObjectNode();
    payload.put("referralReference", referralId);
    payload.put("journeyReference", InternalId.generate().toString());
    payload.put("outcome", "UNFULFILLED");
    AggregatePosition position =
        new AggregatePosition("transfer-journey/" + InternalId.generate(), 1);
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            eventType,
            "transfer",
            new OccurredAt(Instant.parse("2026-10-10T10:30:00Z")),
            new RecordedAt(Instant.now()),
            producer,
            Optional.of(position),
            payload);
    InternalEvent event =
        new InternalEvent(
            eventId,
            eventType,
            "transfer",
            Optional.of(position),
            eventEnvelopeCodec.encode(envelope));

    assertThatThrownBy(() -> transferOutcomeHandler.handle(event)).isInstanceOf(Exception.class);

    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM referral.draft WHERE referral_id = ?",
                String.class,
                UUID.fromString(referralId)))
        .isEqualTo("SUBMITTED");
    assertThat(count("SELECT COUNT(*) FROM referral.resolution")).isZero();
    assertThat(count("SELECT COUNT(*) FROM referral.consumer_event_receipt")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'REFERRAL_TRANSFER_OUTCOME_DENIED' AND outcome = 'DENIED' "
                    + "AND authorization_outcome = 'DENIED' AND correlation_id = ?",
                Integer.class,
                eventCorrelation.value()))
        .isEqualTo(1);

    grantService(
        "ayuvahak-transfer-outcome-consumer", "ReferralResolve", organizationId, facilityId);
    transferOutcomeHandler.handle(event);

    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM referral.draft WHERE referral_id = ?",
                String.class,
                UUID.fromString(referralId)))
        .isEqualTo("RESOLVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM referral.consumer_event_receipt WHERE event_id = ?",
                Integer.class,
                eventId.value()))
        .isEqualTo(1);
  }

  @Test
  void unavailableGrantStoreIsDurablyAuditedWithDistinctCodeAndCanRetry() throws Exception {
    String referralId =
        createDraft("transfer-outcome-grant-store-create", "opaque-synthetic-patient-grant-store");
    performSubmission(
            referralId,
            ORIGINATOR,
            "transfer-outcome-grant-store-submit",
            "{\"expectedVersion\":1}")
        .andExpect(status().isOk());

    InternalId eventId = InternalId.generate();
    EventType eventType = EventType.parse("transfer.unfulfilled.v1");
    CorrelationId eventCorrelation = new CorrelationId("referral-outcome-grant-store-correlation");
    var scope =
        RequestedExecutionScope.facility(
            new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId),
            new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facilityId));
    ExecutionContext producer =
        executionContextFactory.forBackgroundWork(
            ActorReference.service(new IssuerId(ISSUER), "synthetic-transfer-producer"),
            scope,
            eventCorrelation,
            Optional.empty());
    var payload = objectMapper.createObjectNode();
    payload.put("referralReference", referralId);
    payload.put("journeyReference", InternalId.generate().toString());
    payload.put("outcome", "UNFULFILLED");
    AggregatePosition position =
        new AggregatePosition("transfer-journey/" + InternalId.generate(), 1);
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            eventType,
            "transfer",
            new OccurredAt(Instant.parse("2026-10-10T10:30:00Z")),
            new RecordedAt(Instant.now()),
            producer,
            Optional.of(position),
            payload);
    InternalEvent event =
        new InternalEvent(
            eventId,
            eventType,
            "transfer",
            Optional.of(position),
            eventEnvelopeCodec.encode(envelope));

    jdbc.execute(
        "ALTER TABLE identity_access.authorization_grant RENAME TO authorization_grant_unavailable");
    try {
      assertThatThrownBy(() -> transferOutcomeHandler.handle(event)).isInstanceOf(Exception.class);
    } finally {
      jdbc.execute(
          "ALTER TABLE identity_access.authorization_grant_unavailable RENAME TO authorization_grant");
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM referral.draft WHERE referral_id = ?",
                String.class,
                UUID.fromString(referralId)))
        .isEqualTo("SUBMITTED");
    assertThat(count("SELECT COUNT(*) FROM referral.resolution")).isZero();
    assertThat(count("SELECT COUNT(*) FROM referral.consumer_event_receipt")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'REFERRAL_TRANSFER_OUTCOME_DENIED' AND reason_code = "
                    + "'AUTHORIZATION_STORE_UNAVAILABLE' AND correlation_id = ?",
                Integer.class,
                eventCorrelation.value()))
        .isEqualTo(1);

    grantService(
        "ayuvahak-transfer-outcome-consumer", "ReferralResolve", organizationId, facilityId);
    transferOutcomeHandler.handle(event);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM referral.draft WHERE referral_id = ?",
                String.class,
                UUID.fromString(referralId)))
        .isEqualTo("RESOLVED");
  }

  @Test
  void completedTransferEventResolvesSubmittedReferralAsFulfilled() throws Exception {
    String referralId =
        createDraft("transfer-completed-create", "opaque-synthetic-patient-completed");
    performSubmission(
            referralId, ORIGINATOR, "transfer-completed-submit", "{\"expectedVersion\":1}")
        .andExpect(status().isOk());
    grantService(
        "ayuvahak-transfer-outcome-consumer", "ReferralResolve", organizationId, facilityId);

    InternalId eventId = InternalId.generate();
    EventType eventType = EventType.parse("transfer.completed.v1");
    var scope =
        RequestedExecutionScope.facility(
            new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId),
            new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facilityId));
    ExecutionContext producer =
        executionContextFactory.forBackgroundWork(
            ActorReference.service(new IssuerId(ISSUER), "synthetic-transfer-producer"),
            scope,
            CorrelationId.generate(),
            Optional.empty());
    var payload = objectMapper.createObjectNode();
    payload.put("referralReference", referralId);
    payload.put("transferReference", InternalId.generate().toString());
    payload.put("outcome", "COMPLETED");
    AggregatePosition position =
        new AggregatePosition("transfer-journey/" + InternalId.generate(), 1);
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            eventType,
            "transfer",
            new OccurredAt(Instant.parse("2026-10-10T10:30:00Z")),
            new RecordedAt(Instant.now()),
            producer,
            Optional.of(position),
            payload);
    transferOutcomeHandler.handle(
        new InternalEvent(
            eventId,
            eventType,
            "transfer",
            Optional.of(position),
            eventEnvelopeCodec.encode(envelope)));

    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM referral.resolution WHERE referral_id = ?",
                String.class,
                UUID.fromString(referralId)))
        .isEqualTo("FULFILLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM referral.consumer_event_receipt WHERE event_id = ?",
                String.class,
                eventId.value()))
        .isEqualTo("FULFILLED");
  }

  @Test
  void outboxFailureRollsBackSubmittedCancellationAuditAndReceiptThenSameKeyCanRetry()
      throws Exception {
    String referralId = createDraft("cancel-outbox-create", "opaque-synthetic-patient-outbox");
    performSubmission(referralId, ORIGINATOR, "cancel-outbox-submit", "{\"expectedVersion\":1}")
        .andExpect(status().isOk());
    String body = "{\"expectedVersion\":2,\"reasonCode\":\"ORIGIN_NO_LONGER_REQUIRED\"}";
    jdbc.execute(
        "CREATE FUNCTION platform_eventing.reject_referral_cancel_outbox() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN "
            + "IF NEW.state = 'PENDING' THEN "
            + "RAISE EXCEPTION 'synthetic outbox failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_referral_cancel_outbox BEFORE INSERT ON "
            + "platform_eventing.outbox_delivery FOR EACH ROW EXECUTE FUNCTION "
            + "platform_eventing.reject_referral_cancel_outbox()");
    try {
      performCancellation(referralId, ORIGINATOR, "cancel-outbox", body)
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("REFERRAL_UNAVAILABLE"));
      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM referral.draft WHERE referral_id = ?::uuid",
                  String.class,
                  referralId))
          .isEqualTo("SUBMITTED");
      assertThat(count("SELECT COUNT(*) FROM referral.cancellation")).isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'REFERRAL_CANCEL'",
                  Integer.class))
          .isZero();
      assertThat(count("SELECT COUNT(*) FROM referral.command_receipt")).isEqualTo(2);
      assertThat(count("SELECT COUNT(*) FROM platform_eventing.internal_event")).isZero();
    } finally {
      jdbc.execute(
          "DROP TRIGGER IF EXISTS reject_referral_cancel_outbox ON "
              + "platform_eventing.outbox_delivery");
      jdbc.execute("DROP FUNCTION IF EXISTS platform_eventing.reject_referral_cancel_outbox()");
    }

    performCancellation(referralId, ORIGINATOR, "cancel-outbox", body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    assertThat(count("SELECT COUNT(*) FROM platform_eventing.internal_event")).isEqualTo(1);
  }

  @Test
  void auditFailureRollsBackSubmittedCancellationAndEventThenSameKeyCanRetry() throws Exception {
    String referralId = createDraft("cancel-audit-create", "opaque-synthetic-patient-audit-cancel");
    performSubmission(referralId, ORIGINATOR, "cancel-audit-submit", "{\"expectedVersion\":1}")
        .andExpect(status().isOk());
    String body = "{\"expectedVersion\":2,\"reasonCode\":\"ORIGIN_NO_LONGER_REQUIRED\"}";
    jdbc.execute(
        "CREATE FUNCTION audit.reject_referral_cancel() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN "
            + "IF NEW.action_code = 'REFERRAL_CANCEL' THEN "
            + "RAISE EXCEPTION 'synthetic audit failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_referral_cancel BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION audit.reject_referral_cancel()");
    try {
      performCancellation(referralId, ORIGINATOR, "cancel-audit", body)
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));
      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM referral.draft WHERE referral_id = ?::uuid",
                  String.class,
                  referralId))
          .isEqualTo("SUBMITTED");
      assertThat(count("SELECT COUNT(*) FROM referral.cancellation")).isZero();
      assertThat(count("SELECT COUNT(*) FROM referral.command_receipt")).isEqualTo(2);
      assertThat(count("SELECT COUNT(*) FROM platform_eventing.internal_event")).isZero();
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS reject_referral_cancel ON audit.audit_record");
      jdbc.execute("DROP FUNCTION IF EXISTS audit.reject_referral_cancel()");
    }

    performCancellation(referralId, ORIGINATOR, "cancel-audit", body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    assertThat(count("SELECT COUNT(*) FROM platform_eventing.internal_event")).isEqualTo(1);
  }

  @Test
  void failedAmendmentAuditRollsBackRevisionCurrentContentAndReceiptThenAllowsRetry()
      throws Exception {
    String referralId = createDraft("amend-audit-create", "opaque-synthetic-patient-amend-audit");
    performSubmission(referralId, ORIGINATOR, "amend-audit-submit", "{\"expectedVersion\":1}")
        .andExpect(status().isOk());
    String amendment =
        amendmentBody(2, "ORIGIN_CORRECTION", "opaque-synthetic-patient-amend-audit-corrected");
    jdbc.execute(
        "CREATE FUNCTION audit.reject_referral_amend() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN "
            + "IF NEW.action_code = 'REFERRAL_AMEND' THEN "
            + "RAISE EXCEPTION 'synthetic audit failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_referral_amend BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION audit.reject_referral_amend()");
    try {
      performAmendment(referralId, ORIGINATOR, "amend-audit-01", amendment)
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));
      assertThat(count("SELECT COUNT(*) FROM referral.submitted_revision")).isEqualTo(1);
      assertThat(count("SELECT COUNT(*) FROM referral.command_receipt")).isEqualTo(2);
      assertThat(
              jdbc.queryForObject(
                  "SELECT patient_identifier FROM referral.draft WHERE referral_id = ?::uuid",
                  String.class,
                  referralId))
          .isEqualTo("opaque-synthetic-patient-amend-audit");
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS reject_referral_amend ON audit.audit_record");
      jdbc.execute("DROP FUNCTION IF EXISTS audit.reject_referral_amend()");
    }

    performAmendment(referralId, ORIGINATOR, "amend-audit-01", amendment)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.submittedRevision.revisionNumber").value(2));
    assertThat(count("SELECT COUNT(*) FROM referral.submitted_revision")).isEqualTo(2);
    assertThat(count("SELECT COUNT(*) FROM referral.command_receipt")).isEqualTo(3);
  }

  @Test
  void concurrentAmendmentAndResolutionHaveOneWinnerAndAnExplicitLoser() throws Exception {
    String referralId = createDraft("amend-resolve-race-create", "opaque-synthetic-patient-race");
    performSubmission(
            referralId, ORIGINATOR, "amend-resolve-race-submit", "{\"expectedVersion\":1}")
        .andExpect(status().isOk());
    String amendment =
        amendmentBody(2, "ORIGIN_CORRECTION", "opaque-synthetic-patient-race-amended");
    String resolution =
        "{\"expectedVersion\":2,\"outcome\":\"UNFULFILLED\","
            + "\"reasonCode\":\"ORIGIN_CLOSED_UNFULFILLED\"}";
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<MvcResult> amendmentAttempt =
          executor.submit(
              () -> {
                start.await();
                return performAmendment(referralId, ORIGINATOR, "amend-race", amendment)
                    .andReturn();
              });
      Future<MvcResult> resolutionAttempt =
          executor.submit(
              () -> {
                start.await();
                return performResolution(referralId, ORIGINATOR, "resolve-race", resolution)
                    .andReturn();
              });
      start.countDown();
      MvcResult amendmentResult = amendmentAttempt.get();
      MvcResult resolutionResult = resolutionAttempt.get();
      assertThat(
              List.of(
                  amendmentResult.getResponse().getStatus(),
                  resolutionResult.getResponse().getStatus()))
          .containsExactlyInAnyOrder(200, 409);
      MvcResult loser =
          amendmentResult.getResponse().getStatus() == 409 ? amendmentResult : resolutionResult;
      String loserCode =
          com.jayway.jsonpath.JsonPath.parse(loser.getResponse().getContentAsString())
              .read("$.code", String.class);
      assertThat(loserCode).isIn("REFERRAL_VERSION_CONFLICT", "REFERRAL_ALREADY_RESOLVED");
      long revisionCount = count("SELECT COUNT(*) FROM referral.submitted_revision");
      long resolutionCount = count("SELECT COUNT(*) FROM referral.resolution");
      assertThat(revisionCount + resolutionCount).isEqualTo(2);
      assertThat(resolutionCount).isIn(0L, 1L);
      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM referral.draft WHERE referral_id = ?::uuid",
                  String.class,
                  referralId))
          .isIn("SUBMITTED", "RESOLVED");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void invalidAuthorityOrVersionCannotResolveAndAuditFailureRollsBackClosure() throws Exception {
    String referralId =
        createDraft("resolve-audit-create", "opaque-synthetic-patient-resolve-audit");
    performSubmission(referralId, ORIGINATOR, "resolve-audit-submit", "{\"expectedVersion\":1}")
        .andExpect(status().isOk());
    String body =
        "{\"expectedVersion\":2,\"outcome\":\"FULFILLED\","
            + "\"reasonCode\":\"ORIGIN_CONFIRMED_FULFILLED\"}";

    performResolution(referralId, UNAUTHORIZED, "resolve-unauthorized", body)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));
    performResolution(
            referralId,
            ORIGINATOR,
            "resolve-stale",
            body.replace("\"expectedVersion\":2", "\"expectedVersion\":1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REFERRAL_VERSION_CONFLICT"));

    jdbc.execute(
        "CREATE FUNCTION audit.reject_referral_resolve() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN "
            + "IF NEW.action_code = 'REFERRAL_RESOLVE' THEN "
            + "RAISE EXCEPTION 'synthetic audit failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_referral_resolve BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION audit.reject_referral_resolve()");
    try {
      performResolution(referralId, ORIGINATOR, "resolve-audit-01", body)
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));
      assertThat(count("SELECT COUNT(*) FROM referral.resolution")).isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM referral.draft WHERE referral_id = ?::uuid",
                  String.class,
                  referralId))
          .isEqualTo("SUBMITTED");
      assertThat(count("SELECT COUNT(*) FROM referral.command_receipt")).isEqualTo(2);
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS reject_referral_resolve ON audit.audit_record");
      jdbc.execute("DROP FUNCTION IF EXISTS audit.reject_referral_resolve()");
    }

    performResolution(referralId, ORIGINATOR, "resolve-audit-01", body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RESOLVED"))
        .andExpect(jsonPath("$.outcome").value("FULFILLED"));
    assertThat(count("SELECT COUNT(*) FROM referral.resolution")).isEqualTo(1);
  }

  @Test
  void authorizedOriginSubmitsAnImmutableRevisionAndExactRetryReturnsTheOriginalResult()
      throws Exception {
    String patientReference = "opaque-synthetic-patient-submit-01";
    String referralId = createDraft("submit-create-01", patientReference);
    String submitCommand = "{\"expectedVersion\":1}";

    MvcResult first =
        mockMvc
            .perform(
                post("/api/v1/referrals/{referralId}/submission", referralId)
                    .header("Authorization", bearer(token(ORIGINATOR)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", facilityId)
                    .header("Idempotency-Key", "submit-01")
                    .contentType("application/json")
                    .content(submitCommand))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(referralId))
            .andExpect(jsonPath("$.status").value("SUBMITTED"))
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.submittedRevision.revisionNumber").value(1))
            .andExpect(jsonPath("$.submittedRevision.origin.organizationId").value(organizationId))
            .andExpect(jsonPath("$.submittedRevision.origin.facilityId").value(facilityId))
            .andExpect(
                jsonPath("$.submittedRevision.patientReference.identifier").value(patientReference))
            .andExpect(
                jsonPath("$.submittedRevision.careRequirement.requiredCareCapabilityCodes[0]")
                    .value(CAPABILITY))
            .andExpect(jsonPath("$.submittedRevision.submittedAt").isNotEmpty())
            .andReturn();
    String submittedAt =
        com.jayway.jsonpath.JsonPath.read(
            first.getResponse().getContentAsString(), "$.submittedRevision.submittedAt");

    mockMvc
        .perform(
            post("/api/v1/referrals/{referralId}/submission", referralId)
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", "submit-01")
                .contentType("application/json")
                .content(submitCommand))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.status").value("SUBMITTED"))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.submittedRevision.revisionNumber").value(1))
        .andExpect(jsonPath("$.submittedRevision.submittedAt").value(submittedAt));

    assertThat(
            count("SELECT COUNT(*) FROM referral.draft WHERE state = 'SUBMITTED' AND version = 2"))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM referral.submitted_revision")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM referral.submitted_revision_required_care_capability"))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM referral.command_receipt")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'REFERRAL_SUBMIT'",
                Integer.class))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM platform_eventing.internal_event")).isZero();

    mockMvc
        .perform(
            post("/api/v1/referrals/{referralId}/submission", referralId)
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", "submit-01")
                .contentType("application/json")
                .content("{\"expectedVersion\":2}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

    performSubmission(referralId, ORIGINATOR, "submit-again-01", "{\"expectedVersion\":2}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REFERRAL_STATE_CONFLICT"));
  }

  @Test
  void staleVersionAndMissingAuthorityCannotSubmit() throws Exception {
    String referralId = createDraft("submit-stale-create", "opaque-synthetic-patient-stale-submit");

    performSubmission(referralId, ORIGINATOR, "submit-stale", "{\"expectedVersion\":2}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REFERRAL_VERSION_CONFLICT"));

    performSubmission(referralId, UNAUTHORIZED, "submit-unauthorized", "{\"expectedVersion\":1}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));

    assertThat(count("SELECT COUNT(*) FROM referral.draft WHERE state = 'DRAFT' AND version = 1"))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM referral.submitted_revision")).isZero();
    assertThat(count("SELECT COUNT(*) FROM referral.command_receipt")).isEqualTo(1);
  }

  @Test
  void submissionRevalidatesActiveOriginAndCareCapability() throws Exception {
    String referralId =
        createDraft("submit-revalidation-create", "opaque-synthetic-patient-revalidation");
    String inactiveOriginReferralId =
        createDraft("submit-inactive-origin-create", "opaque-synthetic-patient-inactive-origin");
    mockMvc
        .perform(
            post("/api/v1/directory/capabilities/{capabilityId}/deactivation", capabilityId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "submit-revalidation-deactivate-capability")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":1,\"provenance\":{\"method\":\"MANUAL_REVIEW\","
                        + "\"observedAt\":\"2026-10-09T09:00:00Z\","
                        + "\"reasonCode\":\"SYNTHETIC_TEST_DEACTIVATION\"}}"))
        .andExpect(status().isNoContent());

    performSubmission(
            referralId, ORIGINATOR, "submit-inactive-capability", "{\"expectedVersion\":1}")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("REFERRAL_CAPABILITY_UNKNOWN"));
    assertThat(count("SELECT COUNT(*) FROM referral.draft WHERE state = 'DRAFT' AND version = 1"))
        .isEqualTo(2);
    assertThat(count("SELECT COUNT(*) FROM referral.submitted_revision")).isZero();

    mockMvc
        .perform(
            post("/api/v1/directory/facilities/{facilityId}/deactivation", facilityId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", "submit-revalidation-deactivate-facility")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":1,\"provenance\":{\"method\":\"MANUAL_REVIEW\","
                        + "\"observedAt\":\"2026-10-09T09:00:00Z\","
                        + "\"reasonCode\":\"SYNTHETIC_TEST_CLOSURE\"}}"))
        .andExpect(status().isNoContent());

    performSubmission(
            inactiveOriginReferralId,
            ORIGINATOR,
            "submit-inactive-origin",
            "{\"expectedVersion\":1}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_SCOPE"));
    assertThat(count("SELECT COUNT(*) FROM referral.submitted_revision")).isZero();
  }

  @Test
  void failedSubmitAuditRollsBackStateRevisionAndReceiptThenAllowsSameKeyRetry() throws Exception {
    String referralId = createDraft("submit-audit-create", "opaque-synthetic-patient-audit-submit");
    jdbc.execute(
        "CREATE FUNCTION audit.reject_referral_submit() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN "
            + "IF NEW.action_code = 'REFERRAL_SUBMIT' THEN "
            + "RAISE EXCEPTION 'synthetic audit failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_referral_submit BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION audit.reject_referral_submit()");
    try {
      performSubmission(referralId, ORIGINATOR, "submit-audit-01", "{\"expectedVersion\":1}")
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));
      assertThat(count("SELECT COUNT(*) FROM referral.draft WHERE state = 'DRAFT' AND version = 1"))
          .isEqualTo(1);
      assertThat(count("SELECT COUNT(*) FROM referral.submitted_revision")).isZero();
      assertThat(count("SELECT COUNT(*) FROM referral.command_receipt")).isEqualTo(1);
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS reject_referral_submit ON audit.audit_record");
      jdbc.execute("DROP FUNCTION IF EXISTS audit.reject_referral_submit()");
    }

    performSubmission(referralId, ORIGINATOR, "submit-audit-01", "{\"expectedVersion\":1}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUBMITTED"));
    assertThat(count("SELECT COUNT(*) FROM referral.command_receipt")).isEqualTo(2);
    assertThat(count("SELECT COUNT(*) FROM referral.submitted_revision")).isEqualTo(1);
  }

  @Test
  void concurrentSubmissionsProduceOneRevisionAndOneExplicitConflict() throws Exception {
    String referralId = createDraft("submit-race-create", "opaque-synthetic-patient-race");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<MvcResult>> attempts = new ArrayList<>();
      attempts.add(
          executor.submit(
              () -> {
                start.await();
                return performSubmission(
                        referralId, ORIGINATOR, "submit-race-01", "{\"expectedVersion\":1}")
                    .andReturn();
              }));
      attempts.add(
          executor.submit(
              () -> {
                start.await();
                return performSubmission(
                        referralId, ORIGINATOR, "submit-race-02", "{\"expectedVersion\":1}")
                    .andReturn();
              }));
      start.countDown();
      List<MvcResult> results = List.of(attempts.get(0).get(), attempts.get(1).get());
      assertThat(results.stream().map(result -> result.getResponse().getStatus()).toList())
          .containsExactlyInAnyOrder(200, 409);
      MvcResult conflict =
          results.stream()
              .filter(result -> result.getResponse().getStatus() == 409)
              .findFirst()
              .orElseThrow();
      String conflictCode =
          com.jayway.jsonpath.JsonPath.parse(conflict.getResponse().getContentAsString())
              .read("$.code", String.class);
      assertThat(conflictCode).isIn("REFERRAL_STATE_CONFLICT", "REFERRAL_VERSION_CONFLICT");
      assertThat(count("SELECT COUNT(*) FROM referral.submitted_revision")).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'REFERRAL_SUBMIT'",
                  Integer.class))
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void persistedSubmittedRevisionRejectsUpdatesAndRetainsItsSubmittedContent() throws Exception {
    String patientReference = "opaque-synthetic-patient-immutable";
    String referralId = createDraft("submit-immutable-create", patientReference);
    performSubmission(referralId, ORIGINATOR, "submit-immutable", "{\"expectedVersion\":1}")
        .andExpect(status().isOk());

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE referral.submitted_revision SET patient_identifier = ? "
                        + "WHERE referral_id = ?::uuid AND revision_number = 1",
                    "changed-synthetic-value",
                    referralId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE referral.submitted_revision_required_care_capability "
                        + "SET capability_code = 'CHANGED' WHERE referral_id = ?::uuid "
                        + "AND revision_number = 1",
                    referralId))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT patient_identifier FROM referral.submitted_revision "
                    + "WHERE referral_id = ?::uuid AND revision_number = 1",
                String.class,
                referralId))
        .isEqualTo(patientReference);
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

  @Test
  void authorizedOriginReadsCurrentAndImmutableRevisionWhileListOmitsPatientReference()
      throws Exception {
    String patientReference = "opaque-synthetic-patient-query-01";
    String referralId = createDraft("query-read-create", patientReference);
    performSubmission(referralId, ORIGINATOR, "query-read-submit", "{\"expectedVersion\":1}")
        .andExpect(status().isOk());

    mockMvc
        .perform(
            get("/api/v1/referrals/{referralId}", referralId)
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("X-Correlation-ID", "referral-query-current-01"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-ID", "referral-query-current-01"))
        .andExpect(jsonPath("$.id").value(referralId))
        .andExpect(jsonPath("$.status").value("SUBMITTED"))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.patientReference.identifier").value(patientReference))
        .andExpect(jsonPath("$.submittedRevision.revisionNumber").value(1));

    mockMvc
        .perform(
            get("/api/v1/referrals/{referralId}/revisions/{revisionNumber}", referralId, 1)
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(referralId))
        .andExpect(jsonPath("$.submittedRevision.revisionNumber").value(1))
        .andExpect(
            jsonPath("$.submittedRevision.patientReference.identifier").value(patientReference));

    mockMvc
        .perform(
            get("/api/v1/referrals")
                .queryParam("status", "SUBMITTED")
                .queryParam("page", "0")
                .queryParam("size", "1")
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(1))
        .andExpect(jsonPath("$.items[0].id").value(referralId))
        .andExpect(jsonPath("$.items[0].status").value("SUBMITTED"))
        .andExpect(jsonPath("$.items[0].patientReference").doesNotExist())
        .andExpect(jsonPath("$.items[0].careRequirement").doesNotExist());

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code IN "
                    + "('REFERRAL_DETAIL_READ', 'REFERRAL_REVISION_READ', 'REFERRAL_LIST_READ')",
                Integer.class))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE actor_reference = ?",
                Integer.class,
                patientReference))
        .isZero();
  }

  @Test
  void matchingServiceGetsOnlyCurrentSubmittedRequirementAndItsReadIsAttributed() throws Exception {
    String patientReference = "opaque-synthetic-patient-matching-contract";
    String referralId = createDraft("matching-read-create", patientReference);
    performSubmission(referralId, ORIGINATOR, "matching-read-submit", "{\"expectedVersion\":1}")
        .andExpect(status().isOk());

    String serviceClient = "synthetic-matching-evaluator";
    grantService(serviceClient, "ReferralRead", organizationId, facilityId);
    ExecutionContext serviceContext =
        executionContextFactory.forBackgroundWork(
            ActorReference.service(new IssuerId(ISSUER), serviceClient),
            RequestedExecutionScope.facility(
                new ExternalReference(
                    new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId),
                new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facilityId)),
            new CorrelationId("matching-owner-read-correlation"),
            java.util.Optional.empty());

    ReferralMatchingRequirement requirement =
        referralQueries.getSubmittedRequirement(serviceContext, InternalId.parse(referralId), 1);

    assertThat(requirement.referralId()).isEqualTo(InternalId.parse(referralId));
    assertThat(requirement.revisionNumber()).isEqualTo(1);
    assertThat(requirement.currentReferralVersion()).isEqualTo(2);
    assertThat(requirement.requiredCareCapabilityCodes()).containsExactly(CAPABILITY);
    assertThat(requirement.permittedGeographyCodes()).containsExactly("SYNTHETIC_DISTRICT_01");
    assertThat(requirement.toString()).doesNotContain(patientReference);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'REFERRAL_MATCHING_REQUIREMENT_READ' AND reason_code = "
                    + "'MATCHING_EVALUATION' AND correlation_id = ?",
                Integer.class,
                "matching-owner-read-correlation"))
        .isEqualTo(1);

    String unauthorizedClient = "synthetic-matching-without-referral-grant";
    ExecutionContext unauthorizedContext =
        executionContextFactory.forBackgroundWork(
            ActorReference.service(new IssuerId(ISSUER), unauthorizedClient),
            RequestedExecutionScope.facility(
                new ExternalReference(
                    new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId),
                new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facilityId)),
            new CorrelationId("matching-owner-read-denied"),
            java.util.Optional.empty());
    assertThatThrownBy(
            () ->
                referralQueries.getSubmittedRequirement(
                    unauthorizedContext, InternalId.parse(referralId), 1))
        .hasMessageContaining("not authorized");
  }

  @Test
  void referralReadsRequireReadAuthorityAndHideCrossScopeExistence() throws Exception {
    String referralId = createDraft("query-scope-create", "opaque-synthetic-patient-scope");
    String otherOrganizationId = createOrganization("query-other-organization");
    String otherFacilityId = createFacilityFor(otherOrganizationId, "query-other-facility");
    grant(ORIGINATOR, "ReferralRead", "FACILITY", otherOrganizationId, otherFacilityId);

    mockMvc
        .perform(
            get("/api/v1/referrals/{referralId}", referralId)
                .header("Authorization", bearer(token(UNAUTHORIZED)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"))
        .andExpect(header().exists("X-Correlation-ID"));

    mockMvc
        .perform(
            get("/api/v1/referrals/{referralId}", referralId)
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", otherOrganizationId)
                .header("X-AyuVahak-Facility-Id", otherFacilityId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REFERRAL_NOT_FOUND"));
  }

  @Test
  void organizationReadsIncludeFacilityReferralsAndPagesAreStableAndDisjoint() throws Exception {
    String firstReferral = createDraft("query-page-one-create", "synthetic-list-patient-one");
    performSubmission(firstReferral, ORIGINATOR, "query-page-one-submit", "{\"expectedVersion\":1}")
        .andExpect(status().isOk());
    String secondReferral = createDraft("query-page-two-create", "synthetic-list-patient-two");
    performSubmission(
            secondReferral, ORIGINATOR, "query-page-two-submit", "{\"expectedVersion\":1}")
        .andExpect(status().isOk());

    var firstPage =
        mockMvc
            .perform(
                get("/api/v1/referrals")
                    .queryParam("status", "SUBMITTED")
                    .queryParam("page", "0")
                    .queryParam("size", "1")
                    .header("Authorization", bearer(token(ORIGINATOR)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", facilityId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].patientReference").doesNotExist())
            .andExpect(jsonPath("$.hasMore").value(true))
            .andReturn();
    var repeatedFirstPage =
        mockMvc
            .perform(
                get("/api/v1/referrals")
                    .queryParam("status", "SUBMITTED")
                    .queryParam("page", "0")
                    .queryParam("size", "1")
                    .header("Authorization", bearer(token(ORIGINATOR)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", facilityId))
            .andExpect(status().isOk())
            .andReturn();
    var secondPage =
        mockMvc
            .perform(
                get("/api/v1/referrals")
                    .queryParam("status", "SUBMITTED")
                    .queryParam("page", "1")
                    .queryParam("size", "1")
                    .header("Authorization", bearer(token(ORIGINATOR)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", facilityId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andReturn();
    assertThat(repeatedFirstPage.getResponse().getContentAsString())
        .isEqualTo(firstPage.getResponse().getContentAsString());
    String pages =
        firstPage.getResponse().getContentAsString()
            + secondPage.getResponse().getContentAsString();
    assertThat(pages).contains(firstReferral, secondReferral);

    grant(ORIGINATOR, "ReferralRead", "ORGANIZATION", organizationId, null);
    Instant queryTime = Instant.now();
    mockMvc
        .perform(
            get("/api/v1/referrals")
                .queryParam("status", "SUBMITTED")
                .queryParam("from", queryTime.minus(Duration.ofDays(1)).toString())
                .queryParam("until", queryTime.plus(Duration.ofDays(1)).toString())
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "ORGANIZATION")
                .header("X-AyuVahak-Organization-Id", organizationId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[*].patientReference").doesNotExist());
  }

  @Test
  void referralListRejectsUnboundedOrUnknownFiltersAndSensitiveReadAuditFailureFailsClosed()
      throws Exception {
    String referralId = createDraft("query-audit-create", "opaque-synthetic-patient-audit");
    jdbc.execute(
        "CREATE FUNCTION audit.reject_referral_detail_read() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN "
            + "IF NEW.action_code = 'REFERRAL_DETAIL_READ' THEN "
            + "RAISE EXCEPTION 'synthetic read audit failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_referral_detail_read BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION audit.reject_referral_detail_read()");
    try {
      mockMvc
          .perform(
              get("/api/v1/referrals/{referralId}", referralId)
                  .header("Authorization", bearer(token(ORIGINATOR)))
                  .header("X-AyuVahak-Execution-Scope", "FACILITY")
                  .header("X-AyuVahak-Organization-Id", organizationId)
                  .header("X-AyuVahak-Facility-Id", facilityId))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"))
          .andExpect(jsonPath("$.patientReference").doesNotExist());
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS reject_referral_detail_read ON audit.audit_record");
      jdbc.execute("DROP FUNCTION IF EXISTS audit.reject_referral_detail_read()");
    }

    mockMvc
        .perform(
            get("/api/v1/referrals")
                .queryParam("page", "0")
                .queryParam("size", "101")
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REFERRAL_REQUIREMENT_INVALID"));

    mockMvc
        .perform(
            get("/api/v1/referrals")
                .queryParam("patientIdentifier", "must-not-be-a-filter")
                .header("Authorization", bearer(token(ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REFERRAL_REQUIREMENT_INVALID"));
  }

  private org.springframework.test.web.servlet.ResultActions performSubmission(
      String referralId, String subject, String idempotencyKey, String body) throws Exception {
    return mockMvc.perform(
        post("/api/v1/referrals/{referralId}/submission", referralId)
            .header("Authorization", bearer(token(subject)))
            .header("X-AyuVahak-Execution-Scope", "FACILITY")
            .header("X-AyuVahak-Organization-Id", organizationId)
            .header("X-AyuVahak-Facility-Id", facilityId)
            .header("Idempotency-Key", idempotencyKey)
            .contentType("application/json")
            .content(body));
  }

  private org.springframework.test.web.servlet.ResultActions performAmendment(
      String referralId, String subject, String idempotencyKey, String body) throws Exception {
    return mockMvc.perform(
        post("/api/v1/referrals/{referralId}/revisions", referralId)
            .header("Authorization", bearer(token(subject)))
            .header("X-AyuVahak-Execution-Scope", "FACILITY")
            .header("X-AyuVahak-Organization-Id", organizationId)
            .header("X-AyuVahak-Facility-Id", facilityId)
            .header("Idempotency-Key", idempotencyKey)
            .contentType("application/json")
            .content(body));
  }

  private org.springframework.test.web.servlet.ResultActions performResolution(
      String referralId, String subject, String idempotencyKey, String body) throws Exception {
    return mockMvc.perform(
        post("/api/v1/referrals/{referralId}/resolution", referralId)
            .header("Authorization", bearer(token(subject)))
            .header("X-AyuVahak-Execution-Scope", "FACILITY")
            .header("X-AyuVahak-Organization-Id", organizationId)
            .header("X-AyuVahak-Facility-Id", facilityId)
            .header("Idempotency-Key", idempotencyKey)
            .contentType("application/json")
            .content(body));
  }

  private org.springframework.test.web.servlet.ResultActions performCancellation(
      String referralId, String subject, String idempotencyKey, String body) throws Exception {
    return mockMvc.perform(
        post("/api/v1/referrals/{referralId}/cancellation", referralId)
            .header("Authorization", bearer(token(subject)))
            .header("X-AyuVahak-Execution-Scope", "FACILITY")
            .header("X-AyuVahak-Organization-Id", organizationId)
            .header("X-AyuVahak-Facility-Id", facilityId)
            .header("Idempotency-Key", idempotencyKey)
            .contentType("application/json")
            .content(body));
  }

  private String amendmentBody(long expectedVersion, String reasonCode, String patientIdentifier) {
    return """
        {
          "expectedVersion": %d,
          "reasonCode": "%s",
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
        .formatted(expectedVersion, reasonCode, patientIdentifier, CAPABILITY);
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
    return createOrganization("referral-origin-organization");
  }

  private String createOrganization(String idempotencyKey) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/directory/organizations")
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", idempotencyKey)
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

  private String createFacilityFor(String managingOrganizationId, String idempotencyKey)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/directory/facilities")
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType("application/json")
                    .content(
                        "{\"name\":\"Synthetic Other Referral Facility\","
                            + "\"managingOrganizationId\":\""
                            + managingOrganizationId
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
    if ("ORGANIZATION".equals(scope)) {
      jdbc.update(
          "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
              + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
              + "scope_kind, organization_issuer, organization_identifier_type, "
              + "organization_identifier, grant_status, created_at) VALUES (gen_random_uuid(), "
              + "'HUMAN', ?, 'subject', ?, ?, 'ORGANIZATION', ?, 'Organization', ?, 'ACTIVE', "
              + "CURRENT_TIMESTAMP)",
          ISSUER,
          subject,
          authority,
          DIRECTORY_ISSUER,
          organization);
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

  private void grantService(
      String clientId, String authority, String organization, String facility) {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, organization_issuer, organization_identifier_type, "
            + "organization_identifier, facility_issuer, facility_identifier_type, "
            + "facility_identifier, grant_status, created_at) VALUES (gen_random_uuid(), "
            + "'SERVICE', ?, 'client_id', ?, ?, 'FACILITY', ?, 'Organization', ?, ?, 'Facility', ?, "
            + "'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        clientId,
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
