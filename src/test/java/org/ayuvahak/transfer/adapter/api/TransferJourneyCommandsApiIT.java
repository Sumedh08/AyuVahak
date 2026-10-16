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
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.ayuvahak.capacity.application.CapacityReservationCommands;
import org.ayuvahak.capacity.application.CapacityReservationLifecycleProperties;
import org.ayuvahak.capacity.domain.CapacityReleaseResult;
import org.ayuvahak.capacity.domain.CapacityReservationOutcome;
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
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventEnvelopeJsonCodec;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.InternalEvent;
import org.ayuvahak.platform.eventing.application.OutboxDispatcher;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.transfer.adapter.configuration.TransferProperties;
import org.ayuvahak.transfer.application.MatchingApplicabilityPort;
import org.ayuvahak.transfer.application.TransferCapacityReservationRecovery;
import org.ayuvahak.transfer.application.TransferDestinationChangeEventHandler;
import org.ayuvahak.transfer.application.TransferOwnerEventHandler;
import org.ayuvahak.transfer.application.TransferRequestExpirySweep;
import org.ayuvahak.transfer.application.TransferTransportMovementEventHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/** Transfer's first-request command over HTTP and real disposable PostgreSQL/PostGIS. */
@SpringBootTest(
    classes = AyuVahakApplication.class,
    properties = {
      "management.tracing.sampling.probability=0.0",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-transfer-worker,ayuvahak-transport-worker",
      "ayuvahak.authentication.allow-http=true",
      "ayuvahak.transfer.request-deadline=PT1H",
      "ayuvahak.transfer.reservation-dimension=TRANSFER_COORDINATION_SLOT",
      "ayuvahak.transfer.reservation-unit=slot",
      "ayuvahak.transfer.reservation-quantity=1",
      "ayuvahak.transfer.reservation-duration=PT2H",
      "ayuvahak.transfer.expiry-polling-enabled=false",
      "ayuvahak.transfer.reservation-polling-enabled=false",
      "ayuvahak.capacity.reservation-lifecycle.enabled=false",
      "ayuvahak.eventing.outbox.dispatcher-enabled=false"
    })
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(TransferJourneyCommandsApiIT.TestPorts.class)
@ExtendWith(OutputCaptureExtension.class)
class TransferJourneyCommandsApiIT {

  private static final String ISSUER = "https://identity.example.test/realms/transfer-fixture";
  private static final String AUDIENCE = "ayuvahak-api";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String SENDER = "synthetic-transfer-sender";
  private static final String TRANSPORT_WORKER = "ayuvahak-transport-worker";
  private static final String TEST_PASSWORD = "local-test-only";
  private static final RSAKey SIGNING_KEY = newSigningKey("transfer-first-request-fixture");
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(TEST_PASSWORD)
          .withStartupTimeout(Duration.ofMinutes(2));

  @Container private static final PostgreSQLContainer CONTAINER = POSTGRES;
  private static HttpServer jwksServer;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DirectoryStore directory;
  @Autowired private TransferProperties transferProperties;
  @Autowired private StubMatchingApplicability matching;
  @Autowired private TransferRequestExpirySweep expirySweep;
  @Autowired private TransferCapacityReservationRecovery reservationRecovery;
  @Autowired private TransferDestinationChangeEventHandler destinationChangeRecovery;
  @Autowired private TransferTransportMovementEventHandler transportMovementEventHandler;
  @Autowired private DataSource dataSource;
  @Autowired private CapacityReservationCommands capacityReservations;
  @Autowired private CapacityReservationLifecycleProperties capacityLifecycleProperties;
  @Autowired private ExecutionContextFactory executionContexts;
  @Autowired private AutowireCapableBeanFactory beanFactory;
  @Autowired private StubCapacityReservationPort capacityPort;
  @Autowired private TransferOwnerEventHandler ownerEventHandler;
  @Autowired private EventEnvelopeJsonCodec eventCodec;
  @Autowired private OutboxDispatcher outboxDispatcher;
  @Autowired private ObjectMapper objectMapper;

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
        "TRUNCATE capacity.reservation_release_operation, capacity.reservation_command_receipt, "
            + "capacity.coordination_reservation, capacity.reservation_operation");
    jdbc.update("DELETE FROM transfer.consumer_event_receipt");
    jdbc.update("DELETE FROM platform_eventing.delivery_command_receipt");
    jdbc.update("DELETE FROM platform_eventing.outbox_delivery");
    jdbc.update("DELETE FROM platform_eventing.internal_event");
    jdbc.update("DELETE FROM transfer.progress_command_receipt");
    jdbc.update("DELETE FROM transfer.progress_correction");
    jdbc.update("DELETE FROM transfer.progress_fact");
    jdbc.update("DELETE FROM transfer.command_receipt");
    jdbc.update("DELETE FROM transfer.destination_change_receipt");
    jdbc.update("DELETE FROM transfer.destination_change_decision");
    jdbc.update("DELETE FROM transfer.destination_change_assessment");
    jdbc.update("DELETE FROM transfer.destination_change_reconciliation");
    jdbc.update("DELETE FROM transfer.destination_change_operation");
    jdbc.update("DELETE FROM transfer.active_destination");
    jdbc.update("DELETE FROM transfer.capacity_reservation_request");
    jdbc.update("DELETE FROM transfer.transport_cancellation_outcome");
    jdbc.update("DELETE FROM transfer.request_decision");
    jdbc.update("DELETE FROM transfer.transport_request_link");
    jdbc.update("DELETE FROM transfer.transfer_record");
    jdbc.update("DELETE FROM transfer.receiving_assessment");
    jdbc.update("DELETE FROM transfer.journey_closure");
    jdbc.update("DELETE FROM transfer.request_attempt");
    jdbc.update("DELETE FROM transfer.journey");
    jdbc.update("DELETE FROM transport.eta_observation");
    jdbc.update("DELETE FROM transport.assignment_observation");
    jdbc.update("DELETE FROM transport.status_observation");
    jdbc.update("DELETE FROM transport.cancellation_command_receipt");
    jdbc.update("DELETE FROM transport.cancellation_operation");
    jdbc.update("DELETE FROM transport.destination_change_operation");
    jdbc.update("DELETE FROM transport.command_receipt");
    jdbc.update("DELETE FROM transport.transport_request");
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
  void originCanDeclareWhetherTransportIsRequiredForTheJourney() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());

    String response =
        mockMvc
            .perform(
                post("/api/v1/transfer-journeys")
                    .header("Authorization", "Bearer " + token(SENDER))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                    .header("Idempotency-Key", "transport-required-journey")
                    .contentType("application/json")
                    .content(
                        "{\"referralId\":\""
                            + scope.referralId()
                            + "\",\"referralRevision\":4,\"recommendationId\":\""
                            + scope.recommendationId()
                            + "\",\"candidateFacilityId\":\""
                            + scope.destinationFacilityId()
                            + "\",\"transportRequired\":true}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.transportRequired").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();

    UUID journeyId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.journeyId"));
    assertThat(
            jdbc.queryForObject(
                "SELECT transport_required FROM transfer.journey WHERE journey_id = ?",
                Boolean.class,
                journeyId))
        .isTrue();
  }

  @Test
  void omittedTransportRequirementDefaultsToFalse() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());

    String response =
        mockMvc
            .perform(
                post("/api/v1/transfer-journeys")
                    .header("Authorization", "Bearer " + token(SENDER))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                    .header("Idempotency-Key", "transport-default-false-journey")
                    .contentType("application/json")
                    .content(
                        "{\"referralId\":\""
                            + scope.referralId()
                            + "\",\"referralRevision\":4,\"recommendationId\":\""
                            + scope.recommendationId()
                            + "\",\"candidateFacilityId\":\""
                            + scope.destinationFacilityId()
                            + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.transportRequired").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();

    UUID journeyId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.journeyId"));
    assertThat(
            jdbc.queryForObject(
                "SELECT transport_required FROM transfer.journey WHERE journey_id = ?",
                Boolean.class,
                journeyId))
        .isFalse();
  }

  @Test
  void eligibleTransportRequiredTransferCreatesAuditedManualRequestIdempotently() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "transport-request", true);
    grantTransportWorker(scope);

    String response =
        mockMvc
            .perform(
                post("/api/v1/transfers/{transferId}/transport-request", transferId)
                    .header("Authorization", "Bearer " + token(SENDER))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                    .header("Idempotency-Key", "manual-transport-request")
                    .contentType("application/json")
                    .content("{}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(jsonPath("$.coordinationOutcome").value("MANUAL_COORDINATION_REQUIRED"))
            .andExpect(jsonPath("$.transferId").value(transferId))
            .andReturn()
            .getResponse()
            .getContentAsString();

    UUID transportRequestId =
        UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.transportRequestId"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transport.transport_request WHERE transfer_id = ? "
                    + "AND request_status = 'REQUESTED'",
                Integer.class,
                UUID.fromString(transferId)))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT initiated_by_identifier FROM transport.transport_request "
                    + "WHERE transport_request_id = ?",
                String.class,
                transportRequestId))
        .isEqualTo(SENDER);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'TRANSPORT_REQUEST_CREATE' "
                    + "AND target_id = ?",
                Integer.class,
                transportRequestId))
        .isEqualTo(1);

    mockMvc
        .perform(
            post("/api/v1/transfers/{transferId}/transport-request", transferId)
                .header("Authorization", "Bearer " + token(SENDER))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "manual-transport-request")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.transportRequestId").value(transportRequestId.toString()))
        .andExpect(header().string("Idempotency-Replayed", "true"));
  }

  @Test
  void transportRequestFailsClosedWhenTransferDidNotRequireTransport() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "transport-not-required", false);
    grantTransportWorker(scope);

    requestTransport(scope, transferId, "transport-not-required", SENDER)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSPORT_NOT_ELIGIBLE"));

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transport.transport_request WHERE transfer_id = ?",
                Integer.class,
                UUID.fromString(transferId)))
        .isZero();
  }

  @Test
  void transferInitiatorWithoutTransferGrantCannotCreateTransportRequest() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "transport-unauthorized", true);
    grantTransportWorker(scope);

    requestTransport(scope, transferId, "transport-unauthorized", "synthetic-unprivileged")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transport.transport_request WHERE transfer_id = ?",
                Integer.class,
                UUID.fromString(transferId)))
        .isZero();
  }

  @Test
  void activeTransportRequestCannotBeDuplicatedWithAnotherKey() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "transport-active-unique", true);
    grantTransportWorker(scope);

    requestTransport(scope, transferId, "transport-active-first", SENDER)
        .andExpect(status().isCreated());
    requestTransport(scope, transferId, "transport-active-second", SENDER)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSPORT_ACTIVE_REQUEST_EXISTS"));

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transport.transport_request WHERE transfer_id = ? "
                    + "AND request_status = 'REQUESTED'",
                Integer.class,
                UUID.fromString(transferId)))
        .isEqualTo(1);
  }

  @Test
  void auditFailureRollsBackTransportRequestAndIdempotencyReceipt() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "transport-audit-failure", true);
    grantTransportWorker(scope);
    jdbc.execute(
        "CREATE FUNCTION public.fail_transport_request_audit() RETURNS trigger AS $$ "
            + "BEGIN IF NEW.action_code = 'TRANSPORT_REQUEST_CREATE' THEN "
            + "RAISE EXCEPTION 'synthetic transport audit failure'; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql");
    jdbc.execute(
        "CREATE TRIGGER fail_transport_request_audit BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION public.fail_transport_request_audit()");
    try {
      requestTransport(scope, transferId, "transport-audit-failure", SENDER)
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS fail_transport_request_audit ON audit.audit_record");
      jdbc.execute("DROP FUNCTION IF EXISTS public.fail_transport_request_audit()");
    }

    assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM transport.transport_request", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transport.command_receipt", Integer.class))
        .isZero();
  }

  @Test
  void transportMovementEventsUpdateOnlyTransferOwnedDepartureAndArrivalFacts() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "transport-movement-events", true);
    grantTransportWorker(scope);
    requestTransport(scope, transferId, "transport-movement-request", SENDER)
        .andExpect(status().isCreated());
    UUID requestId =
        jdbc.queryForObject(
            "SELECT transport_request_id FROM transport.transport_request WHERE transfer_id = ?",
            UUID.class,
            UUID.fromString(transferId));
    grantTransportAuthority(scope, "synthetic-transport-operator", "TransportStatusAuthority");
    grantTransportAuthority(
        scope, "synthetic-transport-operator", "TransportDispatchCoordinationAuthority");
    grantOwnerEventConsumer(scope, scope.originFacilityId(), "TransferProgressAuthority");

    Instant base = Instant.now().minusSeconds(30);
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "movement-awaiting-assignment",
            "synthetic-transport-operator",
            statusBody("SYN-MOVE-001", "AWAITING_ASSIGNMENT", base))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "assignments",
            "movement-assignment",
            "synthetic-transport-operator",
            "{\"sourceNamespace\":\"urn:ayuvahak:manual\","
                + "\"sourceObservationId\":\"SYN-MOVE-ASSIGNMENT\","
                + "\"assignmentReference\":\"SYN-ASSIGNMENT-10\",\"occurredAt\":\""
                + base.plusSeconds(1)
                + "\"}")
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "movement-en-route-origin",
            "synthetic-transport-operator",
            statusBody("SYN-MOVE-002", "EN_ROUTE_TO_ORIGIN", base.plusSeconds(2)))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "movement-at-origin",
            "synthetic-transport-operator",
            statusBody("SYN-MOVE-003", "AT_ORIGIN", base.plusSeconds(3)))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "movement-patient-onboard",
            "synthetic-transport-operator",
            statusBody("SYN-MOVE-004", "PATIENT_ONBOARD", base.plusSeconds(4)))
        .andExpect(status().isCreated());

    assertThat(countTransportEvents(requestId)).isZero();
    assertThat(transferStatus(transferId)).isEqualTo("READY");

    String departureBody =
        statusBody("SYN-MOVE-005", "EN_ROUTE_TO_DESTINATION", base.plusSeconds(5));
    jdbc.execute(
        "CREATE FUNCTION public.fail_transport_movement_outbox() RETURNS trigger AS $$ "
            + "BEGIN IF NEW.event_type = 'transport.departure-confirmed.v1' THEN "
            + "RAISE EXCEPTION 'synthetic movement outbox failure'; END IF; RETURN NEW; END; "
            + "$$ LANGUAGE plpgsql");
    jdbc.execute(
        "CREATE TRIGGER fail_transport_movement_outbox BEFORE INSERT "
            + "ON platform_eventing.internal_event FOR EACH ROW "
            + "EXECUTE FUNCTION public.fail_transport_movement_outbox()");
    try {
      transportEvidenceCommand(
              scope,
              requestId,
              "status-observations",
              "movement-departure",
              "synthetic-transport-operator",
              departureBody)
          .andExpect(status().isServiceUnavailable());
      assertThat(
              jdbc.queryForObject(
                  "SELECT request_status FROM transport.transport_request "
                      + "WHERE transport_request_id = ?",
                  String.class,
                  requestId))
          .isEqualTo("PATIENT_ONBOARD");
      assertThat(countTransportEvents(requestId)).isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM transport.status_observation WHERE transport_request_id = ? "
                      + "AND source_observation_id = 'SYN-MOVE-005'",
                  Integer.class,
                  requestId))
          .isZero();
    } finally {
      jdbc.execute(
          "DROP TRIGGER IF EXISTS fail_transport_movement_outbox "
              + "ON platform_eventing.internal_event");
      jdbc.execute("DROP FUNCTION IF EXISTS public.fail_transport_movement_outbox()");
    }
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "movement-departure",
            "synthetic-transport-operator",
            departureBody)
        .andExpect(status().isCreated());
    assertThat(eventTypes(requestId)).containsExactly("transport.departure-confirmed.v1");
    assertThat(transferStatus(transferId)).isEqualTo("READY");
    var departureDispatch = outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    assertThat(departureDispatch.delivered())
        .as(
            "departure event must reach the registered Transfer movement consumer: %s",
            departureDispatch)
        .isGreaterThan(0);
    assertThat(transferStatus(transferId)).isEqualTo("IN_TRANSIT");

    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "movement-arrival",
            "synthetic-transport-operator",
            statusBody("SYN-MOVE-006", "ARRIVED_DESTINATION", base.plusSeconds(6)))
        .andExpect(status().isCreated());
    assertThat(eventTypes(requestId))
        .containsExactly("transport.departure-confirmed.v1", "transport.arrival-reported.v1");
    outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    assertThat(transferStatus(transferId)).isEqualTo("ARRIVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.progress_fact WHERE transfer_id = ? "
                    + "AND fact_kind IN ('DEPARTURE', 'ARRIVAL')",
                Integer.class,
                UUID.fromString(transferId)))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.progress_fact WHERE transfer_id = ? "
                    + "AND fact_kind IN ('HANDOVER', 'COMPLETION')",
                Integer.class,
                UUID.fromString(transferId)))
        .isZero();
  }

  @Test
  void futureSkewedTransportArrivalCannotEmitOrAdvanceTransferMovement() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "transport-future-arrival", true);
    grantTransportWorker(scope);
    requestTransport(scope, transferId, "transport-future-arrival-request", SENDER)
        .andExpect(status().isCreated());
    UUID requestId =
        jdbc.queryForObject(
            "SELECT transport_request_id FROM transport.transport_request WHERE transfer_id = ?",
            UUID.class,
            UUID.fromString(transferId));
    grantTransportAuthority(scope, "synthetic-future-arrival-operator", "TransportStatusAuthority");
    grantOwnerEventConsumer(scope, scope.originFacilityId(), "TransferProgressAuthority");

    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "transport-future-arrival",
            "synthetic-future-arrival-operator",
            statusBody(
                "SYN-FUTURE-ARRIVAL-001", "ARRIVED_DESTINATION", Instant.now().plusSeconds(3600)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSPORT_EVIDENCE_STALE"));

    assertThat(countTransportEvents(requestId)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT observation_outcome FROM transport.status_observation "
                    + "WHERE transport_request_id = ? AND source_observation_id = ?",
                String.class,
                requestId,
                "SYN-FUTURE-ARRIVAL-001"))
        .isEqualTo("FUTURE_SKEWED");

    outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    assertThat(transferStatus(transferId)).isEqualTo("READY");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.progress_fact WHERE transfer_id = ? "
                    + "AND fact_kind = 'ARRIVAL'",
                Integer.class,
                UUID.fromString(transferId)))
        .isZero();
  }

  @Test
  void transportArrivalCanReconcileBeforeTransferDepartureDelivery() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "transport-arrival-before-departure", true);
    grantTransportWorker(scope);
    requestTransport(scope, transferId, "transport-arrival-request", SENDER)
        .andExpect(status().isCreated());
    UUID requestId =
        jdbc.queryForObject(
            "SELECT transport_request_id FROM transport.transport_request WHERE transfer_id = ?",
            UUID.class,
            UUID.fromString(transferId));
    String operator = "synthetic-transport-arrival-operator";
    grantTransportAuthority(scope, operator, "TransportStatusAuthority");
    grantTransportAuthority(scope, operator, "TransportDispatchCoordinationAuthority");
    grantOwnerEventConsumer(scope, scope.originFacilityId(), "TransferProgressAuthority");

    Instant base = Instant.now().minusSeconds(30);
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "arrival-awaiting-assignment",
            operator,
            statusBody("SYN-ARRIVAL-001", "AWAITING_ASSIGNMENT", base))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "assignments",
            "arrival-assignment",
            operator,
            "{\"sourceNamespace\":\"urn:ayuvahak:manual\","
                + "\"sourceObservationId\":\"SYN-ARRIVAL-ASSIGNMENT\","
                + "\"assignmentReference\":\"SYN-ASSIGNMENT-ARRIVAL\",\"occurredAt\":\""
                + base.plusSeconds(1)
                + "\"}")
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "arrival-en-route-origin",
            operator,
            statusBody("SYN-ARRIVAL-002", "EN_ROUTE_TO_ORIGIN", base.plusSeconds(2)))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "arrival-at-origin",
            operator,
            statusBody("SYN-ARRIVAL-003", "AT_ORIGIN", base.plusSeconds(3)))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "arrival-patient-onboard",
            operator,
            statusBody("SYN-ARRIVAL-004", "PATIENT_ONBOARD", base.plusSeconds(4)))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "arrival-en-route-destination",
            operator,
            statusBody("SYN-ARRIVAL-005", "EN_ROUTE_TO_DESTINATION", base.plusSeconds(5)))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "arrival-reported",
            operator,
            statusBody("SYN-ARRIVAL-006", "ARRIVED_DESTINATION", base.plusSeconds(6)))
        .andExpect(status().isCreated());

    assertThat(transferStatus(transferId)).isEqualTo("READY");
    InternalEvent arrival =
        transportEventFromOutbox(
            EventType.parse("transport.arrival-reported.v1"), "transport-request/" + requestId);
    transportMovementEventHandler.handle(arrival);

    assertThat(transferStatus(transferId)).isEqualTo("ARRIVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.progress_fact WHERE transfer_id = ? "
                    + "AND fact_kind = 'DEPARTURE'",
                Integer.class,
                UUID.fromString(transferId)))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT exception_code FROM transfer.progress_fact WHERE transfer_id = ? "
                    + "AND fact_kind = 'ARRIVAL'",
                String.class,
                UUID.fromString(transferId)))
        .isEqualTo("ARRIVAL_BEFORE_DEPARTURE_RECORDED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.progress_fact WHERE transfer_id = ? "
                    + "AND fact_kind IN ('HANDOVER', 'COMPLETION')",
                Integer.class,
                UUID.fromString(transferId)))
        .isZero();

    outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    assertThat(transferStatus(transferId)).isEqualTo("ARRIVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.progress_fact WHERE transfer_id = ? "
                    + "AND fact_kind = 'DEPARTURE'",
                Integer.class,
                UUID.fromString(transferId)))
        .isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ACCEPTED",
        "REJECTED",
        "STALE_ACCEPTED",
        "ACCEPTED_RELEASE_UNKNOWN",
        "REJECTED_RELEASE_UNKNOWN",
        "ARRIVAL_BEFORE_ACK",
        "ARRIVAL_AFTER_INTERRUPTION_BEFORE_ACK",
        "OLD_ARRIVAL_EVENT_AFTER_ACK",
        "ARRIVAL_EVENT_BEFORE_RESOLUTION_EVENT",
        "ACCEPTED_NEW_DESTINATION_ARRIVAL"
      })
  void postMovementDestinationChangeKeepsCurrentDestinationUntilTransportAcknowledges(
      String providerOutcome) throws Exception {
    String fixtureSuffix = providerOutcome.toLowerCase(java.util.Locale.ROOT);
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "reroute-pending-" + fixtureSuffix, true);
    grantTransportWorker(scope);
    requestTransport(scope, transferId, "reroute-transport-request", SENDER)
        .andExpect(status().isCreated());
    UUID transportRequestId =
        jdbc.queryForObject(
            "SELECT transport_request_id FROM transport.transport_request WHERE transfer_id = ?",
            UUID.class,
            UUID.fromString(transferId));
    grantTransportAuthority(scope, "synthetic-reroute-operator", "TransportStatusAuthority");
    grantTransportAuthority(
        scope, "synthetic-reroute-operator", "TransportDispatchCoordinationAuthority");
    grantOwnerEventConsumer(scope, scope.originFacilityId(), "TransferProgressAuthority");
    Instant base = Instant.now().minusSeconds(30);
    transportEvidenceCommand(
            scope,
            transportRequestId,
            "status-observations",
            "reroute-awaiting-assignment",
            "synthetic-reroute-operator",
            statusBody("SYN-REROUTE-001", "AWAITING_ASSIGNMENT", base))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            transportRequestId,
            "assignments",
            "reroute-assignment",
            "synthetic-reroute-operator",
            "{\"sourceNamespace\":\"urn:ayuvahak:manual\","
                + "\"sourceObservationId\":\"SYN-REROUTE-ASSIGNMENT\","
                + "\"assignmentReference\":\"SYN-ASSIGNMENT-REROUTE\",\"occurredAt\":\""
                + base.plusMillis(500)
                + "\"}")
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            transportRequestId,
            "status-observations",
            "reroute-en-route-origin",
            "synthetic-reroute-operator",
            statusBody("SYN-REROUTE-002", "EN_ROUTE_TO_ORIGIN", base.plusSeconds(1)))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            transportRequestId,
            "status-observations",
            "reroute-at-origin",
            "synthetic-reroute-operator",
            statusBody("SYN-REROUTE-003", "AT_ORIGIN", base.plusSeconds(2)))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            transportRequestId,
            "status-observations",
            "reroute-onboard",
            "synthetic-reroute-operator",
            statusBody("SYN-REROUTE-004", "PATIENT_ONBOARD", base.plusSeconds(3)))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            transportRequestId,
            "status-observations",
            "reroute-departure",
            "synthetic-reroute-operator",
            statusBody("SYN-REROUTE-005", "EN_ROUTE_TO_DESTINATION", base.plusSeconds(4)))
        .andExpect(status().isCreated());
    var departureDispatch = outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    assertThat(departureDispatch.delivered())
        .as(
            "departure event must reach the registered Transfer movement consumer: %s",
            departureDispatch)
        .isGreaterThan(0);
    assertThat(transferStatus(transferId)).isEqualTo("IN_TRANSIT");

    ReceivingFacility proposed = createReceivingFacility("Synthetic reroute destination");
    Scope proposedScope =
        new Scope(
            proposed.organizationId(),
            scope.originFacilityId(),
            proposed.facilityId(),
            scope.referralId(),
            scope.recommendationId());
    ensureReservableCapacityCatalog();
    grantCapacityReporter(proposedScope);
    grantReservationWorker(proposedScope);
    recordCoordinationSlotObservation(
        proposedScope, 1, "capacity-post-movement-reroute-destination-" + fixtureSuffix);
    grantOriginAuthority(SENDER, "TransferRerouteAuthority", scope);
    matching.returnApplicable(scope.referralId());
    String body =
        "{\"referralRevision\":1,\"recommendationId\":\""
            + scope.recommendationId()
            + "\",\"proposedOrganizationId\":\""
            + proposed.organizationId()
            + "\",\"proposedFacilityId\":\""
            + proposed.facilityId()
            + "\",\"reasonCode\":\"DESTINATION_CAPACITY_CHANGE\"}";
    var first =
        mockMvc
            .perform(
                post("/api/v1/transfers/{transferId}/destination-changes", transferId)
                    .header("Authorization", "Bearer " + token(SENDER))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                    .header("Idempotency-Key", "reroute-create-001")
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("AWAITING_RECEIVER_DECISIONS"))
            .andReturn();
    String operationId =
        com.jayway.jsonpath.JsonPath.read(
            first.getResponse().getContentAsString(), "$.operationId");

    mockMvc
        .perform(
            post("/api/v1/transfers/{transferId}/destination-changes", transferId)
                .header("Authorization", "Bearer " + token(SENDER))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "reroute-create-001")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.operationId").value(operationId));
    assertThat(
            jdbc.queryForObject(
                "SELECT facility_id FROM transfer.active_destination WHERE transfer_id = ?",
                UUID.class,
                UUID.fromString(transferId)))
        .isEqualTo(UUID.fromString(scope.destinationFacilityId()));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.destination_change_operation WHERE transfer_id = ?",
                Integer.class,
                UUID.fromString(transferId)))
        .isEqualTo(1);

    grantReceivingAuthorityAt(
        proposed.organizationId(),
        proposed.facilityId(),
        "synthetic-reroute-clinician",
        "ClinicalSuitabilityAuthority");
    grantReceivingAuthorityAt(
        proposed.organizationId(),
        proposed.facilityId(),
        "synthetic-reroute-readiness",
        "OperationalReadinessAuthority");
    grantReceivingAuthorityAt(
        proposed.organizationId(),
        proposed.facilityId(),
        "synthetic-reroute-acceptor",
        "AcceptanceAuthority");
    postDestinationChangeCommand(
            transferId,
            operationId,
            proposed,
            "clinical-suitability-assessments",
            "synthetic-reroute-clinician",
            "reroute-clinical-assessment",
            "{\"expectedVersion\":1,\"outcome\":\"SUPPORTS\","
                + "\"reasonCode\":\"CLINICAL_REVIEW_COMPLETE\","
                + "\"occurredAt\":\"2026-10-16T10:00:00Z\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.assessmentKind").value("CLINICAL_SUITABILITY"));
    postDestinationChangeCommand(
            transferId,
            operationId,
            proposed,
            "operational-readiness-assessments",
            "synthetic-reroute-readiness",
            "reroute-readiness-assessment",
            "{\"expectedVersion\":1,\"outcome\":\"SUPPORTS\","
                + "\"reasonCode\":\"READINESS_CONFIRMED\","
                + "\"occurredAt\":\"2026-10-16T10:01:00Z\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.assessmentKind").value("OPERATIONAL_READINESS"));
    postDestinationChangeCommand(
            transferId,
            operationId,
            proposed,
            "acceptance",
            "synthetic-reroute-acceptor",
            "reroute-acceptance",
            "{\"expectedVersion\":1,\"occurredAt\":\"2026-10-16T10:02:00Z\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("TRANSPORT_ACK_PENDING"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.destination_change_assessment WHERE operation_id = ?",
                Integer.class,
                UUID.fromString(operationId)))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT capacity_reservation_outcome FROM transfer.destination_change_operation "
                    + "WHERE operation_id = ?",
                String.class,
                UUID.fromString(operationId)))
        .isEqualTo("RESERVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity.coordination_reservation "
                    + "WHERE transfer_id = ? AND reservation_status IN ('ACTIVE', 'AT_RISK')",
                Integer.class,
                UUID.fromString(transferId)))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transport.destination_change_operation "
                    + "WHERE transfer_destination_change_id = ? AND operation_state = 'PENDING'",
                Integer.class,
                UUID.fromString(operationId)))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT facility_id FROM transfer.active_destination WHERE transfer_id = ?",
                UUID.class,
                UUID.fromString(transferId)))
        .isEqualTo(UUID.fromString(scope.destinationFacilityId()));

    UUID transportDestinationChangeId =
        jdbc.queryForObject(
            "SELECT operation_id FROM transport.destination_change_operation "
                + "WHERE transfer_destination_change_id = ?",
            UUID.class,
            UUID.fromString(operationId));
    UUID oldReservationOperationId =
        jdbc.queryForObject(
            "SELECT reservation_operation_id FROM transfer.active_destination WHERE transfer_id = ?",
            UUID.class,
            UUID.fromString(transferId));
    UUID oldHoldReleaseOperationId =
        jdbc.queryForObject(
            "SELECT old_hold_release_operation_id FROM transfer.destination_change_operation "
                + "WHERE operation_id = ?",
            UUID.class,
            UUID.fromString(operationId));
    UUID proposedHoldReleaseOperationId =
        jdbc.queryForObject(
            "SELECT proposed_hold_release_operation_id FROM transfer.destination_change_operation "
                + "WHERE operation_id = ?",
            UUID.class,
            UUID.fromString(operationId));
    String transportOutcome =
        switch (providerOutcome) {
          case "STALE_ACCEPTED",
              "ACCEPTED_RELEASE_UNKNOWN",
              "ARRIVAL_BEFORE_ACK",
              "ARRIVAL_AFTER_INTERRUPTION_BEFORE_ACK",
              "OLD_ARRIVAL_EVENT_AFTER_ACK",
              "ARRIVAL_EVENT_BEFORE_RESOLUTION_EVENT",
              "ACCEPTED_NEW_DESTINATION_ARRIVAL" ->
              "ACCEPTED";
          case "REJECTED_RELEASE_UNKNOWN" -> "REJECTED";
          default -> providerOutcome;
        };
    boolean arrivalBeforeAck =
        providerOutcome.equals("ARRIVAL_BEFORE_ACK")
            || providerOutcome.equals("ARRIVAL_AFTER_INTERRUPTION_BEFORE_ACK");
    boolean oldArrivalEventAfterAck = providerOutcome.equals("OLD_ARRIVAL_EVENT_AFTER_ACK");
    if (arrivalBeforeAck) {
      grantFacilityAuthority("synthetic-reroute-operator", "TransferProgressAuthority", scope);
      long currentTransferVersion =
          jdbc.queryForObject(
              "SELECT version FROM transfer.transfer_record WHERE transfer_id = ?",
              Long.class,
              UUID.fromString(transferId));
      if (providerOutcome.equals("ARRIVAL_AFTER_INTERRUPTION_BEFORE_ACK")) {
        progressCommand(
                scope,
                transferId,
                "interruption",
                "synthetic-reroute-operator",
                "reroute-old-destination-interruption-before-ack",
                "{\"expectedVersion\":"
                    + currentTransferVersion
                    + ",\"occurredAt\":\""
                    + Instant.now()
                    + "\",\"reasonCode\":\"ROUTE_INTERRUPTED\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("INTERRUPTED"));
        currentTransferVersion =
            jdbc.queryForObject(
                "SELECT version FROM transfer.transfer_record WHERE transfer_id = ?",
                Long.class,
                UUID.fromString(transferId));
      }
      var oldDestinationArrival =
          progressCommand(
                  scope,
                  transferId,
                  "arrival",
                  "synthetic-reroute-operator",
                  "reroute-old-destination-arrival-before-ack",
                  "{\"expectedVersion\":"
                      + currentTransferVersion
                      + ",\"occurredAt\":\""
                      + Instant.now()
                      + "\",\"sourceReference\":\"synthetic:old-destination-arrival\"}")
              .andReturn();
      assertThat(oldDestinationArrival.getResponse().getStatus())
          .as(oldDestinationArrival.getResponse().getContentAsString())
          .isEqualTo(201);
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM transfer.destination_change_operation WHERE operation_id = ?",
                  String.class,
                  UUID.fromString(operationId)))
          .as("arrival at the old destination creates an immediately resolvable exception")
          .isEqualTo("RECONCILIATION_REQUIRED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = ? AND target_id = ? "
                      + "AND reason_code = 'OLD_DESTINATION_ARRIVAL_BEFORE_PROVIDER_ACK'",
                  Integer.class,
                  "TRANSFER_DESTINATION_CHANGE_OLD_DESTINATION_ARRIVAL_REQUIRES_RECONCILIATION",
                  UUID.fromString(operationId)))
          .isEqualTo(1);
      long reconciliationVersion =
          jdbc.queryForObject(
              "SELECT version FROM transfer.destination_change_operation WHERE operation_id = ?",
              Long.class,
              UUID.fromString(operationId));
      String reconciliationBody =
          "{\"expectedVersion\":"
              + reconciliationVersion
              + ",\"resolution\":\"RETAIN_OLD_DESTINATION\","
              + "\"evidenceReference\":\"synthetic:old-destination-confirmed-before-ack\","
              + "\"occurredAt\":\""
              + Instant.now()
              + "\"}";
      mockMvc
          .perform(
              post(
                      "/api/v1/transfers/{transferId}/destination-changes/{operationId}/reconciliation",
                      transferId,
                      operationId)
                  .header("Authorization", "Bearer " + token(SENDER))
                  .header("X-AyuVahak-Execution-Scope", "FACILITY")
                  .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                  .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                  .header("Idempotency-Key", "reroute-keep-old-before-provider-ack")
                  .contentType("application/json")
                  .content(reconciliationBody))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.status").value("PROPOSED_HOLD_RELEASE_PENDING"));
      assertThat(destinationChangeRecovery.recoverPendingCapacityReleases(10)).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM transfer.destination_change_operation WHERE operation_id = ?",
                  String.class,
                  UUID.fromString(operationId)))
          .isEqualTo("RESOLVED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT facility_id FROM transfer.active_destination WHERE transfer_id = ?",
                  UUID.class,
                  UUID.fromString(transferId)))
          .isEqualTo(UUID.fromString(scope.destinationFacilityId()));
    }
    UUID oldDestinationArrivalEventId = null;
    if (oldArrivalEventAfterAck) {
      transportEvidenceCommand(
              scope,
              transportRequestId,
              "status-observations",
              "reroute-old-arrival-before-ack-dispatched-late",
              "synthetic-reroute-operator",
              statusBody(
                  "SYN-REROUTE-OLD-ARRIVAL-BEFORE-ACK", "ARRIVED_DESTINATION", base.plusSeconds(5)))
          .andExpect(status().isCreated());
      oldDestinationArrivalEventId =
          jdbc.queryForObject(
              "SELECT event_id FROM platform_eventing.internal_event "
                  + "WHERE event_type = 'transport.arrival-reported.v1' "
                  + "AND envelope #>> '{payload,evidenceReference}' = "
                  + "(SELECT status_observation_id::text FROM transport.status_observation "
                  + "WHERE transport_request_id = ? AND source_observation_id = ?)",
              UUID.class,
              transportRequestId,
              "SYN-REROUTE-OLD-ARRIVAL-BEFORE-ACK");
      assertThat(
              jdbc.queryForObject(
                  "SELECT envelope #>> '{payload,destinationFacilityReference}' "
                      + "FROM platform_eventing.internal_event WHERE event_id = ?",
                  String.class,
                  oldDestinationArrivalEventId))
          .isEqualTo(scope.destinationFacilityId());
    }
    grantTransportAuthority(
        scope, "synthetic-reroute-operator", "TransportDestinationChangeAuthority");
    mockMvc
        .perform(
            post(
                    "/api/v1/transport-destination-change-operations/{operationId}/outcome",
                    transportDestinationChangeId)
                .header("Authorization", "Bearer " + token("synthetic-reroute-operator"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .contentType("application/json")
                .content(
                    "{\"outcome\":\""
                        + transportOutcome
                        + "\","
                        + "\"sourceNamespace\":\"urn:ayuvahak:manual\","
                        + "\"sourceObservationId\":\"SYN-REROUTE-ACK-001\","
                        + "\"occurredAt\":\""
                        + Instant.now()
                        + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.outcome").value(transportOutcome));
    if (oldArrivalEventAfterAck) {
      UUID resolutionEventId =
          jdbc.queryForObject(
              "SELECT resolution_event_id FROM transport.destination_change_operation "
                  + "WHERE operation_id = ?",
              UUID.class,
              transportDestinationChangeId);
      destinationChangeRecovery.handle(internalEvent(resolutionEventId));
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM transfer.destination_change_operation WHERE operation_id = ?",
                  String.class,
                  UUID.fromString(operationId)))
          .as("an earlier old-destination arrival prevents applying a later transport ACK")
          .isEqualTo("RECONCILIATION_REQUIRED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT facility_id FROM transfer.active_destination WHERE transfer_id = ?",
                  UUID.class,
                  UUID.fromString(transferId)))
          .isEqualTo(UUID.fromString(scope.destinationFacilityId()));
      transportMovementEventHandler.handle(internalEvent(oldDestinationArrivalEventId));
      assertThat(transferStatus(transferId)).isEqualTo("ARRIVED");
      long reconciliationVersion =
          jdbc.queryForObject(
              "SELECT version FROM transfer.destination_change_operation WHERE operation_id = ?",
              Long.class,
              UUID.fromString(operationId));
      mockMvc
          .perform(
              post(
                      "/api/v1/transfers/{transferId}/destination-changes/{operationId}/reconciliation",
                      transferId,
                      operationId)
                  .header("Authorization", "Bearer " + token(SENDER))
                  .header("X-AyuVahak-Execution-Scope", "FACILITY")
                  .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                  .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                  .header("Idempotency-Key", "reroute-old-arrival-before-ack-resolution")
                  .contentType("application/json")
                  .content(
                      "{\"expectedVersion\":"
                          + reconciliationVersion
                          + ",\"resolution\":\"RETAIN_OLD_DESTINATION\","
                          + "\"evidenceReference\":\"synthetic:old-arrival-before-ack\","
                          + "\"occurredAt\":\""
                          + Instant.now()
                          + "\"}"))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.status").value("PROPOSED_HOLD_RELEASE_PENDING"));
      assertThat(destinationChangeRecovery.recoverPendingCapacityReleases(10)).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM transfer.destination_change_operation WHERE operation_id = ?",
                  String.class,
                  UUID.fromString(operationId)))
          .isEqualTo("RESOLVED");
    }
    if (providerOutcome.equals("ARRIVAL_EVENT_BEFORE_RESOLUTION_EVENT")) {
      transportEvidenceCommand(
              scope,
              transportRequestId,
              "status-observations",
              "reroute-arrival-event-before-resolution",
              "synthetic-reroute-operator",
              statusBody(
                  "SYN-REROUTE-ARRIVAL-BEFORE-RESOLUTION",
                  "ARRIVED_DESTINATION",
                  base.plusSeconds(5)))
          .andExpect(status().isCreated());
      UUID arrivalEventId =
          jdbc.queryForObject(
              "SELECT event_id FROM platform_eventing.internal_event "
                  + "WHERE event_type = 'transport.arrival-reported.v1' "
                  + "AND envelope #>> '{payload,transportRequestReference}' = ?",
              UUID.class,
              transportRequestId.toString());
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM transfer.destination_change_operation WHERE operation_id = ?",
                  String.class,
                  UUID.fromString(operationId)))
          .isEqualTo("TRANSPORT_ACK_PENDING");
      assertThat(
              jdbc.queryForObject(
                  "SELECT envelope::text FROM platform_eventing.internal_event WHERE event_id = ?",
                  String.class,
                  arrivalEventId))
          .contains("\"destinationFacilityReference\": \"" + proposed.facilityId() + "\"");
      assertThatThrownBy(() -> transportMovementEventHandler.handle(internalEvent(arrivalEventId)))
          .isInstanceOf(IllegalStateException.class);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM transfer.consumer_event_receipt WHERE event_id = ?",
                  Integer.class,
                  arrivalEventId))
          .isZero();
      UUID reorderedResolutionEventId =
          jdbc.queryForObject(
              "SELECT resolution_event_id FROM transport.destination_change_operation "
                  + "WHERE operation_id = ?",
              UUID.class,
              transportDestinationChangeId);
      destinationChangeRecovery.handle(internalEvent(reorderedResolutionEventId));
      transportMovementEventHandler.handle(internalEvent(arrivalEventId));
      assertThat(transferStatus(transferId)).isEqualTo("ARRIVED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT facility_id FROM transfer.active_destination WHERE transfer_id = ?",
                  UUID.class,
                  UUID.fromString(transferId)))
          .isEqualTo(UUID.fromString(proposed.facilityId()));
    }
    if (providerOutcome.equals("STALE_ACCEPTED")) {
      matching.returnStatus(RecommendationApplicabilityResult.Status.NOT_APPLICABLE);
    }
    boolean loseCapacityReleaseResponse = providerOutcome.endsWith("_RELEASE_UNKNOWN");
    if (loseCapacityReleaseResponse) {
      capacityPort.loseNextReleaseResponseAfterCommit();
    }
    outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    UUID resolutionEventId =
        jdbc.queryForObject(
            "SELECT resolution_event_id FROM transport.destination_change_operation "
                + "WHERE operation_id = ?",
            UUID.class,
            transportDestinationChangeId);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM platform_eventing.outbox_delivery WHERE event_id = ?",
                String.class,
                resolutionEventId))
        .as("Transport's destination-change outcome is consumed by Transfer")
        .isEqualTo("DELIVERED");
    if (loseCapacityReleaseResponse) {
      String pendingStatus =
          transportOutcome.equals("ACCEPTED")
              ? "OLD_HOLD_RELEASE_PENDING"
              : "PROPOSED_HOLD_RELEASE_PENDING";
      UUID releaseOperationId =
          transportOutcome.equals("ACCEPTED")
              ? oldHoldReleaseOperationId
              : proposedHoldReleaseOperationId;
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM transfer.destination_change_operation WHERE operation_id = ?",
                  String.class,
                  UUID.fromString(operationId)))
          .isEqualTo(pendingStatus);
      assertThat(
              jdbc.queryForObject(
                  "SELECT facility_id FROM transfer.active_destination WHERE transfer_id = ?",
                  UUID.class,
                  UUID.fromString(transferId)))
          .isEqualTo(
              UUID.fromString(
                  transportOutcome.equals("ACCEPTED")
                      ? proposed.facilityId()
                      : scope.destinationFacilityId()));
      assertThat(capacityPort.releaseOperationIds()).containsExactly(releaseOperationId);
      mockMvc
          .perform(
              post("/api/v1/transfers/{transferId}/destination-changes", transferId)
                  .header("Authorization", "Bearer " + token(SENDER))
                  .header("X-AyuVahak-Execution-Scope", "FACILITY")
                  .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                  .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                  .header("Idempotency-Key", "reroute-blocked-until-release-recovery")
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("TRANSFER_STATE_CONFLICT"));
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM transfer.destination_change_operation WHERE transfer_id = ?",
                  Integer.class,
                  UUID.fromString(transferId)))
          .isEqualTo(1);
      assertThat(destinationChangeRecovery.recoverPendingCapacityReleases(10)).isEqualTo(1);
      assertThat(capacityPort.releaseOperationIds())
          .containsExactly(releaseOperationId, releaseOperationId);
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM transfer.destination_change_operation WHERE operation_id = ?",
                  String.class,
                  UUID.fromString(operationId)))
          .isEqualTo("RESOLVED");
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.consumer_event_receipt WHERE event_id = ?",
                String.class,
                resolutionEventId))
        .as("Transfer records the destination-change consumer result")
        .isEqualTo(
            providerOutcome.equals("STALE_ACCEPTED")
                ? "ACCEPTED_DESTINATION_CHANGE_NO_LONGER_APPLICABLE"
                : oldArrivalEventAfterAck
                    ? "ACCEPTED_DESTINATION_CHANGE_NO_LONGER_APPLICABLE"
                    : arrivalBeforeAck
                        ? "LATE_PROVIDER_OUTCOME_AFTER_RECONCILIATION"
                        : "TRANSPORT_DESTINATION_CHANGE_" + transportOutcome);
    if (arrivalBeforeAck) {
      assertThat(transferStatus(transferId)).isEqualTo("ARRIVED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM transfer.destination_change_operation WHERE operation_id = ?",
                  String.class,
                  UUID.fromString(operationId)))
          .isEqualTo("RESOLVED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT facility_id FROM transfer.active_destination WHERE transfer_id = ?",
                  UUID.class,
                  UUID.fromString(transferId)))
          .isEqualTo(UUID.fromString(scope.destinationFacilityId()));
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM capacity.coordination_reservation "
                      + "WHERE transfer_id = ? AND reservation_status IN ('ACTIVE', 'AT_RISK')",
                  Integer.class,
                  UUID.fromString(transferId)))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM transfer.destination_change_reconciliation "
                      + "WHERE operation_id = ?",
                  Integer.class,
                  UUID.fromString(operationId)))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM capacity.coordination_reservation "
                      + "WHERE transfer_id = ? AND reservation_status IN ('ACTIVE', 'AT_RISK')",
                  Integer.class,
                  UUID.fromString(transferId)))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT provider_outcome FROM transfer.destination_change_operation "
                      + "WHERE operation_id = ?",
                  String.class,
                  UUID.fromString(operationId)))
          .isNull();
      return;
    }
    if (oldArrivalEventAfterAck) {
      assertThat(transferStatus(transferId)).isEqualTo("ARRIVED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM transfer.destination_change_operation WHERE operation_id = ?",
                  String.class,
                  UUID.fromString(operationId)))
          .isEqualTo("RESOLVED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT facility_id FROM transfer.active_destination WHERE transfer_id = ?",
                  UUID.class,
                  UUID.fromString(transferId)))
          .isEqualTo(UUID.fromString(scope.destinationFacilityId()));
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM capacity.coordination_reservation "
                      + "WHERE transfer_id = ? AND reservation_status IN ('ACTIVE', 'AT_RISK')",
                  Integer.class,
                  UUID.fromString(transferId)))
          .isEqualTo(1);
      return;
    }
    if (providerOutcome.equals("STALE_ACCEPTED")) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM transfer.destination_change_operation WHERE operation_id = ?",
                  String.class,
                  UUID.fromString(operationId)))
          .isEqualTo("RECONCILIATION_REQUIRED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT facility_id FROM transfer.active_destination WHERE transfer_id = ?",
                  UUID.class,
                  UUID.fromString(transferId)))
          .isEqualTo(UUID.fromString(scope.destinationFacilityId()));
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM capacity.coordination_reservation "
                      + "WHERE transfer_id = ? AND reservation_status IN ('ACTIVE', 'AT_RISK')",
                  Integer.class,
                  UUID.fromString(transferId)))
          .isEqualTo(2);
      assertThat(
              jdbc.queryForObject(
                  "SELECT reservation_status FROM capacity.coordination_reservation "
                      + "WHERE operation_id = ?",
                  String.class,
                  oldReservationOperationId))
          .isEqualTo("ACTIVE");
      assertThat(
              jdbc.queryForObject(
                  "SELECT reservation_status FROM capacity.coordination_reservation "
                      + "WHERE operation_id = (SELECT capacity_reservation_operation_id "
                      + "FROM transfer.destination_change_operation WHERE operation_id = ?)",
                  String.class,
                  UUID.fromString(operationId)))
          .isEqualTo("ACTIVE");
      return;
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM transfer.destination_change_operation WHERE operation_id = ?",
                String.class,
                UUID.fromString(operationId)))
        .as("Transfer's reroute outcome is reconciled")
        .isIn("OLD_HOLD_RELEASE_PENDING", "RESOLVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT facility_id FROM transfer.active_destination WHERE transfer_id = ?",
                UUID.class,
                UUID.fromString(transferId)))
        .isEqualTo(
            UUID.fromString(
                transportOutcome.equals("ACCEPTED")
                    ? proposed.facilityId()
                    : scope.destinationFacilityId()));
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM transfer.destination_change_operation WHERE operation_id = ?",
                String.class,
                UUID.fromString(operationId)))
        .isEqualTo("RESOLVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE operation_id = ?",
                String.class,
                jdbc.queryForObject(
                    "SELECT reservation_operation_id FROM transfer.active_destination "
                        + "WHERE transfer_id = ?",
                    UUID.class,
                    UUID.fromString(transferId))))
        .isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE operation_id = ?",
                String.class,
                oldReservationOperationId))
        .isEqualTo(transportOutcome.equals("ACCEPTED") ? "RELEASED" : "ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE operation_id = ?",
                String.class,
                jdbc.queryForObject(
                    "SELECT capacity_reservation_operation_id FROM transfer.destination_change_operation "
                        + "WHERE operation_id = ?",
                    UUID.class,
                    UUID.fromString(operationId))))
        .isEqualTo(transportOutcome.equals("ACCEPTED") ? "ACTIVE" : "RELEASED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity.coordination_reservation "
                    + "WHERE transfer_id = ? AND reservation_status IN ('ACTIVE', 'AT_RISK')",
                Integer.class,
                UUID.fromString(transferId)))
        .isEqualTo(1);
    if (providerOutcome.equals("ACCEPTED_NEW_DESTINATION_ARRIVAL")) {
      grantTransportAuthority(
          scope, "synthetic-reroute-operator", "TransportCancellationAuthority");
      var cancellation =
          mockMvc
              .perform(
                  post(
                          "/api/v1/transport-requests/{requestId}/cancellation-operations",
                          transportRequestId)
                      .header("Authorization", "Bearer " + token("synthetic-reroute-operator"))
                      .header("X-AyuVahak-Execution-Scope", "FACILITY")
                      .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                      .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                      .header("Idempotency-Key", "reroute-cancellation-create")
                      .contentType("application/json")
                      .content("{\"reasonCode\":\"PROVIDER_CANCELLATION_RECONCILIATION\"}"))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.outcome").value("MOVEMENT_ALREADY_STARTED"))
              .andReturn();
      String cancellationOperationId =
          com.jayway.jsonpath.JsonPath.read(
              cancellation.getResponse().getContentAsString(), "$.cancellationOperationId");
      outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
      assertThat(
              jdbc.queryForObject(
                  "SELECT outcome FROM transfer.consumer_event_receipt WHERE event_id = "
                      + "(SELECT resolution_event_id FROM transport.cancellation_operation "
                      + "WHERE cancellation_operation_id = ?)",
                  String.class,
                  UUID.fromString(cancellationOperationId)))
          .isEqualTo("TRANSPORT_CANCELLATION_MOVEMENT_ALREADY_STARTED");

      transportEvidenceCommand(
              scope,
              transportRequestId,
              "status-observations",
              "reroute-arrival-at-accepted-destination",
              "synthetic-reroute-operator",
              statusBody(
                  "SYN-REROUTE-NEW-DESTINATION-ARRIVAL",
                  "ARRIVED_DESTINATION",
                  base.plusSeconds(5)))
          .andExpect(status().isCreated());
      var arrivalDispatch = outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
      assertThat(arrivalDispatch.delivered())
          .as("the Transport event for the accepted destination must reach Transfer")
          .isGreaterThan(0);
      assertThat(transferStatus(transferId)).isEqualTo("ARRIVED");
    }
  }

  @Test
  void transportCancellationIsIdempotentPreservesLateAssignmentAndBlocksReplacement()
      throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "transport-cancellation-operation", true);
    grantTransportWorker(scope);
    requestTransport(scope, transferId, "transport-cancellation-request", SENDER)
        .andExpect(status().isCreated());
    UUID requestId =
        jdbc.queryForObject(
            "SELECT transport_request_id FROM transport.transport_request WHERE transfer_id = ?",
            UUID.class,
            UUID.fromString(transferId));
    String operator = "synthetic-transport-cancellation-operator";
    grantTransportAuthority(scope, operator, "TransportCancellationAuthority");
    grantTransportAuthority(scope, operator, "TransportDispatchCoordinationAuthority");
    grantTransportAuthority(scope, operator, "TransportStatusAuthority");
    grantOwnerEventConsumer(scope, scope.originFacilityId(), "TransferProgressAuthority");
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "transport-cancellation-awaiting-assignment",
            operator,
            "{\"observationId\":\"SYN-CANCEL-AWAITING-001\","
                + "\"sourceNamespace\":\"urn:ayuvahak:manual\","
                + "\"status\":\"AWAITING_ASSIGNMENT\",\"occurredAt\":\""
                + Instant.now().minusSeconds(15)
                + "\",\"reasonCode\":\"DISPATCH_COORDINATION_STARTED\"}")
        .andExpect(status().isCreated());

    String cancellationUrl = "/api/v1/transport-requests/" + requestId + "/cancellation-operations";
    var cancellation =
        mockMvc
            .perform(
                post(cancellationUrl)
                    .header("Authorization", "Bearer " + token(operator))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                    .header("Idempotency-Key", "transport-cancellation-create")
                    .contentType("application/json")
                    .content("{\"reasonCode\":\"TRANSFER_CANCELLED\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.state").value("PENDING"))
            .andReturn();
    String operationId =
        com.jayway.jsonpath.JsonPath.read(
            cancellation.getResponse().getContentAsString(), "$.cancellationOperationId");
    mockMvc
        .perform(
            post("/api/v1/transport-cancellation-operations/{operationId}/outcome", operationId)
                .header("Authorization", "Bearer " + serviceToken(TRANSPORT_WORKER))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "transport-cancellation-service-bypass")
                .contentType("application/json")
                .content(
                    "{\"outcome\":\"CANCELLED_BEFORE_MOVEMENT\","
                        + "\"sourceNamespace\":\"urn:ayuvahak:manual\","
                        + "\"sourceObservationId\":\"SYN-CANCEL-SERVICE-BYPASS\","
                        + "\"occurredAt\":\""
                        + Instant.now().minusSeconds(1)
                        + "\"}"))
        .andExpect(status().isForbidden());
    assertThat(
            jdbc.queryForObject(
                "SELECT operation_state FROM transport.cancellation_operation "
                    + "WHERE cancellation_operation_id = ?",
                String.class,
                UUID.fromString(operationId)))
        .isEqualTo("PENDING");
    mockMvc
        .perform(
            post(cancellationUrl)
                .header("Authorization", "Bearer " + token(operator))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "transport-cancellation-create")
                .contentType("application/json")
                .content("{\"reasonCode\":\"TRANSFER_CANCELLED\"}"))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.cancellationOperationId").value(operationId));

    Instant assignmentAt = Instant.now().minusSeconds(10);
    transportEvidenceCommand(
            scope,
            requestId,
            "assignments",
            "cancellation-late-assignment",
            operator,
            "{\"sourceNamespace\":\"urn:ayuvahak:manual\","
                + "\"sourceObservationId\":\"SYN-CANCEL-LATE-ASSIGNMENT\","
                + "\"assignmentReference\":\"SYN-ASSIGNMENT-CANCEL-RACE\",\"occurredAt\":\""
                + assignmentAt
                + "\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.requestStatus").value("ASSIGNED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT operation_state FROM transport.cancellation_operation "
                    + "WHERE cancellation_operation_id = ?",
                String.class,
                UUID.fromString(operationId)))
        .isEqualTo("PENDING");

    requestTransport(scope, transferId, "transport-cancellation-replacement-before-outcome", SENDER)
        .andExpect(status().isConflict());

    Instant resolvedAt = Instant.now().minusSeconds(1);
    String outcomeUrl = "/api/v1/transport-cancellation-operations/" + operationId + "/outcome";
    String outcomeBody =
        "{\"outcome\":\"CANCELLED_BEFORE_MOVEMENT\","
            + "\"sourceNamespace\":\"urn:ayuvahak:manual\","
            + "\"sourceObservationId\":\"SYN-CANCEL-OUTCOME-001\","
            + "\"occurredAt\":\""
            + resolvedAt
            + "\"}";
    mockMvc
        .perform(
            post(outcomeUrl)
                .header("Authorization", "Bearer " + token(operator))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "transport-cancellation-resolve")
                .contentType("application/json")
                .content(outcomeBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.outcome").value("CANCELLED_BEFORE_MOVEMENT"));

    assertThat(
            jdbc.queryForObject(
                "SELECT request_status FROM transport.transport_request "
                    + "WHERE transport_request_id = ?",
                String.class,
                requestId))
        .isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_eventing.internal_event "
                    + "WHERE event_type = 'transport.cancellation-resolved.v1' "
                    + "AND envelope #>> '{payload,transportRequestReference}' = ?",
                Integer.class,
                requestId.toString()))
        .isEqualTo(1);
    outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    assertThat(transferStatus(transferId)).isEqualTo("READY");

    transportEvidenceCommand(
            scope,
            requestId,
            "assignments",
            "cancellation-assignment-after-confirmed-cancel",
            operator,
            "{\"sourceNamespace\":\"urn:ayuvahak:manual\","
                + "\"sourceObservationId\":\"SYN-CANCEL-LATE-ASSIGNMENT-2\","
                + "\"assignmentReference\":\"SYN-ASSIGNMENT-AFTER-CANCEL\",\"occurredAt\":\""
                + Instant.now().minusSeconds(1)
                + "\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.requestStatus").value("CANCELLED"))
        .andExpect(jsonPath("$.evidenceOutcome").value("RECONCILIATION_REQUIRED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT observation_outcome FROM transport.assignment_observation "
                    + "WHERE source_observation_id = 'SYN-CANCEL-LATE-ASSIGNMENT-2'",
                String.class))
        .isEqualTo("RECONCILIATION_REQUIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT request_status FROM transport.transport_request "
                    + "WHERE transport_request_id = ?",
                String.class,
                requestId))
        .isEqualTo("CANCELLED");

    requestTransport(scope, transferId, "transport-cancellation-replacement-after-outcome", SENDER)
        .andExpect(status().isCreated());
  }

  @Test
  void concurrentTransportCancellationCommandsCreateOneDurableOperation() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "transport-cancellation-concurrent", true);
    grantTransportWorker(scope);
    requestTransport(scope, transferId, "transport-cancellation-concurrent-request", SENDER)
        .andExpect(status().isCreated());
    UUID requestId =
        jdbc.queryForObject(
            "SELECT transport_request_id FROM transport.transport_request WHERE transfer_id = ?",
            UUID.class,
            UUID.fromString(transferId));
    String operator = "synthetic-transport-cancellation-concurrent-operator";
    grantTransportAuthority(scope, operator, "TransportCancellationAuthority");
    String requestUrl = "/api/v1/transport-requests/" + requestId + "/cancellation-operations";
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (Connection barrierConnection = dataSource.getConnection()) {
      barrierConnection.setAutoCommit(false);
      try (var lock =
          barrierConnection.prepareStatement(
              "SELECT transport_request_id FROM transport.transport_request "
                  + "WHERE transport_request_id = ? FOR UPDATE")) {
        lock.setObject(1, requestId);
        try (var ignored = lock.executeQuery()) {
          assertThat(ignored.next()).isTrue();
        }
      }
      Future<MvcResult> first =
          cancellationRequest(
              executor,
              ready,
              start,
              scope,
              requestUrl,
              operator,
              "transport-cancellation-concurrent-first");
      Future<MvcResult> second =
          cancellationRequest(
              executor,
              ready,
              start,
              scope,
              requestUrl,
              operator,
              "transport-cancellation-concurrent-second");
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      awaitTransportRequestLockWaiters(2);
      barrierConnection.commit();

      MvcResult firstResult = first.get(15, TimeUnit.SECONDS);
      MvcResult secondResult = second.get(15, TimeUnit.SECONDS);
      assertThat(
              java.util.List.of(
                  firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()))
          .containsExactlyInAnyOrder(202, 409);
      MvcResult accepted =
          firstResult.getResponse().getStatus() == 202 ? firstResult : secondResult;
      String firstOperation =
          com.jayway.jsonpath.JsonPath.read(
              accepted.getResponse().getContentAsString(), "$.cancellationOperationId");
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM transport.cancellation_operation "
                      + "WHERE transport_request_id = ?",
                  Integer.class,
                  requestId))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM transport.cancellation_command_receipt", Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM audit.audit_record "
                      + "WHERE action_code = 'TRANSPORT_CANCELLATION_REQUESTED' "
                      + "AND target_id = ?",
                  Integer.class,
                  UUID.fromString(firstOperation)))
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void transferCancellationRecoveryQueriesTransportOutcomeWhenEventDeliveryIsDelayed()
      throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "transport-cancellation-query-recovery", true);
    grantTransportWorker(scope);
    grantFacilityAuthority("synthetic-transfer-mover", "TransferProgressAuthority", scope);
    grantOwnerEventConsumer(scope, scope.originFacilityId(), "TransferProgressAuthority");
    requestTransport(scope, transferId, "transport-cancellation-query-request", SENDER)
        .andExpect(status().isCreated());
    UUID transferUuid = UUID.fromString(transferId);
    UUID requestId =
        jdbc.queryForObject(
            "SELECT transport_request_id FROM transport.transport_request WHERE transfer_id = ?",
            UUID.class,
            transferUuid);

    progressCommand(
            scope,
            transferId,
            "cancellation",
            "synthetic-transfer-mover",
            "transport-cancellation-query-transfer-cancel",
            "{\"expectedVersion\":2,\"occurredAt\":\""
                + Instant.now().minusSeconds(4)
                + "\",\"reasonCode\":\"ROUTE_CANCELLED\"}")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("CANCELLATION_PENDING_RELEASE"));

    String operationId =
        jdbc.queryForObject(
            "SELECT cancellation_operation_id FROM transport.cancellation_operation "
                + "WHERE transport_request_id = ?",
            String.class,
            requestId);
    String operator = "synthetic-transport-cancellation-query-operator";
    grantTransportAuthority(scope, operator, "TransportCancellationAuthority");
    mockMvc
        .perform(
            post("/api/v1/transport-cancellation-operations/{operationId}/uncertainty", operationId)
                .header("Authorization", "Bearer " + token(operator))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "transport-cancellation-query-unknown")
                .contentType("application/json")
                .content("{\"state\":\"OUTCOME_UNKNOWN\",\"reasonCode\":\"RESPONSE_LOST\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.state").value("OUTCOME_UNKNOWN"));

    reservationRecovery.reconcileCancellation(InternalId.parse(transferId));
    assertThat(transferStatus(transferId)).isEqualTo("CANCELLATION_PENDING_RELEASE");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("ACTIVE");

    mockMvc
        .perform(
            post("/api/v1/transport-cancellation-operations/{operationId}/uncertainty", operationId)
                .header("Authorization", "Bearer " + token(operator))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "transport-cancellation-query-reconciliation")
                .contentType("application/json")
                .content(
                    "{\"state\":\"RECONCILIATION_REQUIRED\","
                        + "\"reasonCode\":\"MANUAL_RECONCILIATION_REQUIRED\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.state").value("RECONCILIATION_REQUIRED"));

    reservationRecovery.reconcileCancellation(InternalId.parse(transferId));
    assertThat(transferStatus(transferId)).isEqualTo("CANCELLATION_PENDING_RELEASE");

    mockMvc
        .perform(
            post("/api/v1/transport-cancellation-operations/{operationId}/outcome", operationId)
                .header("Authorization", "Bearer " + token(operator))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "transport-cancellation-query-resolve")
                .contentType("application/json")
                .content(
                    "{\"outcome\":\"CANCELLED_BEFORE_MOVEMENT\","
                        + "\"sourceNamespace\":\"urn:ayuvahak:manual\","
                        + "\"sourceObservationId\":\"SYN-CANCEL-QUERY-OUTCOME\","
                        + "\"occurredAt\":\""
                        + Instant.now().minusSeconds(1)
                        + "\"}"))
        .andExpect(status().isCreated());

    assertThat(transferStatus(transferId)).isEqualTo("CANCELLATION_PENDING_RELEASE");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("ACTIVE");

    reservationRecovery.reconcileCancellation(InternalId.parse(transferId));

    assertThat(transferStatus(transferId)).isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("RELEASED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.transport_cancellation_outcome "
                    + "WHERE transfer_id = ? AND outcome = 'CANCELLED_BEFORE_MOVEMENT'",
                Integer.class,
                transferUuid))
        .isEqualTo(1);

    outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    assertThat(transferStatus(transferId)).isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.progress_fact WHERE transfer_id = ? "
                    + "AND fact_kind = 'CANCELLATION_RELEASE'",
                Integer.class,
                transferUuid))
        .isEqualTo(1);
  }

  @Test
  void departureEvidenceRemainsAuthoritativeWhileTransferCancellationIsPending() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "transport-cancel-departure-race", true);
    grantTransportWorker(scope);
    requestTransport(scope, transferId, "transport-cancel-departure-request", SENDER)
        .andExpect(status().isCreated());
    UUID transferUuid = UUID.fromString(transferId);
    UUID requestId =
        jdbc.queryForObject(
            "SELECT transport_request_id FROM transport.transport_request WHERE transfer_id = ?",
            UUID.class,
            transferUuid);
    String operator = "synthetic-transport-cancel-departure-operator";
    grantTransportAuthority(scope, operator, "TransportStatusAuthority");
    grantTransportAuthority(scope, operator, "TransportDispatchCoordinationAuthority");
    grantTransportAuthority(scope, operator, "TransportCancellationAuthority");
    grantFacilityAuthority("synthetic-transfer-mover", "TransferProgressAuthority", scope);
    grantOwnerEventConsumer(scope, scope.originFacilityId(), "TransferProgressAuthority");

    Instant base = Instant.now().minusSeconds(60);
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "cancel-departure-awaiting-assignment",
            operator,
            statusBody("SYN-CANCEL-DEPARTURE-001", "AWAITING_ASSIGNMENT", base))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "assignments",
            "cancel-departure-assignment",
            operator,
            "{\"sourceNamespace\":\"urn:ayuvahak:manual\","
                + "\"sourceObservationId\":\"SYN-CANCEL-DEPARTURE-ASSIGNMENT\","
                + "\"assignmentReference\":\"SYN-ASSIGNMENT-CANCEL-DEPARTURE\","
                + "\"occurredAt\":\""
                + base.plusSeconds(1)
                + "\"}")
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "cancel-departure-en-route-origin",
            operator,
            statusBody("SYN-CANCEL-DEPARTURE-002", "EN_ROUTE_TO_ORIGIN", base.plusSeconds(2)))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "cancel-departure-at-origin",
            operator,
            statusBody("SYN-CANCEL-DEPARTURE-003", "AT_ORIGIN", base.plusSeconds(3)))
        .andExpect(status().isCreated());
    progressCommand(
            scope,
            transferId,
            "cancellation",
            "synthetic-transfer-mover",
            "transport-cancel-departure-transfer-cancel",
            "{\"expectedVersion\":2,\"occurredAt\":\""
                + base.plusSeconds(5)
                + "\",\"reasonCode\":\"ROUTE_CANCELLED\"}")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("CANCELLATION_PENDING_RELEASE"));
    assertThat(transferStatus(transferId)).isEqualTo("CANCELLATION_PENDING_RELEASE");
    assertThat(
            jdbc.queryForObject(
                "SELECT operation_state FROM transport.cancellation_operation "
                    + "WHERE transport_request_id = ?",
                String.class,
                requestId))
        .isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("ACTIVE");

    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "cancel-departure-patient-onboard",
            operator,
            statusBody("SYN-CANCEL-DEPARTURE-004", "PATIENT_ONBOARD", base.plusSeconds(6)))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "cancel-departure-in-transit",
            operator,
            statusBody("SYN-CANCEL-DEPARTURE-005", "EN_ROUTE_TO_DESTINATION", base.plusSeconds(7)))
        .andExpect(status().isCreated());
    String operationId =
        jdbc.queryForObject(
            "SELECT cancellation_operation_id FROM transport.cancellation_operation "
                + "WHERE transport_request_id = ?",
            String.class,
            requestId);
    mockMvc
        .perform(
            post("/api/v1/transport-cancellation-operations/{operationId}/outcome", operationId)
                .header("Authorization", "Bearer " + token(operator))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "transport-cancel-departure-resolve")
                .contentType("application/json")
                .content(
                    "{\"outcome\":\"MOVEMENT_ALREADY_STARTED\","
                        + "\"sourceNamespace\":\"urn:ayuvahak:manual\","
                        + "\"sourceObservationId\":\"SYN-CANCEL-DEPARTURE-OUTCOME\","
                        + "\"occurredAt\":\""
                        + base.plusSeconds(8)
                        + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.outcome").value("MOVEMENT_ALREADY_STARTED"));

    outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));

    assertThat(transferStatus(transferId)).isEqualTo("IN_TRANSIT");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.progress_fact WHERE transfer_id = ? "
                    + "AND fact_kind = 'DEPARTURE'",
                Integer.class,
                transferUuid))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.transport_cancellation_outcome "
                    + "WHERE transport_request_id = ?",
                String.class,
                requestId))
        .isEqualTo("MOVEMENT_ALREADY_STARTED");
  }

  @Test
  void transportInterruptionUpdatesTransferAndUnfulfilledRemainsAnExplicitException()
      throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "transport-interruption-events", true);
    grantTransportWorker(scope);
    requestTransport(scope, transferId, "transport-interruption-request", SENDER)
        .andExpect(status().isCreated());
    UUID requestId =
        jdbc.queryForObject(
            "SELECT transport_request_id FROM transport.transport_request WHERE transfer_id = ?",
            UUID.class,
            UUID.fromString(transferId));
    String operator = "synthetic-transport-operator";
    grantTransportAuthority(scope, operator, "TransportStatusAuthority");
    grantTransportAuthority(scope, operator, "TransportDispatchCoordinationAuthority");
    grantOwnerEventConsumer(scope, scope.originFacilityId(), "TransferProgressAuthority");

    Instant base = Instant.now().minusSeconds(30);
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "interruption-awaiting-assignment",
            operator,
            statusBody("SYN-INTERRUPT-001", "AWAITING_ASSIGNMENT", base))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "assignments",
            "interruption-assignment",
            operator,
            "{\"sourceNamespace\":\"urn:ayuvahak:manual\","
                + "\"sourceObservationId\":\"SYN-INTERRUPT-ASSIGNMENT\","
                + "\"assignmentReference\":\"SYN-ASSIGNMENT-INTERRUPT\",\"occurredAt\":\""
                + base.plusSeconds(1)
                + "\"}")
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "interruption-en-route-origin",
            operator,
            statusBody("SYN-INTERRUPT-002", "EN_ROUTE_TO_ORIGIN", base.plusSeconds(2)))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "interruption-at-origin",
            operator,
            statusBody("SYN-INTERRUPT-003", "AT_ORIGIN", base.plusSeconds(3)))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "interruption-patient-onboard",
            operator,
            statusBody("SYN-INTERRUPT-004", "PATIENT_ONBOARD", base.plusSeconds(4)))
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "interruption-departure",
            operator,
            statusBody("SYN-INTERRUPT-005", "EN_ROUTE_TO_DESTINATION", base.plusSeconds(5)))
        .andExpect(status().isCreated());
    var departureDispatch = outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    assertThat(departureDispatch.delivered())
        .as(
            "departure event must reach the registered Transfer movement consumer: %s",
            departureDispatch)
        .isGreaterThan(0);
    assertThat(transferStatus(transferId)).isEqualTo("IN_TRANSIT");

    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "interruption-status",
            operator,
            statusBodyWithReason(
                "SYN-INTERRUPT-006", "INTERRUPTED", base.plusSeconds(6), "ROUTE_INTERRUPTED"))
        .andExpect(status().isCreated());
    assertThat(eventTypes(requestId))
        .containsExactly("transport.departure-confirmed.v1", "transport.interrupted.v1");
    outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    assertThat(transferStatus(transferId)).isEqualTo("INTERRUPTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT reason_code FROM transfer.progress_fact WHERE transfer_id = ? "
                    + "AND fact_kind = 'INTERRUPTION'",
                String.class,
                UUID.fromString(transferId)))
        .isEqualTo("ROUTE_INTERRUPTED");

    transportEvidenceCommand(
            scope,
            requestId,
            "unfulfilled",
            "interruption-unfulfilled",
            operator,
            unfulfilledBody("SYN-INTERRUPT-007", base.plusSeconds(7), "NO_SAFE_CONTINUATION"))
        .andExpect(status().isCreated());
    assertThat(eventTypes(requestId))
        .containsExactly(
            "transport.departure-confirmed.v1",
            "transport.interrupted.v1",
            "transport.unfulfilled.v1");
    outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    assertThat(transferStatus(transferId)).isEqualTo("INTERRUPTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.consumer_event_receipt "
                    + "WHERE event_type = 'transport.unfulfilled.v1'",
                String.class))
        .isEqualTo("TRANSPORT_UNFULFILLED_REQUIRES_RECONCILIATION");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.progress_fact WHERE transfer_id = ? "
                    + "AND fact_kind IN ('HANDOVER', 'COMPLETION')",
                Integer.class,
                UUID.fromString(transferId)))
        .isZero();
  }

  private static String statusBody(String observationId, String status, Instant occurredAt) {
    return "{\"observationId\":\""
        + observationId
        + "\","
        + "\"sourceNamespace\":\"urn:ayuvahak:manual\",\"status\":\""
        + status
        + "\","
        + "\"occurredAt\":\""
        + occurredAt
        + "\"}";
  }

  private InternalEvent internalEvent(UUID eventId) {
    return jdbc.queryForObject(
        "SELECT event_id, event_type, producer_context, aggregate_ref, aggregate_sequence, "
            + "envelope::text AS envelope_json FROM platform_eventing.internal_event WHERE event_id = ?",
        (row, index) -> {
          String aggregate = row.getString("aggregate_ref");
          Long sequence = row.getObject("aggregate_sequence", Long.class);
          java.util.Optional<AggregatePosition> position =
              aggregate == null
                  ? java.util.Optional.empty()
                  : java.util.Optional.of(new AggregatePosition(aggregate, sequence));
          return new InternalEvent(
              InternalId.parse(row.getObject("event_id", UUID.class).toString()),
              EventType.parse(row.getString("event_type")),
              row.getString("producer_context"),
              position,
              row.getString("envelope_json").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        },
        eventId);
  }

  private static String statusBodyWithReason(
      String observationId, String status, Instant occurredAt, String reasonCode) {
    return "{\"observationId\":\""
        + observationId
        + "\","
        + "\"sourceNamespace\":\"urn:ayuvahak:manual\",\"status\":\""
        + status
        + "\","
        + "\"occurredAt\":\""
        + occurredAt
        + "\",\"reasonCode\":\""
        + reasonCode
        + "\"}";
  }

  private static String unfulfilledBody(
      String observationId, Instant occurredAt, String reasonCode) {
    return "{\"sourceNamespace\":\"urn:ayuvahak:manual\","
        + "\"sourceObservationId\":\""
        + observationId
        + "\","
        + "\"occurredAt\":\""
        + occurredAt
        + "\",\"reasonCode\":\""
        + reasonCode
        + "\"}";
  }

  private int countTransportEvents(UUID requestId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM platform_eventing.internal_event WHERE aggregate_ref = ?",
        Integer.class,
        "transport-request/" + requestId);
  }

  private List<String> eventTypes(UUID requestId) {
    return jdbc.queryForList(
        "SELECT event_type FROM platform_eventing.internal_event WHERE aggregate_ref = ? "
            + "ORDER BY aggregate_sequence",
        String.class,
        "transport-request/" + requestId);
  }

  private String transferStatus(String transferId) {
    return jdbc.queryForObject(
        "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
        String.class,
        UUID.fromString(transferId));
  }

  @Test
  void transportOperatorCanRecordManualAssignmentStatusAndEtaAsAttributedEvidence()
      throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "transport-evidence-happy", true);
    grantTransportWorker(scope);
    requestTransport(scope, transferId, "transport-evidence-request", SENDER)
        .andExpect(status().isCreated());
    UUID requestId =
        jdbc.queryForObject(
            "SELECT transport_request_id FROM transport.transport_request WHERE transfer_id = ?",
            UUID.class,
            UUID.fromString(transferId));
    String operator = "synthetic-transport-operator";
    grantTransportAuthority(scope, operator, "TransportStatusAuthority");
    grantTransportAuthority(scope, operator, "TransportDispatchCoordinationAuthority");
    Instant statusAt = Instant.now().minusSeconds(10);
    Instant assignmentAt = statusAt.plusSeconds(1);
    Instant etaObservedAt = assignmentAt.plusSeconds(1);

    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "transport-status-awaiting-assignment",
            operator,
            "{\"observationId\":\"SYN-STATUS-001\",\"sourceNamespace\":\"urn:ayuvahak:manual\","
                + "\"status\":\"AWAITING_ASSIGNMENT\",\"occurredAt\":\""
                + statusAt
                + "\","
                + "\"reasonCode\":\"DISPATCH_COORDINATION_STARTED\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.requestStatus").value("AWAITING_ASSIGNMENT"));

    String assignmentBody =
        "{\"sourceNamespace\":\"urn:ayuvahak:manual\","
            + "\"sourceObservationId\":\"SYN-ASSIGNMENT-001\","
            + "\"assignmentReference\":\"SYN-ASSIGNMENT-001\",\"occurredAt\":\""
            + assignmentAt
            + "\"}";
    transportEvidenceCommand(
            scope, requestId, "assignments", "transport-assignment-001", operator, assignmentBody)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.requestStatus").value("ASSIGNED"));
    transportEvidenceCommand(
            scope, requestId, "assignments", "transport-assignment-001", operator, assignmentBody)
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"));
    transportEvidenceCommand(
            scope,
            requestId,
            "assignments",
            "transport-assignment-source-replay",
            operator,
            assignmentBody)
        .andExpect(status().isCreated())
        .andExpect(header().string("Source-Observation-Replayed", "true"));
    transportEvidenceCommand(
            scope,
            requestId,
            "assignments",
            "transport-assignment-changed-fingerprint",
            operator,
            assignmentBody.replace(
                "SYN-ASSIGNMENT-001\",\"occurredAt", "SYN-ASSIGNMENT-OTHER\",\"occurredAt"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSPORT_EVIDENCE_CONFLICT"));

    transportEvidenceCommand(
            scope,
            requestId,
            "eta-observations",
            "transport-eta-001",
            operator,
            "{\"observationId\":\"SYN-ETA-001\",\"sourceNamespace\":\"urn:ayuvahak:manual\","
                + "\"estimatedArrivalAt\":\""
                + etaObservedAt.plusSeconds(3600)
                + "\",\"observedAt\":\""
                + etaObservedAt
                + "\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.requestStatus").value("ASSIGNED"));

    assertThat(count("SELECT COUNT(*) FROM transport.status_observation")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM transport.assignment_observation")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM transport.eta_observation")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT request_status FROM transport.transport_request WHERE transport_request_id = ?",
                String.class,
                requestId))
        .isEqualTo("ASSIGNED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code LIKE 'TRANSPORT_%'",
                Integer.class))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                UUID.fromString(transferId)))
        .isEqualTo("READY");
  }

  @Test
  void outOfOrderTransportStatusIsRetainedForReconciliationWithoutRegressingState()
      throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "transport-evidence-order", true);
    grantTransportWorker(scope);
    requestTransport(scope, transferId, "transport-evidence-order-request", SENDER)
        .andExpect(status().isCreated());
    UUID requestId =
        jdbc.queryForObject(
            "SELECT transport_request_id FROM transport.transport_request WHERE transfer_id = ?",
            UUID.class,
            UUID.fromString(transferId));
    String operator = "synthetic-transport-operator";
    grantTransportAuthority(scope, operator, "TransportStatusAuthority");
    grantTransportAuthority(scope, operator, "TransportDispatchCoordinationAuthority");
    Instant statusAt = Instant.now().minusSeconds(10);
    Instant assignmentAt = statusAt.plusSeconds(1);

    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "transport-order-awaiting-assignment",
            operator,
            "{\"observationId\":\"SYN-ORDER-001\",\"sourceNamespace\":\"urn:ayuvahak:manual\","
                + "\"status\":\"AWAITING_ASSIGNMENT\",\"occurredAt\":\""
                + statusAt
                + "\","
                + "\"reasonCode\":\"DISPATCH_COORDINATION_STARTED\"}")
        .andExpect(status().isCreated());
    transportEvidenceCommand(
            scope,
            requestId,
            "assignments",
            "transport-order-assignment",
            operator,
            "{\"sourceNamespace\":\"urn:ayuvahak:manual\","
                + "\"sourceObservationId\":\"SYN-ASSIGNMENT-ORDER\","
                + "\"assignmentReference\":\"SYN-ASSIGNMENT-ORDER\",\"occurredAt\":\""
                + assignmentAt
                + "\"}")
        .andExpect(status().isCreated());

    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "transport-order-stale-status",
            operator,
            "{\"observationId\":\"SYN-ORDER-002\",\"sourceNamespace\":\"urn:ayuvahak:manual\","
                + "\"status\":\"AWAITING_ASSIGNMENT\",\"occurredAt\":\""
                + assignmentAt.plusSeconds(1)
                + "\","
                + "\"reasonCode\":\"STALE_SOURCE_STATUS\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSPORT_STATE_CONFLICT"));

    assertThat(
            jdbc.queryForObject(
                "SELECT request_status FROM transport.transport_request WHERE transport_request_id = ?",
                String.class,
                requestId))
        .isEqualTo("ASSIGNED");
    assertThat(
            jdbc.queryForObject(
                "SELECT observation_outcome FROM transport.status_observation "
                    + "WHERE source_observation_id = 'SYN-ORDER-002'",
                String.class))
        .isEqualTo("RECONCILIATION_REQUIRED");
  }

  @Test
  void transportViewsRequireBothFunctionalAuthorityAndTransferParticipation() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "transport-scoped-view", true);
    grantTransportWorker(scope);
    requestTransport(scope, transferId, "transport-scoped-view-request", SENDER)
        .andExpect(status().isCreated());
    UUID requestId =
        jdbc.queryForObject(
            "SELECT transport_request_id FROM transport.transport_request WHERE transfer_id = ?",
            UUID.class,
            UUID.fromString(transferId));
    String operator = "synthetic-transport-viewer";
    grantTransportAuthority(scope, operator, "TransportStatusAuthority");
    grantTransportAuthority(scope, operator, "TransportDispatchCoordinationAuthority");
    grantTransportAuthority(scope, operator, "TransportCancellationAuthority");
    grantOriginAuthority(operator, "TransferViewAuthority", scope);
    Instant statusAt = Instant.now().minusSeconds(10);
    transportEvidenceCommand(
            scope,
            requestId,
            "status-observations",
            "transport-view-status",
            operator,
            statusBodyWithReason(
                "SYN-VIEW-STATUS-001",
                "AWAITING_ASSIGNMENT",
                statusAt,
                "DISPATCH_COORDINATION_STARTED"))
        .andExpect(status().isCreated());
    Instant assignmentAt = statusAt.plusSeconds(1);
    transportEvidenceCommand(
            scope,
            requestId,
            "assignments",
            "transport-view-assignment",
            operator,
            "{\"sourceNamespace\":\"urn:ayuvahak:manual\","
                + "\"sourceObservationId\":\"SYN-VIEW-ASSIGNMENT-001\","
                + "\"assignmentReference\":\"SYN-ASSIGNMENT-001\",\"occurredAt\":\""
                + assignmentAt
                + "\"}")
        .andExpect(status().isCreated());
    Instant etaObservedAt = assignmentAt.plusSeconds(1);
    transportEvidenceCommand(
            scope,
            requestId,
            "eta-observations",
            "transport-view-eta",
            operator,
            "{\"observationId\":\"SYN-VIEW-ETA-001\","
                + "\"sourceNamespace\":\"urn:ayuvahak:manual\","
                + "\"estimatedArrivalAt\":\""
                + etaObservedAt.plusSeconds(3600)
                + "\",\"observedAt\":\""
                + etaObservedAt
                + "\"}")
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            post("/api/v1/transport-requests/{requestId}/cancellation-operations", requestId)
                .header("Authorization", "Bearer " + token(operator))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "transport-view-cancellation")
                .contentType("application/json")
                .content("{\"reasonCode\":\"OPERATOR_RECONCILIATION\"}"))
        .andExpect(status().isAccepted());

    mockMvc
        .perform(
            get("/api/v1/transfers/{transferId}/transport-requests", transferId)
                .header("Authorization", "Bearer " + token(operator))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .param("page", "0")
                .param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].transportRequestId").value(requestId.toString()))
        .andExpect(jsonPath("$.items[0].status").value("ASSIGNED"))
        .andExpect(jsonPath("$.hasMore").value(false));

    mockMvc
        .perform(
            get(
                    "/api/v1/transfers/{transferId}/transport-requests/{requestId}",
                    transferId,
                    requestId)
                .header("Authorization", "Bearer " + token(operator))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.request.transportRequestId").value(requestId.toString()))
        .andExpect(
            jsonPath("$.statusObservations[0].reasonCode").value("DISPATCH_COORDINATION_STARTED"))
        .andExpect(
            jsonPath("$.assignmentObservations[0].assignmentReference").value("SYN-ASSIGNMENT-001"))
        .andExpect(
            jsonPath("$.etaObservations[0].observationId")
                .value(org.hamcrest.Matchers.notNullValue()))
        .andExpect(jsonPath("$.cancellationOperations[0].state").value("PENDING"));

    String transportOnly = "synthetic-transport-only-viewer";
    grantTransportAuthority(scope, transportOnly, "TransportStatusAuthority");
    mockMvc
        .perform(
            get("/api/v1/transfers/{transferId}/transport-requests", transferId)
                .header("Authorization", "Bearer " + token(transportOnly))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId()))
        .andExpect(status().isForbidden());
  }

  private org.springframework.test.web.servlet.ResultActions transportEvidenceCommand(
      Scope scope,
      UUID requestId,
      String operation,
      String idempotencyKey,
      String subject,
      String body)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/transport-requests/{transportRequestId}/{operation}", requestId, operation)
            .header("Authorization", "Bearer " + token(subject))
            .header("X-AyuVahak-Execution-Scope", "FACILITY")
            .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
            .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
            .header("Idempotency-Key", idempotencyKey)
            .contentType("application/json")
            .content(body));
  }

  private org.springframework.test.web.servlet.ResultActions requestTransport(
      Scope scope, String transferId, String idempotencyKey, String subject) throws Exception {
    return mockMvc.perform(
        post("/api/v1/transfers/{transferId}/transport-request", transferId)
            .header("Authorization", "Bearer " + token(subject))
            .header("X-AyuVahak-Execution-Scope", "FACILITY")
            .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
            .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
            .header("Idempotency-Key", idempotencyKey)
            .contentType("application/json")
            .content("{}"));
  }

  @Test
  void originParticipantCanReadBoundedJourneyHistoryWithoutPatientContext(CapturedOutput output)
      throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String created =
        createRequest(scope, "journey-view-request")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String journeyId = com.jayway.jsonpath.JsonPath.read(created, "$.journeyId");
    String requestAttemptId = com.jayway.jsonpath.JsonPath.read(created, "$.requestAttemptId");
    grantOriginAuthority("synthetic-transfer-viewer", "TransferViewAuthority", scope);

    String view =
        mockMvc
            .perform(
                get("/api/v1/transfer-journeys/{journeyId}", journeyId)
                    .param("page", "0")
                    .param("size", "10")
                    .header("Authorization", "Bearer " + token("synthetic-transfer-viewer"))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", scope.originFacilityId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.journeyId").value(journeyId))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].requestAttemptId").value(requestAttemptId))
            .andExpect(jsonPath("$.items[0].status").value("PENDING"))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(10))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(view).doesNotContain("patientReference", "PatientReference");
    String safeRequestLogs =
        output
            .getAll()
            .lines()
            .filter(
                line ->
                    line.contains(
                        "\"logger_name\":\"org.ayuvahak.platform.observability.SafeHttpRequestLoggingFilter\""))
            .collect(Collectors.joining(System.lineSeparator()));
    assertThat(safeRequestLogs.contains(journeyId)).isFalse();
    assertThat(safeRequestLogs.contains(requestAttemptId)).isFalse();
    assertThat(safeRequestLogs.contains("Bearer ")).isFalse();
    assertThat(safeRequestLogs.contains("patientReference")).isFalse();
    String metrics =
        mockMvc
            .perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(metrics)
        .contains("http_server_requests_seconds_count")
        .doesNotContain(journeyId, requestAttemptId, "patientReference");
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'TRANSFER_JOURNEY_READ' "
                    + "AND outcome = 'SUCCEEDED'"))
        .isEqualTo(1);
  }

  @Test
  void journeyHistoryLaterPageReturnsNextAttemptWithoutSkippingItTwice() throws Exception {
    Scope origin = createOriginAndDestination();
    grantSender(origin);
    matching.returnApplicable(origin.referralId());
    String firstResponse =
        createRequest(origin, "journey-history-page-first")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String journeyId = com.jayway.jsonpath.JsonPath.read(firstResponse, "$.journeyId");
    String firstAttemptId = com.jayway.jsonpath.JsonPath.read(firstResponse, "$.requestAttemptId");
    grantReceivingAuthority(origin, "synthetic-page-receiver", "AcceptanceAuthority");
    receivingCommand(
            origin,
            firstAttemptId,
            "rejection",
            "synthetic-page-receiver",
            "journey-history-page-reject-first",
            "{\"expectedVersion\":1,\"reasonCode\":\"DESTINATION_UNAVAILABLE\","
                + "\"occurredAt\":\"2026-10-10T11:20:00Z\"}")
        .andExpect(status().isCreated());

    InternalId secondFacilityId = InternalId.generate();
    Instant createdAt = Instant.now();
    directory.insertFacility(
        new Facility(
            secondFacilityId,
            InternalId.parse(origin.originOrganizationId()),
            "Synthetic second page destination",
            new DirectorySource(
                new ExternalReference(
                    new IssuerId("urn:synthetic:directory-source"),
                    "Facility",
                    "page-destination-" + UUID.randomUUID())),
            new DirectoryProvenance(
                "MANUAL_REVIEW",
                createdAt,
                ActorReference.human(new IssuerId("urn:synthetic:fixture"), "directory-fixture"),
                createdAt,
                "TEST_FIXTURE"),
            DirectoryLifecycleStatus.ACTIVE,
            1));
    matching.returnApplicable(origin.referralId());
    String secondResponse =
        mockMvc
            .perform(
                post("/api/v1/transfer-journeys/{journeyId}/request-attempts", journeyId)
                    .header("Authorization", "Bearer " + token(SENDER))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", origin.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", origin.originFacilityId())
                    .header("Idempotency-Key", "journey-history-page-second")
                    .contentType("application/json")
                    .content(
                        "{\"expectedVersion\":1,\"referralRevision\":4,\"recommendationId\":\""
                            + origin.recommendationId()
                            + "\",\"candidateFacilityId\":\""
                            + secondFacilityId
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String secondAttemptId =
        com.jayway.jsonpath.JsonPath.read(secondResponse, "$.requestAttemptId");
    grantOriginAuthority("synthetic-journey-page-viewer", "TransferViewAuthority", origin);

    mockMvc
        .perform(
            get("/api/v1/transfer-journeys/{journeyId}", journeyId)
                .param("page", "0")
                .param("size", "1")
                .header("Authorization", "Bearer " + token("synthetic-journey-page-viewer"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", origin.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", origin.originFacilityId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].requestAttemptId").value(firstAttemptId))
        .andExpect(jsonPath("$.hasMore").value(true));

    mockMvc
        .perform(
            get("/api/v1/transfer-journeys/{journeyId}", journeyId)
                .param("page", "1")
                .param("size", "1")
                .header("Authorization", "Bearer " + token("synthetic-journey-page-viewer"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", origin.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", origin.originFacilityId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].requestAttemptId").value(secondAttemptId))
        .andExpect(jsonPath("$.hasMore").value(false));
  }

  @Test
  void journeyViewHidesNonparticipantsAndHonorsRevokedGrant() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String created =
        createRequest(scope, "journey-view-access")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String journeyId = com.jayway.jsonpath.JsonPath.read(created, "$.journeyId");

    grantOriginAuthority("synthetic-revoked-viewer", "TransferViewAuthority", scope);
    jdbc.update(
        "UPDATE identity_access.authorization_grant SET grant_status = 'REVOKED', "
            + "revoked_at = CURRENT_TIMESTAMP "
            + "WHERE principal_identifier = ? AND authority_code = 'TransferViewAuthority'",
        "synthetic-revoked-viewer");
    mockMvc
        .perform(
            get("/api/v1/transfer-journeys/{journeyId}", journeyId)
                .header("Authorization", "Bearer " + token("synthetic-revoked-viewer"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'TRANSFER_JOURNEY_READ' "
                    + "AND outcome = 'DENIED'"))
        .isEqualTo(1);

    Scope outsider = createOriginAndDestination();
    grantOriginAuthority("synthetic-outsider-viewer", "TransferViewAuthority", outsider);
    mockMvc
        .perform(
            get("/api/v1/transfer-journeys/{journeyId}", journeyId)
                .header("Authorization", "Bearer " + token("synthetic-outsider-viewer"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", outsider.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", outsider.originFacilityId()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
  }

  @Test
  void reroutedJourneyHistoryShowsEachReceivingParticipantOnlyItsOwnAttempt() throws Exception {
    Scope origin = createOriginAndDestination();
    grantSender(origin);
    matching.returnApplicable(origin.referralId());
    String firstAttemptId = createRequestAttempt(origin, "participant-history-first-request");
    UUID journeyId =
        jdbc.queryForObject(
            "SELECT journey_id FROM transfer.request_attempt WHERE request_attempt_id = ?",
            UUID.class,
            UUID.fromString(firstAttemptId));
    grantReceivingAuthority(origin, "synthetic-first-receiver", "AcceptanceAuthority");
    receivingCommand(
            origin,
            firstAttemptId,
            "rejection",
            "synthetic-first-receiver",
            "participant-history-first-rejection",
            "{\"expectedVersion\":1,\"reasonCode\":\"DESTINATION_UNAVAILABLE\","
                + "\"occurredAt\":\"2026-10-10T10:20:00Z\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("REJECTED"));

    InternalId secondFacilityId = InternalId.generate();
    Instant createdAt = Instant.now();
    directory.insertFacility(
        new Facility(
            secondFacilityId,
            InternalId.parse(origin.originOrganizationId()),
            "Synthetic second receiving facility",
            new DirectorySource(
                new ExternalReference(
                    new IssuerId("urn:synthetic:directory-source"),
                    "Facility",
                    "receiving-" + UUID.randomUUID())),
            new DirectoryProvenance(
                "MANUAL_REVIEW",
                createdAt,
                ActorReference.human(new IssuerId("urn:synthetic:fixture"), "directory-fixture"),
                createdAt,
                "TEST_FIXTURE"),
            DirectoryLifecycleStatus.ACTIVE,
            1));
    matching.returnApplicable(origin.referralId());
    String secondAttempt =
        mockMvc
            .perform(
                post("/api/v1/transfer-journeys/{journeyId}/request-attempts", journeyId)
                    .header("Authorization", "Bearer " + token(SENDER))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", origin.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", origin.originFacilityId())
                    .header("Idempotency-Key", "participant-history-second-request")
                    .contentType("application/json")
                    .content(
                        "{\"expectedVersion\":1,\"referralRevision\":4,\"recommendationId\":\""
                            + origin.recommendationId()
                            + "\",\"candidateFacilityId\":\""
                            + secondFacilityId
                            + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.attemptNumber").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String secondAttemptId = com.jayway.jsonpath.JsonPath.read(secondAttempt, "$.requestAttemptId");

    grantOriginAuthority("synthetic-complete-history-viewer", "TransferViewAuthority", origin);
    String completeHistory =
        mockMvc
            .perform(
                get("/api/v1/transfer-journeys/{journeyId}", journeyId)
                    .header("Authorization", "Bearer " + token("synthetic-complete-history-viewer"))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", origin.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", origin.originFacilityId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].requestAttemptId").value(firstAttemptId))
            .andExpect(jsonPath("$.items[1].requestAttemptId").value(secondAttemptId))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(completeHistory).contains("DESTINATION_UNAVAILABLE");

    grantFacilityAuthority("synthetic-first-history-viewer", "TransferViewAuthority", origin);
    String firstReceiverHistory =
        mockMvc
            .perform(
                get("/api/v1/transfer-journeys/{journeyId}", journeyId)
                    .header("Authorization", "Bearer " + token("synthetic-first-history-viewer"))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", origin.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", origin.destinationFacilityId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].requestAttemptId").value(firstAttemptId))
            .andExpect(jsonPath("$.items[0].decisionReasonCode").value("DESTINATION_UNAVAILABLE"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(firstReceiverHistory).doesNotContain(secondAttemptId, secondFacilityId.toString());

    Scope secondReceiver = origin.withDestinationFacility(secondFacilityId.toString());
    grantFacilityAuthority(
        "synthetic-second-history-viewer", "TransferViewAuthority", secondReceiver);
    String secondReceiverHistory =
        mockMvc
            .perform(
                get("/api/v1/transfer-journeys/{journeyId}", journeyId)
                    .header("Authorization", "Bearer " + token("synthetic-second-history-viewer"))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", origin.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", secondFacilityId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].requestAttemptId").value(secondAttemptId))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(secondReceiverHistory).doesNotContain(firstAttemptId, "DESTINATION_UNAVAILABLE");
  }

  @Test
  void organizationScopedJourneyHistoryContainsOnlyAttemptsAssessedOrDecidedByThatOrganization()
      throws Exception {
    Scope origin = createOriginAndDestination();
    ReceivingFacility firstReceiver = createReceivingFacility("Synthetic receiving one");
    ReceivingFacility secondReceiver = createReceivingFacility("Synthetic receiving two");
    grantSender(origin);
    matching.returnApplicable(origin.referralId());
    String firstResponse =
        createRequest(
                origin.withDestinationFacility(firstReceiver.facilityId()),
                "org-history-first-request")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String firstAttemptId = com.jayway.jsonpath.JsonPath.read(firstResponse, "$.requestAttemptId");
    String journeyId = com.jayway.jsonpath.JsonPath.read(firstResponse, "$.journeyId");
    grantFacilityAuthorityAt(
        "synthetic-org-one-decision-maker",
        "AcceptanceAuthority",
        firstReceiver.organizationId(),
        firstReceiver.facilityId());
    receivingCommandAt(
            firstReceiver.organizationId(),
            firstReceiver.facilityId(),
            firstAttemptId,
            "rejection",
            "synthetic-org-one-decision-maker",
            "org-history-first-rejection",
            "{\"expectedVersion\":1,\"reasonCode\":\"DESTINATION_UNAVAILABLE\","
                + "\"occurredAt\":\"2026-10-10T10:20:00Z\"}")
        .andExpect(status().isCreated());

    matching.returnApplicable(origin.referralId());
    String secondResponse =
        mockMvc
            .perform(
                post("/api/v1/transfer-journeys/{journeyId}/request-attempts", journeyId)
                    .header("Authorization", "Bearer " + token(SENDER))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", origin.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", origin.originFacilityId())
                    .header("Idempotency-Key", "org-history-second-request")
                    .contentType("application/json")
                    .content(
                        "{\"expectedVersion\":1,\"referralRevision\":4,\"recommendationId\":\""
                            + origin.recommendationId()
                            + "\",\"candidateFacilityId\":\""
                            + secondReceiver.facilityId()
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String secondAttemptId =
        com.jayway.jsonpath.JsonPath.read(secondResponse, "$.requestAttemptId");
    grantFacilityAuthorityAt(
        "synthetic-org-two-clinician",
        "ClinicalSuitabilityAuthority",
        secondReceiver.organizationId(),
        secondReceiver.facilityId());
    receivingCommandAt(
            secondReceiver.organizationId(),
            secondReceiver.facilityId(),
            secondAttemptId,
            "clinical-suitability-assessments",
            "synthetic-org-two-clinician",
            "org-history-second-assessment",
            "{\"expectedVersion\":1,\"outcome\":\"SUPPORTS\","
                + "\"reasonCode\":\"CLINICAL_REVIEW_COMPLETE\","
                + "\"occurredAt\":\"2026-10-10T10:30:00Z\"}")
        .andExpect(status().isCreated());

    grantOrganizationAuthority(
        "synthetic-org-one-viewer", "TransferViewAuthority", firstReceiver.organizationId());
    String firstOrganizationView =
        organizationJourneyView(
                journeyId, "synthetic-org-one-viewer", firstReceiver.organizationId())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].requestAttemptId").value(firstAttemptId))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(firstOrganizationView)
        .contains("DESTINATION_UNAVAILABLE")
        .doesNotContain(secondAttemptId, "CLINICAL_REVIEW_COMPLETE");

    grantOrganizationAuthority(
        "synthetic-org-two-viewer", "TransferViewAuthority", secondReceiver.organizationId());
    String secondOrganizationView =
        organizationJourneyView(
                journeyId, "synthetic-org-two-viewer", secondReceiver.organizationId())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].requestAttemptId").value(secondAttemptId))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(secondOrganizationView)
        .contains("CLINICAL_REVIEW_COMPLETE")
        .doesNotContain(firstAttemptId, "DESTINATION_UNAVAILABLE");
  }

  @Test
  void receivingParticipantCanReadDeterministicallyPagedIncomingRequests() throws Exception {
    Scope receiving = createOriginAndDestination();
    Scope secondOrigin =
        createOriginAndDestination().withDestinationFacility(receiving.destinationFacilityId());
    grantSender(receiving);
    grantSender(secondOrigin);
    matching.returnApplicable(receiving.referralId());
    String firstRequest =
        createRequest(receiving, "incoming-view-first")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String firstRequestId = com.jayway.jsonpath.JsonPath.read(firstRequest, "$.requestAttemptId");
    matching.returnApplicable(secondOrigin.referralId());
    String secondRequest =
        createRequest(secondOrigin, "incoming-view-second")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String secondRequestId = com.jayway.jsonpath.JsonPath.read(secondRequest, "$.requestAttemptId");
    jdbc.update(
        "UPDATE transfer.request_attempt SET expires_at = CURRENT_TIMESTAMP + INTERVAL '20 seconds' "
            + "WHERE request_attempt_id = ?",
        UUID.fromString(firstRequestId));
    jdbc.update(
        "UPDATE transfer.request_attempt SET expires_at = CURRENT_TIMESTAMP + INTERVAL '10 seconds' "
            + "WHERE request_attempt_id = ?",
        UUID.fromString(secondRequestId));
    grantFacilityAuthority("synthetic-incoming-viewer", "TransferViewAuthority", receiving);

    String firstPage =
        mockMvc
            .perform(
                get("/api/v1/transfer-request-attempts")
                    .param("page", "0")
                    .param("size", "1")
                    .param("state", "PENDING")
                    .header("Authorization", "Bearer " + token("synthetic-incoming-viewer"))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", receiving.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", receiving.destinationFacilityId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].requestAttemptId").value(secondRequestId))
            .andExpect(jsonPath("$.hasMore").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(firstPage).doesNotContain("patientReference", "PatientReference");
    mockMvc
        .perform(
            get("/api/v1/transfer-request-attempts")
                .param("page", "1")
                .param("size", "1")
                .header("Authorization", "Bearer " + token("synthetic-incoming-viewer"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", receiving.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", receiving.destinationFacilityId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].requestAttemptId").value(firstRequestId))
        .andExpect(jsonPath("$.hasMore").value(false));
    mockMvc
        .perform(
            get("/api/v1/transfer-request-attempts")
                .param("size", "101")
                .header("Authorization", "Bearer " + token("synthetic-incoming-viewer"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", receiving.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", receiving.destinationFacilityId()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("TRANSFER_REQUEST_INVALID"));
    mockMvc
        .perform(
            get("/api/v1/transfer-request-attempts")
                .param("state", "ACCEPTED")
                .header("Authorization", "Bearer " + token("synthetic-incoming-viewer"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", receiving.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", receiving.destinationFacilityId()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("TRANSFER_REQUEST_INVALID"));
    mockMvc
        .perform(
            get("/api/v1/transfer-request-attempts")
                .param("page", Integer.toString(Integer.MAX_VALUE))
                .param("size", "100")
                .header("Authorization", "Bearer " + token("synthetic-incoming-viewer"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", receiving.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", receiving.destinationFacilityId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
    mockMvc
        .perform(
            get("/api/v1/transfer-request-attempts")
                .param("page", "2147483648")
                .header("Authorization", "Bearer " + token("synthetic-incoming-viewer"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", receiving.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", receiving.destinationFacilityId()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("TRANSFER_REQUEST_INVALID"));
  }

  @Test
  void originAndReceivingParticipantsCanReadBoundedTransferViews() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "transfer-view");
    grantOriginAuthority("synthetic-origin-transfer-viewer", "TransferViewAuthority", scope);
    String originList =
        mockMvc
            .perform(
                get("/api/v1/transfers")
                    .param("page", "0")
                    .param("size", "5")
                    .param("status", "READY")
                    .header("Authorization", "Bearer " + token("synthetic-origin-transfer-viewer"))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", scope.originFacilityId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].transferId").value(transferId))
            .andExpect(jsonPath("$.items[0].status").value("READY"))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(originList).doesNotContain("patientReference", "PatientReference");

    grantFacilityAuthority("synthetic-receiving-transfer-viewer", "TransferViewAuthority", scope);
    String receivingDetail =
        mockMvc
            .perform(
                get("/api/v1/transfers/{transferId}", transferId)
                    .header(
                        "Authorization", "Bearer " + token("synthetic-receiving-transfer-viewer"))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", scope.destinationFacilityId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transferId").value(transferId))
            .andExpect(jsonPath("$.receivingFacilityId").value(scope.destinationFacilityId()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(receivingDetail).doesNotContain("patientReference", "PatientReference");

    Scope outsider = createOriginAndDestination();
    grantOriginAuthority("synthetic-unrelated-transfer-viewer", "TransferViewAuthority", outsider);
    mockMvc
        .perform(
            get("/api/v1/transfers/{transferId}", transferId)
                .header("Authorization", "Bearer " + token("synthetic-unrelated-transfer-viewer"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", outsider.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", outsider.originFacilityId()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code IN "
                    + "('TRANSFER_LIST_READ', 'TRANSFER_READ') AND outcome = 'SUCCEEDED'"))
        .isEqualTo(2);
  }

  @Test
  void transferListOrdersByLatestProgressFactAndFallsBackToCreationBeforeProgress()
      throws Exception {
    Scope firstScope = createOriginAndDestination();
    String firstTransferId = createReadyTransfer(firstScope, "view-order-first");

    InternalId secondFacilityId = InternalId.generate();
    Instant facilityCreatedAt = Instant.now();
    directory.insertFacility(
        new Facility(
            secondFacilityId,
            InternalId.parse(firstScope.originOrganizationId()),
            "Synthetic second transfer-view destination",
            new DirectorySource(
                new ExternalReference(
                    new IssuerId("urn:synthetic:directory-source"),
                    "Facility",
                    "transfer-view-order-" + UUID.randomUUID())),
            new DirectoryProvenance(
                "MANUAL_REVIEW",
                facilityCreatedAt,
                ActorReference.human(new IssuerId("urn:synthetic:fixture"), "directory-fixture"),
                facilityCreatedAt,
                "TEST_FIXTURE"),
            DirectoryLifecycleStatus.ACTIVE,
            1));
    Scope secondScope =
        firstScope.withDifferentReferralId().withDestinationFacility(secondFacilityId.toString());
    matching.returnApplicable(secondScope.referralId());
    grantCapacityReporter(secondScope);
    recordCoordinationSlotObservation(secondScope, 1, "view-order-second-capacity");
    grantReservationWorker(secondScope);
    String secondRequest = createRequestAttempt(secondScope, "view-order-second-request");
    preparePositiveAcceptance(secondScope, secondRequest);
    String secondAcceptance = acceptRequest(secondScope, secondRequest, "view-order-second-accept");
    String secondTransferId = com.jayway.jsonpath.JsonPath.read(secondAcceptance, "$.transferId");
    assertThat(com.jayway.jsonpath.JsonPath.read(secondAcceptance, "$.status").toString())
        .isEqualTo("READY");

    grantOriginAuthority("synthetic-transfer-order-viewer", "TransferViewAuthority", firstScope);
    String beforeProgress =
        mockMvc
            .perform(
                get("/api/v1/transfers")
                    .param("page", "0")
                    .param("size", "10")
                    .header("Authorization", "Bearer " + token("synthetic-transfer-order-viewer"))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", firstScope.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", firstScope.originFacilityId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].transferId").value(secondTransferId))
            .andExpect(jsonPath("$.items[1].transferId").value(firstTransferId))
            .andExpect(jsonPath("$.items[0].lastAggregateSequence").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(
            com.jayway.jsonpath.JsonPath.read(beforeProgress, "$.items[0].lastEventAt").toString())
        .isEqualTo(
            com.jayway.jsonpath.JsonPath.read(beforeProgress, "$.items[0].createdAt").toString());

    grantFacilityAuthority("synthetic-transfer-mover", "TransferProgressAuthority", firstScope);
    Instant departureAt = Instant.now().plusSeconds(2);
    progressCommand(
            firstScope,
            firstTransferId,
            "departure",
            "synthetic-transfer-mover",
            "view-order-first-departure",
            "{\"expectedVersion\":2,\"occurredAt\":\""
                + departureAt
                + "\",\"sourceReference\":\"synthetic:view-order-departure\"}")
        .andExpect(status().isCreated());
    long latestProgressSequence =
        jdbc.queryForObject(
            "SELECT aggregate_sequence FROM transfer.progress_fact WHERE transfer_id = ? "
                + "ORDER BY occurred_at DESC, aggregate_sequence DESC LIMIT 1",
            Long.class,
            UUID.fromString(firstTransferId));
    Instant latestProgressAt =
        jdbc.queryForObject(
            "SELECT occurred_at FROM transfer.progress_fact WHERE transfer_id = ? "
                + "ORDER BY occurred_at DESC, aggregate_sequence DESC LIMIT 1",
            (result, row) -> result.getTimestamp("occurred_at").toInstant(),
            UUID.fromString(firstTransferId));

    mockMvc
        .perform(
            get("/api/v1/transfers")
                .param("page", "0")
                .param("size", "10")
                .header("Authorization", "Bearer " + token("synthetic-transfer-order-viewer"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", firstScope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", firstScope.originFacilityId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].transferId").value(firstTransferId))
        .andExpect(jsonPath("$.items[0].lastAggregateSequence").value(latestProgressSequence))
        .andExpect(jsonPath("$.items[0].lastEventAt").value(latestProgressAt.toString()))
        .andExpect(jsonPath("$.items[1].transferId").value(secondTransferId));
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
  void capacityCanHoldProposedDestinationAlongsideCurrentTransferDestination() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "capacity-reroute-two-holds");
    InternalId proposedFacilityId = InternalId.generate();
    Instant createdAt = Instant.now();
    ActorReference fixtureActor =
        ActorReference.human(new IssuerId("urn:synthetic:fixture"), "directory-fixture");
    directory.insertFacility(
        new Facility(
            proposedFacilityId,
            InternalId.parse(scope.originOrganizationId()),
            "Synthetic proposed reroute destination",
            new DirectorySource(
                new ExternalReference(
                    new IssuerId("urn:synthetic:directory-source"),
                    "Facility",
                    "reroute-destination-" + UUID.randomUUID())),
            new DirectoryProvenance(
                "MANUAL_REVIEW", createdAt, fixtureActor, createdAt, "TEST_FIXTURE"),
            DirectoryLifecycleStatus.ACTIVE,
            1));
    Scope proposedScope = scope.withDestinationFacility(proposedFacilityId.toString());
    grantCapacityReporter(proposedScope);
    grantFacilityServiceAuthority("CapacityReservation", proposedScope);
    recordCoordinationSlotObservation(proposedScope, 1, "capacity-reroute-proposed-destination");

    InternalId proposedReservationOperationId = InternalId.generate();
    Instant start = Instant.now().minusSeconds(1);
    Instant end = start.plus(Duration.ofHours(1));
    CapacityReservationResult proposedReservation =
        capacityReservations.reserve(
            reservationContext(proposedScope),
            new CapacityReservationRequest(
                proposedReservationOperationId,
                InternalId.parse(transferId),
                directoryReference("Facility", proposedFacilityId.toString()),
                "TRANSFER_COORDINATION_SLOT",
                "slot",
                java.math.BigDecimal.ONE,
                start,
                end,
                end),
            "reroute-capacity-" + proposedReservationOperationId);

    assertThat(proposedReservation.outcome()).isEqualTo(CapacityReservationResult.Outcome.RESERVED);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity.coordination_reservation "
                    + "WHERE transfer_id = ? AND reservation_status IN ('ACTIVE', 'AT_RISK')",
                Integer.class,
                UUID.fromString(transferId)))
        .isEqualTo(2);

    InternalId releaseOperationId = InternalId.generate();
    CapacityReleaseResult released =
        capacityReservations.release(
            reservationContext(proposedScope),
            releaseOperationId,
            proposedReservationOperationId,
            InternalId.parse(transferId));
    assertThat(released.outcome()).isEqualTo(CapacityReleaseResult.Outcome.RELEASED);
    assertThat(
            capacityReservations.release(
                reservationContext(proposedScope),
                releaseOperationId,
                proposedReservationOperationId,
                InternalId.parse(transferId)))
        .isEqualTo(released);
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE operation_id = ?",
                String.class,
                proposedReservationOperationId.value()))
        .isEqualTo("RELEASED");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE operation_id = ?",
                String.class,
                UUID.fromString(transferId)))
        .isEqualTo("ACTIVE");
    assertThatThrownBy(
            () ->
                capacityReservations.release(
                    reservationContext(proposedScope),
                    releaseOperationId,
                    InternalId.generate(),
                    InternalId.parse(transferId)))
        .isInstanceOf(PlatformProblemException.class)
        .extracting(exception -> ((PlatformProblemException) exception).status())
        .isEqualTo(HttpStatus.CONFLICT);
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
            "cancellation",
            "synthetic-transfer-mover",
            "cancel-after-departure",
            "{\"expectedVersion\":3,\"occurredAt\":\"2026-10-10T10:01:00Z\","
                + "\"reasonCode\":\"ROUTE_CANCELLED\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSFER_STATE_CONFLICT"));
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
            "cancellation",
            "synthetic-transfer-mover",
            "cancel-after-arrival",
            "{\"expectedVersion\":4,\"occurredAt\":\"2026-10-10T10:46:00Z\","
                + "\"reasonCode\":\"ROUTE_CANCELLED\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSFER_STATE_CONFLICT"));
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

    progressCommand(
            scope,
            transferId,
            "cancellation",
            "synthetic-transfer-receiver",
            "cancel-after-completion",
            "{\"expectedVersion\":6,\"occurredAt\":\"2026-10-10T10:56:00Z\","
                + "\"reasonCode\":\"ROUTE_CANCELLED\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSFER_STATE_CONFLICT"));

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
  void cancellationReleasesCapacityBeforeItBecomesTerminalAndBlocksDeparture() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "cancellation-release");
    grantOriginAuthority(SENDER, "TransferJourneyClosureAuthority", scope);
    UUID journeyId =
        jdbc.queryForObject(
            "SELECT journey_id FROM transfer.transfer_record WHERE transfer_id = ?",
            UUID.class,
            UUID.fromString(transferId));
    mockMvc
        .perform(
            post("/api/v1/transfer-journeys/{journeyId}/closure", journeyId)
                .header("Authorization", "Bearer " + token(SENDER))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "closure-before-capacity-release")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":1,\"reasonCode\":\"TRANSFER_CANCELLED\","
                        + "\"occurredAt\":\"2026-10-10T09:59:00Z\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSFER_STATE_CONFLICT"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.journey_closure WHERE journey_id = ?",
                Integer.class,
                journeyId))
        .isZero();
    grantFacilityAuthority("synthetic-transfer-mover", "TransferProgressAuthority", scope);

    mockMvc
        .perform(
            post("/api/v1/transfers/{transferId}/cancellation", transferId)
                .header("Authorization", "Bearer " + token("synthetic-transfer-mover"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.destinationFacilityId())
                .header("Idempotency-Key", "cancellation-release-command")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":2,\"occurredAt\":\"2026-10-10T10:00:00Z\","
                        + "\"reasonCode\":\"ROUTE_CANCELLED\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("CANCELLED"));

    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE transfer_id = ?",
                String.class,
                UUID.fromString(transferId)))
        .isEqualTo("RELEASED");
    long journeyVersion =
        jdbc.queryForObject(
            "SELECT version FROM transfer.journey WHERE journey_id = ?", Long.class, journeyId);
    mockMvc
        .perform(
            post("/api/v1/transfer-journeys/{journeyId}/closure", journeyId)
                .header("Authorization", "Bearer " + token(SENDER))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "closure-after-capacity-release")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":"
                        + journeyVersion
                        + ",\"reasonCode\":\"TRANSFER_CANCELLED\","
                        + "\"occurredAt\":\"2026-10-10T10:02:00Z\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.outcome").value("UNFULFILLED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT aggregate_sequence FROM platform_eventing.internal_event "
                    + "WHERE event_type = 'transfer.unfulfilled.v1'",
                Long.class))
        .isEqualTo(journeyVersion + 1);
    progressCommand(
            scope,
            transferId,
            "departure",
            "synthetic-transfer-mover",
            "departure-after-cancellation",
            "{\"expectedVersion\":2,\"occurredAt\":\"2026-10-10T10:01:00Z\"}")
        .andExpect(status().isConflict());
  }

  @Test
  void cancellationReleaseSurvivesOwnerTimeoutAndRestartedRecovery() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "cancellation-release-recovery");
    UUID transferUuid = UUID.fromString(transferId);
    grantFacilityAuthority("synthetic-transfer-mover", "TransferProgressAuthority", scope);
    capacityPort.failNextRelease(new IllegalStateException("Synthetic Capacity release timeout"));

    progressCommand(
            scope,
            transferId,
            "cancellation",
            "synthetic-transfer-mover",
            "cancellation-release-timeout",
            "{\"expectedVersion\":2,\"occurredAt\":\"2026-10-10T10:00:00Z\","
                + "\"reasonCode\":\"ROUTE_CANCELLED\"}")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("CANCELLATION_PENDING_RELEASE"));

    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("CANCELLATION_PENDING_RELEASE");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("ACTIVE");

    TransferCapacityReservationRecovery restarted =
        beanFactory.createBean(TransferCapacityReservationRecovery.class);
    assertThat(restarted.reconcileDue()).isEqualTo(1);

    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("RELEASED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.progress_fact WHERE transfer_id = ? "
                    + "AND fact_kind = 'CANCELLATION_RELEASE'",
                Integer.class,
                transferUuid))
        .isEqualTo(1);
  }

  @Test
  void finalAbandonmentFromInterruptedWaitsForCapacityReleaseBeforeRerouting() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "final-abandonment");
    UUID transferUuid = UUID.fromString(transferId);
    UUID journeyId =
        jdbc.queryForObject(
            "SELECT journey_id FROM transfer.transfer_record WHERE transfer_id = ?",
            UUID.class,
            transferUuid);
    grantFacilityAuthority("synthetic-transfer-mover", "TransferProgressAuthority", scope);
    grantOriginAuthority("synthetic-transfer-mover", "TransferProgressAuthority", scope);

    progressCommand(
            scope,
            transferId,
            "departure",
            "synthetic-transfer-mover",
            "final-abandonment-departure",
            "{\"expectedVersion\":2,\"occurredAt\":\"2026-10-10T10:00:00Z\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
    progressCommand(
            scope,
            transferId,
            "interruption",
            "synthetic-transfer-mover",
            "final-abandonment-interruption",
            "{\"expectedVersion\":3,\"occurredAt\":\"2026-10-10T10:15:00Z\","
                + "\"reasonCode\":\"JOURNEY_INTERRUPTED\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("INTERRUPTED"));

    capacityPort.failNextRelease(new IllegalStateException("Synthetic release timeout"));
    progressCommand(
            scope,
            transferId,
            "abandonment",
            "synthetic-transfer-mover",
            "final-abandonment-command",
            "{\"expectedVersion\":4,\"occurredAt\":\"2026-10-10T10:30:00Z\","
                + "\"reasonCode\":\"JOURNEY_FINAL_ABANDONMENT\","
                + "\"sourceReference\":\"synthetic:abandonment-1\"}")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("CANCELLATION_PENDING_RELEASE"));

    assertThat(
            jdbc.queryForList(
                "SELECT fact_kind FROM transfer.progress_fact WHERE transfer_id = ? "
                    + "ORDER BY aggregate_sequence",
                String.class,
                transferUuid))
        .containsExactly("DEPARTURE", "INTERRUPTION", "FINAL_ABANDONMENT");
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("CANCELLATION_PENDING_RELEASE");

    matching.returnApplicable(scope.referralId());
    mockMvc
        .perform(
            post("/api/v1/transfer-journeys/{journeyId}/request-attempts", journeyId)
                .header("Authorization", "Bearer " + token(SENDER))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "reroute-before-abandonment-release")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":4,\"referralRevision\":4,\"recommendationId\":\""
                        + scope.recommendationId()
                        + "\",\"candidateFacilityId\":\""
                        + InternalId.generate()
                        + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSFER_ACTIVE_ATTEMPT_EXISTS"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.request_attempt WHERE journey_id = ?",
                Integer.class,
                journeyId))
        .isEqualTo(1);

    TransferCapacityReservationRecovery restarted =
        beanFactory.createBean(TransferCapacityReservationRecovery.class);
    assertThat(restarted.reconcileDue()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("RELEASED");
    assertThat(
            jdbc.queryForList(
                "SELECT fact_kind FROM transfer.progress_fact WHERE transfer_id = ? "
                    + "ORDER BY aggregate_sequence",
                String.class,
                transferUuid))
        .containsExactly("DEPARTURE", "INTERRUPTION", "FINAL_ABANDONMENT", "CANCELLATION_RELEASE");
  }

  @Test
  void factualArrivalAfterInterruptionIsPreservedAndBlocksFinalAbandonment() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "arrival-before-final-abandonment");
    UUID transferUuid = UUID.fromString(transferId);
    grantFacilityAuthority("synthetic-transfer-mover", "TransferProgressAuthority", scope);
    grantOriginAuthority("synthetic-transfer-mover", "TransferProgressAuthority", scope);
    progressCommand(
            scope,
            transferId,
            "departure",
            "synthetic-transfer-mover",
            "arrival-before-abandonment-departure",
            "{\"expectedVersion\":2,\"occurredAt\":\"2026-10-10T10:00:00Z\"}")
        .andExpect(status().isCreated());
    progressCommand(
            scope,
            transferId,
            "interruption",
            "synthetic-transfer-mover",
            "arrival-before-abandonment-interruption",
            "{\"expectedVersion\":3,\"occurredAt\":\"2026-10-10T10:15:00Z\","
                + "\"reasonCode\":\"JOURNEY_INTERRUPTED\"}")
        .andExpect(status().isCreated());
    progressCommand(
            scope,
            transferId,
            "arrival",
            "synthetic-transfer-mover",
            "arrival-before-abandonment-arrival",
            "{\"expectedVersion\":4,\"occurredAt\":\"2026-10-10T10:10:00Z\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ARRIVED"));

    progressCommandAtOrigin(
            scope,
            transferId,
            "synthetic-transfer-mover",
            "arrival-before-abandonment-command",
            "{\"expectedVersion\":5,\"occurredAt\":\"2026-10-10T10:30:00Z\","
                + "\"reasonCode\":\"JOURNEY_FINAL_ABANDONMENT\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSFER_STATE_CONFLICT"));

    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("ARRIVED");
    assertThat(
            jdbc.queryForList(
                "SELECT fact_kind FROM transfer.progress_fact WHERE transfer_id = ? "
                    + "ORDER BY aggregate_sequence",
                String.class,
                transferUuid))
        .containsExactly("DEPARTURE", "INTERRUPTION", "ARRIVAL");
  }

  @Test
  void cancellationWaitsForLostCapacityReservationResponseToBeReconciled() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    ensureReservableCapacityCatalog();
    grantCapacityReporter(scope);
    recordCoordinationSlotObservation(scope, 1, "capacity-reservation-cancel-timeout");
    grantReservationWorker(scope);
    String requestAttemptId = createRequestAttempt(scope, "capacity-reservation-cancel-request");
    preparePositiveAcceptance(scope, requestAttemptId);
    capacityPort.loseNextResponseAfterCommit();
    String accepted =
        acceptRequest(scope, requestAttemptId, "capacity-reservation-cancel-acceptance");
    UUID transferId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(accepted, "$.transferId"));
    grantFacilityAuthority("synthetic-transfer-mover", "TransferProgressAuthority", scope);

    progressCommand(
            scope,
            transferId.toString(),
            "cancellation",
            "synthetic-transfer-mover",
            "capacity-reservation-cancel-command",
            "{\"expectedVersion\":1,\"occurredAt\":\"2026-10-10T10:49:00Z\","
                + "\"reasonCode\":\"ROUTE_CANCELLED\"}")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("CANCELLATION_PENDING_RELEASE"));

    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("UNAVAILABLE");
    assertThat(capacityPort.releaseCalls()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("ACTIVE");

    jdbc.update(
        "UPDATE transfer.capacity_reservation_request SET next_attempt_at = CURRENT_TIMESTAMP - "
            + "INTERVAL '1 hour' WHERE transfer_id = ? AND outcome = 'UNAVAILABLE'",
        transferId);
    TransferCapacityReservationRecovery restarted =
        beanFactory.createBean(TransferCapacityReservationRecovery.class);
    assertThat(restarted.reconcileDue()).isGreaterThanOrEqualTo(1);

    assertThat(capacityPort.idempotencyKeys())
        .isNotEmpty()
        .containsOnly("transfer-capacity-" + transferId);
    assertThat(capacityPort.reservationOutcomeReadCalls())
        .as("recovery must read the Capacity-owned current reservation outcome")
        .isGreaterThan(0);
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE "
                    + "action_code = 'CAPACITY_RESERVATION_OUTCOME_READ' AND outcome = 'SUCCEEDED'"))
        .as(
            "audit rows after reservation reconciliation: %s",
            jdbc.queryForList(
                "SELECT action_code, outcome, reason_code, target_id FROM audit.audit_record"))
        .isGreaterThan(0);
    assertThat(
            count(
                "SELECT COUNT(*) FROM capacity.reservation_command_receipt WHERE operation_id = '"
                    + transferId
                    + "'::uuid"))
        .isEqualTo(1);
    assertThat(capacityPort.releaseCalls()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("RELEASED");
  }

  @Test
  void cancellationWaitsForDefinitiveReservationOutcomeAndRecoveryReleasesLateHold()
      throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    ensureReservableCapacityCatalog();
    grantCapacityReporter(scope);
    recordCoordinationSlotObservation(scope, 1, "reservation-cancellation-race-capacity");
    grantReservationWorker(scope);
    String requestAttemptId = createRequestAttempt(scope, "reservation-cancellation-race-request");
    preparePositiveAcceptance(scope, requestAttemptId);
    UUID requestAttemptUuid = UUID.fromString(requestAttemptId);
    ReserveGate reserveGate = capacityPort.pauseNextReserve();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<MvcResult> acceptance =
          executor.submit(
              () ->
                  receivingCommand(
                          scope,
                          requestAttemptId,
                          "acceptance",
                          "synthetic-receiver",
                          "reservation-cancellation-race-acceptance",
                          "{\"expectedVersion\":3,\"occurredAt\":\"2026-10-10T10:50:00Z\"}")
                      .andReturn());
      assertThat(reserveGate.entered.await(15, TimeUnit.SECONDS)).isTrue();
      UUID transferUuid =
          jdbc.queryForObject(
              "SELECT transfer_id FROM transfer.transfer_record WHERE request_attempt_id = ?",
              UUID.class,
              requestAttemptUuid);
      grantFacilityAuthority("synthetic-transfer-mover", "TransferProgressAuthority", scope);
      Future<MvcResult> cancellation =
          executor.submit(
              () ->
                  progressCommand(
                          scope,
                          transferUuid.toString(),
                          "cancellation",
                          "synthetic-transfer-mover",
                          "reservation-cancellation-race-command",
                          "{\"expectedVersion\":1,\"occurredAt\":\"2026-10-10T10:51:00Z\","
                              + "\"reasonCode\":\"ROUTE_CANCELLED\"}")
                      .andReturn());

      boolean cancellationReturnedWhileReservationWasPaused;
      try {
        MvcResult result = cancellation.get(2, TimeUnit.SECONDS);
        cancellationReturnedWhileReservationWasPaused = result.getResponse().getStatus() == 202;
      } catch (TimeoutException stillWaitingForReservation) {
        cancellationReturnedWhileReservationWasPaused = false;
      }

      assertThat(cancellationReturnedWhileReservationWasPaused).isTrue();
      assertThat(
              jdbc.queryForObject(
                  "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                  String.class,
                  transferUuid))
          .isEqualTo("CANCELLATION_PENDING_RELEASE");
      assertThat(
              jdbc.queryForObject(
                  "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                  String.class,
                  transferUuid))
          .isEqualTo("IN_PROGRESS");
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM capacity.coordination_reservation WHERE transfer_id = ?",
                  Integer.class,
                  transferUuid))
          .isZero();

      jdbc.update(
          "UPDATE transfer.capacity_reservation_request SET next_attempt_at = CURRENT_TIMESTAMP - "
              + "INTERVAL '1 second' WHERE transfer_id = ? AND outcome = 'IN_PROGRESS'",
          transferUuid);
      TransferCapacityReservationRecovery restarted =
          beanFactory.createBean(TransferCapacityReservationRecovery.class);
      assertThat(restarted.reconcileDue()).isGreaterThanOrEqualTo(1);

      reserveGate.resume.countDown();

      assertThat(acceptance.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
      assertThat(cancellation.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(202);
      assertThat(
              jdbc.queryForObject(
                  "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                  String.class,
                  transferUuid))
          .isEqualTo("CANCELLED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT reservation_status FROM capacity.coordination_reservation "
                      + "WHERE transfer_id = ?",
                  String.class,
                  transferUuid))
          .isEqualTo("RELEASED");

      int reservationCalls = capacityPort.idempotencyKeys().size();
      restarted.reconcile(InternalId.parse(transferUuid.toString()));
      assertThat(capacityPort.idempotencyKeys()).hasSize(reservationCalls);
      assertThat(
              jdbc.queryForObject(
                  "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                  String.class,
                  transferUuid))
          .isEqualTo("CANCELLED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT reservation_status FROM capacity.coordination_reservation "
                      + "WHERE transfer_id = ?",
                  String.class,
                  transferUuid))
          .isEqualTo("RELEASED");
    } finally {
      reserveGate.resume.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void referralCancellationEventWithdrawsPendingAttemptExactlyOnce() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String response =
        createRequest(scope, "referral-event-request")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String journeyId = com.jayway.jsonpath.JsonPath.read(response, "$.journeyId");
    String attemptId = com.jayway.jsonpath.JsonPath.read(response, "$.requestAttemptId");
    CorrelationId eventCorrelation = new CorrelationId("transfer-referral-cancel-correlation");
    grantOwnerEventConsumer(scope, scope.originFacilityId(), "TransferReferralCancellation");

    var payload = objectMapper.createObjectNode();
    payload.put("referralReference", scope.referralId());
    payload.put("revisionNumber", 4);
    payload.put("reasonCode", "ORIGIN_NO_LONGER_REQUIRED");
    payload.put("sourceKind", "HUMAN");
    InternalEvent event =
        internalEvent(
            EventType.parse("referral.cancelled.v1"),
            reservationContext(scope, eventCorrelation),
            "referral/" + scope.referralId(),
            5,
            Instant.parse("2026-10-10T10:00:00Z"),
            payload);

    ownerEventHandler.handle(event);

    assertThat(
            jdbc.queryForObject(
                "SELECT request_status FROM transfer.request_attempt WHERE request_attempt_id = ?",
                String.class,
                UUID.fromString(attemptId)))
        .isEqualTo("WITHDRAWN");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.consumer_event_receipt WHERE event_id = ?",
                Integer.class,
                event.eventId().value()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.consumer_event_receipt WHERE event_id = ?",
                String.class,
                event.eventId().value()))
        .isEqualTo("PENDING_REQUEST_WITHDRAWN");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE correlation_id = ?",
                Integer.class,
                eventCorrelation.value()))
        .isPositive();
    jdbc.update(
        "DELETE FROM identity_access.authorization_grant WHERE principal_kind = 'SERVICE' "
            + "AND principal_issuer = ? AND principal_identifier_type = 'client_id' "
            + "AND principal_identifier = ? AND authority_code = ?",
        ISSUER,
        transferProperties.getOwnerEventConsumerClientId(),
        "TransferReferralCancellation");
    ownerEventHandler.handle(event);
    assertThat(count("SELECT COUNT(*) FROM transfer.consumer_event_receipt")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'TRANSFER_OWNER_EVENT_DENIED' AND correlation_id = ?",
                Integer.class,
                eventCorrelation.value()))
        .isZero();
    assertThat(journeyId).isNotBlank();
  }

  @Test
  void referralCancellationRacingFirstRequestCannotBeLost() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    grantOwnerEventConsumer(scope, scope.originFacilityId(), "TransferReferralCancellation");
    matching.returnApplicable(scope.referralId());
    ApplicabilityGate gate = matching.pauseNextRevalidation();
    CorrelationId eventCorrelation = new CorrelationId("transfer-referral-cancel-race");
    var payload = objectMapper.createObjectNode();
    payload.put("referralReference", scope.referralId());
    payload.put("revisionNumber", 4);
    payload.put("reasonCode", "ORIGIN_NO_LONGER_REQUIRED");
    payload.put("sourceKind", "HUMAN");
    InternalEvent event =
        internalEvent(
            EventType.parse("referral.cancelled.v1"),
            executionContexts.forBackgroundWork(
                ActorReference.service(new IssuerId(ISSUER), "synthetic-referral-event-source"),
                RequestedExecutionScope.facility(
                    directoryReference("Organization", scope.originOrganizationId()),
                    directoryReference("Facility", scope.originFacilityId())),
                eventCorrelation,
                java.util.Optional.empty()),
            "referral/" + scope.referralId(),
            5,
            Instant.now(),
            payload);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<MvcResult> creation =
          executor.submit(() -> createRequest(scope, "referral-cancel-create-race").andReturn());
      assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
      Future<Void> cancellation =
          executor.submit(
              () -> {
                ownerEventHandler.handle(event);
                return null;
              });

      long lockWaitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      boolean cancellationWaitingForTransferLock = false;
      while (System.nanoTime() < lockWaitDeadline) {
        Integer waitingLocks =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted",
                Integer.class);
        if (waitingLocks != null && waitingLocks > 0) {
          cancellationWaitingForTransferLock = true;
          break;
        }
        Thread.sleep(10);
      }
      assertThat(cancellationWaitingForTransferLock)
          .as("Referral cancellation must wait on Transfer's per-Referral coordination lock")
          .isTrue();
      gate.resume.countDown();

      MvcResult created = creation.get(15, TimeUnit.SECONDS);
      cancellation.get(15, TimeUnit.SECONDS);
      assertThat(created.getResponse().getStatus()).isEqualTo(201);
      assertThat(cancellationWaitingForTransferLock).isTrue();
      assertThat(count("SELECT COUNT(*) FROM transfer.request_attempt")).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT request_status FROM transfer.request_attempt", String.class))
          .isEqualTo("WITHDRAWN");
      assertThat(
              jdbc.queryForObject(
                  "SELECT outcome FROM transfer.consumer_event_receipt WHERE event_id = ?",
                  String.class,
                  event.eventId().value()))
          .isEqualTo("PENDING_REQUEST_WITHDRAWN");
    } finally {
      gate.resume.countDown();
      executor.shutdownNow();
      matching.clearApplicabilityGate(gate);
    }
  }

  @Test
  void referralCancellationReleasePreservesEnvelopeCorrelationAndCausation() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "referral-event-capacity-release");
    grantOwnerEventConsumer(scope, scope.originFacilityId(), "TransferReferralCancellation");
    grantOwnerEventConsumer(
        scope, scope.destinationFacilityId(), "TransferReservationReconciliation");
    CorrelationId eventCorrelation = new CorrelationId("transfer-cancel-release-correlation");
    var payload = objectMapper.createObjectNode();
    payload.put("referralReference", scope.referralId());
    payload.put("revisionNumber", 4);
    payload.put("reasonCode", "ORIGIN_NO_LONGER_REQUIRED");
    payload.put("sourceKind", "HUMAN");
    InternalEvent event =
        internalEvent(
            EventType.parse("referral.cancelled.v1"),
            reservationContext(scope, eventCorrelation),
            "referral/" + scope.referralId(),
            5,
            Instant.parse("2026-10-10T10:00:00Z"),
            payload);

    ownerEventHandler.handle(event);

    assertThat(capacityPort.releaseCalls()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                UUID.fromString(transferId)))
        .isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'CAPACITY_RESERVATION_RELEASE' AND correlation_id = ?",
                Integer.class,
                eventCorrelation.value()))
        .isEqualTo(1);
    assertThat(capacityPort.lastReleaseContext().correlationId()).isEqualTo(eventCorrelation);
    assertThat(capacityPort.lastReleaseContext().causationId())
        .hasValue(new org.ayuvahak.platform.core.CausationId(event.eventId().toString()));

    jdbc.update(
        "DELETE FROM identity_access.authorization_grant WHERE principal_kind = 'SERVICE' "
            + "AND principal_issuer = ? AND principal_identifier_type = 'client_id' "
            + "AND principal_identifier = ? AND authority_code IN (?, ?)",
        ISSUER,
        transferProperties.getOwnerEventConsumerClientId(),
        "TransferReferralCancellation",
        "TransferReservationReconciliation");
    ownerEventHandler.handle(event);
    assertThat(capacityPort.releaseCalls()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'TRANSFER_OWNER_EVENT_DENIED' AND correlation_id = ?",
                Integer.class,
                eventCorrelation.value()))
        .isZero();
  }

  @Test
  void unavailableGrantStoreIsDurablyAuditedWithDistinctCodeAndCanRetry() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String response =
        createRequest(scope, "referral-event-grant-store-unavailable-request")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String attemptId = com.jayway.jsonpath.JsonPath.read(response, "$.requestAttemptId");
    CorrelationId eventCorrelation =
        new CorrelationId("transfer-grant-store-unavailable-correlation");
    var payload = objectMapper.createObjectNode();
    payload.put("referralReference", scope.referralId());
    payload.put("revisionNumber", 4);
    payload.put("reasonCode", "ORIGIN_NO_LONGER_REQUIRED");
    payload.put("sourceKind", "HUMAN");
    InternalEvent event =
        internalEvent(
            EventType.parse("referral.cancelled.v1"),
            reservationContext(scope, eventCorrelation),
            "referral/" + scope.referralId(),
            5,
            Instant.parse("2026-10-10T10:00:00Z"),
            payload);

    jdbc.execute(
        "ALTER TABLE identity_access.authorization_grant RENAME TO authorization_grant_unavailable");
    try {
      assertThatThrownBy(() -> ownerEventHandler.handle(event)).isInstanceOf(Exception.class);
    } finally {
      jdbc.execute(
          "ALTER TABLE identity_access.authorization_grant_unavailable RENAME TO authorization_grant");
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT request_status FROM transfer.request_attempt WHERE request_attempt_id = ?",
                String.class,
                UUID.fromString(attemptId)))
        .isEqualTo("PENDING");
    assertThat(count("SELECT COUNT(*) FROM transfer.consumer_event_receipt")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'TRANSFER_OWNER_EVENT_DENIED' AND reason_code = "
                    + "'AUTHORIZATION_STORE_UNAVAILABLE' AND correlation_id = ?",
                Integer.class,
                eventCorrelation.value()))
        .isEqualTo(1);

    grantOwnerEventConsumer(scope, scope.originFacilityId(), "TransferReferralCancellation");
    ownerEventHandler.handle(event);
    assertThat(
            jdbc.queryForObject(
                "SELECT request_status FROM transfer.request_attempt WHERE request_attempt_id = ?",
                String.class,
                UUID.fromString(attemptId)))
        .isEqualTo("WITHDRAWN");
  }

  @Test
  void deniedReferralCancellationEventIsAuditedAndRemainsRetryable() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String response =
        createRequest(scope, "referral-event-authorization-request")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String attemptId = com.jayway.jsonpath.JsonPath.read(response, "$.requestAttemptId");
    CorrelationId eventCorrelation = new CorrelationId("transfer-referral-denied-correlation");
    var payload = objectMapper.createObjectNode();
    payload.put("referralReference", scope.referralId());
    payload.put("revisionNumber", 4);
    payload.put("reasonCode", "ORIGIN_NO_LONGER_REQUIRED");
    payload.put("sourceKind", "HUMAN");
    InternalEvent event =
        internalEvent(
            EventType.parse("referral.cancelled.v1"),
            reservationContext(scope, eventCorrelation),
            "referral/" + scope.referralId(),
            5,
            Instant.parse("2026-10-10T10:00:00Z"),
            payload);

    assertThatThrownBy(() -> ownerEventHandler.handle(event)).isInstanceOf(Exception.class);

    assertThat(
            jdbc.queryForObject(
                "SELECT request_status FROM transfer.request_attempt WHERE request_attempt_id = ?",
                String.class,
                UUID.fromString(attemptId)))
        .isEqualTo("PENDING");
    assertThat(count("SELECT COUNT(*) FROM transfer.consumer_event_receipt")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'TRANSFER_OWNER_EVENT_DENIED' AND outcome = 'DENIED' "
                    + "AND authorization_outcome = 'DENIED' AND correlation_id = ?",
                Integer.class,
                eventCorrelation.value()))
        .isEqualTo(1);

    grantOwnerEventConsumer(scope, scope.originFacilityId(), "TransferReferralCancellation");
    ownerEventHandler.handle(event);

    assertThat(
            jdbc.queryForObject(
                "SELECT request_status FROM transfer.request_attempt WHERE request_attempt_id = ?",
                String.class,
                UUID.fromString(attemptId)))
        .isEqualTo("WITHDRAWN");
    assertThat(count("SELECT COUNT(*) FROM transfer.consumer_event_receipt")).isEqualTo(1);
  }

  @Test
  void capacityAtRiskAndExpiryEventsReconcileByReservationSequence() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "capacity-lifecycle-event");
    grantOwnerEventConsumer(
        scope, scope.destinationFacilityId(), "TransferReservationReconciliation");
    UUID transferUuid = UUID.fromString(transferId);
    UUID reservationId =
        jdbc.queryForObject(
            "SELECT reservation_id FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
            UUID.class,
            transferUuid);

    var atRiskPayload = objectMapper.createObjectNode();
    atRiskPayload.put("transferReference", transferId);
    atRiskPayload.put("reservationReference", reservationId.toString());
    atRiskPayload.put("evidenceVersion", 3);
    atRiskPayload.put("reasonCode", "CAPACITY_BASIS_REDUCED");
    InternalEvent atRisk =
        internalEvent(
            EventType.parse("capacity.reservation-at-risk.v1"),
            reservationContext(scope),
            "capacity-reservation/" + reservationId,
            2,
            Instant.parse("2026-10-10T10:00:00Z"),
            atRiskPayload);
    ownerEventHandler.handle(atRisk);
    ownerEventHandler.handle(atRisk);

    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("COORDINATION_EXCEPTION");

    grantReceivingAuthority(scope, "synthetic-receiver", "AcceptanceAuthority");
    long atRiskVersion =
        jdbc.queryForObject(
            "SELECT version FROM transfer.transfer_record WHERE transfer_id = ?",
            Long.class,
            transferUuid);
    progressCommand(
            scope,
            transferId,
            "capacity-reconfirmation",
            "synthetic-receiver",
            "capacity-reconfirmation-before-recovery",
            "{\"expectedVersion\":"
                + atRiskVersion
                + ",\"occurredAt\":\""
                + Instant.now()
                + "\",\"reasonCode\":\"RECEIVING_AUTHORITY_RECONFIRMED\"}")
        .andExpect(status().isConflict());
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("AT_RISK");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE reservation_id = ?",
                String.class,
                reservationId))
        .isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.progress_fact WHERE transfer_id = ? "
                    + "AND fact_kind = 'CAPACITY_EXCEPTION'",
                Integer.class,
                transferUuid))
        .isEqualTo(1);

    var expiredPayload = objectMapper.createObjectNode();
    expiredPayload.put("transferReference", transferId);
    expiredPayload.put("reservationReference", reservationId.toString());
    expiredPayload.put("expiresAt", "2026-10-10T10:00:00Z");
    expiredPayload.put("reasonCode", "RESERVATION_EXPIRED");
    InternalEvent expired =
        internalEvent(
            EventType.parse("capacity.reservation-expired.v1"),
            reservationContext(scope),
            "capacity-reservation/" + reservationId,
            3,
            Instant.parse("2026-10-10T10:00:00Z"),
            expiredPayload);
    ownerEventHandler.handle(expired);

    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("EXPIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT last_capacity_event_sequence FROM transfer.capacity_reservation_request "
                    + "WHERE transfer_id = ?",
                Long.class,
                transferUuid))
        .isEqualTo(3L);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.consumer_event_receipt WHERE transfer_id = ?",
                Integer.class,
                transferUuid))
        .isEqualTo(2);

    grantFacilityAuthority("synthetic-transfer-receiver", "TransferProgressAuthority", scope);
    long transferVersion =
        jdbc.queryForObject(
            "SELECT version FROM transfer.transfer_record WHERE transfer_id = ?",
            Long.class,
            transferUuid);
    progressCommand(
            scope,
            transferId,
            "arrival",
            "synthetic-transfer-receiver",
            "arrival-during-capacity-exception",
            "{\"expectedVersion\":" + transferVersion + ",\"occurredAt\":\"2026-10-10T10:05:00Z\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ARRIVED"))
        .andExpect(jsonPath("$.exceptionCode").value("ARRIVAL_DURING_COORDINATION_EXCEPTION"));
  }

  @Test
  void capacityLifecycleFailureLogOmitsTransferIdentifier(CapturedOutput output) throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "capacity-lifecycle-safe-log");
    UUID transferUuid = UUID.fromString(transferId);
    grantDirectoryLifecycleLookupOnly();
    jdbc.update(
        "UPDATE capacity.coordination_reservation SET lifecycle_checked_at = "
            + "CURRENT_TIMESTAMP - INTERVAL '2 hours' WHERE transfer_id = ?",
        transferUuid);

    assertThat(capacityReservations.reconcileLifecycle()).isZero();

    assertThat(output.getAll())
        .contains("Capacity reservation lifecycle reconciliation deferred")
        .doesNotContain(transferId);
  }

  @Test
  void expiryEventWaitsForLostReservationResponseAndReconcilesAfterStableReplay() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    ensureReservableCapacityCatalog();
    grantCapacityReporter(scope);
    recordCoordinationSlotObservation(scope, 1, "expiry-before-reservation-response-capacity");
    grantReservationWorker(scope);
    grantCapacityLifecycleWorker(scope);
    String requestAttemptId = createRequestAttempt(scope, "expiry-before-response-request");
    preparePositiveAcceptance(scope, requestAttemptId);
    ReserveGate responseGate = capacityPort.pauseNextResponseAfterCommit();
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
                          "expiry-before-response-acceptance",
                          "{\"expectedVersion\":3,\"occurredAt\":\"2026-10-10T10:50:00Z\"}")
                      .andReturn());
      assertThat(responseGate.entered.await(15, TimeUnit.SECONDS)).isTrue();
      UUID transferId =
          jdbc.queryForObject(
              "SELECT transfer_id FROM transfer.transfer_record WHERE request_attempt_id = ?",
              UUID.class,
              UUID.fromString(requestAttemptId));
      UUID reservationId =
          jdbc.queryForObject(
              "SELECT reservation_id FROM capacity.coordination_reservation WHERE transfer_id = ?",
              UUID.class,
              transferId);

      jdbc.update(
          "UPDATE capacity.coordination_reservation SET interval_start = CURRENT_TIMESTAMP "
              + "- INTERVAL '2 hours', interval_end = CURRENT_TIMESTAMP - INTERVAL '1 hour', "
              + "expires_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE reservation_id = ?",
          reservationId);
      assertThat(capacityReservations.reconcileLifecycle()).isGreaterThan(0);

      InternalEvent expired =
          capacityEventFromOutbox(
              EventType.parse("capacity.reservation-expired.v1"),
              "capacity-reservation/" + reservationId);
      grantOwnerEventConsumer(
          scope, scope.destinationFacilityId(), "TransferReservationReconciliation");

      assertThatThrownBy(() -> ownerEventHandler.handle(expired))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("awaits reservation outcome reconciliation");
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM transfer.consumer_event_receipt WHERE event_id = ?",
                  Integer.class,
                  expired.eventId().value()))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                  String.class,
                  transferId))
          .isEqualTo("IN_PROGRESS");

      jdbc.update(
          "UPDATE transfer.capacity_reservation_request SET next_attempt_at = CURRENT_TIMESTAMP "
              + "- INTERVAL '1 hour' WHERE transfer_id = ? AND outcome = 'IN_PROGRESS'",
          transferId);
      TransferCapacityReservationRecovery restarted =
          beanFactory.createBean(TransferCapacityReservationRecovery.class);
      assertThat(restarted.reconcileDue()).isGreaterThanOrEqualTo(1);
      ownerEventHandler.handle(expired);

      assertThat(
              jdbc.queryForObject(
                  "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                  String.class,
                  transferId))
          .isEqualTo("COORDINATION_EXCEPTION");
      assertThat(
              jdbc.queryForObject(
                  "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                  String.class,
                  transferId))
          .isEqualTo("EXPIRED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT reservation_id FROM transfer.capacity_reservation_request "
                      + "WHERE transfer_id = ?",
                  UUID.class,
                  transferId))
          .isEqualTo(reservationId);
      assertThat(
              jdbc.queryForObject(
                  "SELECT outcome FROM transfer.consumer_event_receipt WHERE event_id = ?",
                  String.class,
                  expired.eventId().value()))
          .isEqualTo("EXPIRED_EXCEPTION_RECORDED");
      responseGate.resume.countDown();
      MvcResult acceptanceResult = acceptance.get(15, TimeUnit.SECONDS);
      assertThat(acceptanceResult.getResponse().getStatus()).isIn(200, 201);
      assertThat(
              jdbc.queryForObject(
                  "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                  String.class,
                  transferId))
          .isEqualTo("COORDINATION_EXCEPTION");
      assertThat(
              jdbc.queryForObject(
                  "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                  String.class,
                  transferId))
          .isEqualTo("EXPIRED");
    } finally {
      responseGate.resume.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void stableReserveReplayUsesCurrentCapacityLifecycleBeforeMarkingTransferReady()
      throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    ensureReservableCapacityCatalog();
    grantCapacityReporter(scope);
    recordCoordinationSlotObservation(scope, 1, "stale-reserved-replay-capacity");
    grantReservationWorker(scope);
    grantCapacityLifecycleWorker(scope);
    String requestAttemptId = createRequestAttempt(scope, "stale-reserved-replay-request");
    preparePositiveAcceptance(scope, requestAttemptId);
    capacityPort.loseNextResponseAfterCommit();

    String accepted = acceptRequest(scope, requestAttemptId, "stale-reserved-replay-acceptance");
    UUID transferId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(accepted, "$.transferId"));
    UUID reservationId =
        jdbc.queryForObject(
            "SELECT reservation_id FROM capacity.coordination_reservation WHERE transfer_id = ?",
            UUID.class,
            transferId);

    assertThat(com.jayway.jsonpath.JsonPath.read(accepted, "$.status").toString())
        .isEqualTo("AWAITING_RESERVATION");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM capacity.reservation_operation WHERE operation_id = ?",
                String.class,
                transferId))
        .isEqualTo("RESERVED");

    jdbc.update(
        "UPDATE capacity.coordination_reservation SET interval_start = CURRENT_TIMESTAMP "
            + "- INTERVAL '3 hours', interval_end = CURRENT_TIMESTAMP - INTERVAL '1 hour', "
            + "expires_at = CURRENT_TIMESTAMP - INTERVAL '1 hour', "
            + "reservation_status = 'EXPIRED', lifecycle_sequence = lifecycle_sequence + 1, "
            + "status_recorded_at = CURRENT_TIMESTAMP WHERE reservation_id = ?",
        reservationId);
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE reservation_id = ?",
                String.class,
                reservationId))
        .isEqualTo("EXPIRED");

    var expiredPayload = objectMapper.createObjectNode();
    expiredPayload.put("transferReference", transferId.toString());
    expiredPayload.put("reservationReference", reservationId.toString());
    expiredPayload.put("expiresAt", Instant.now().minusSeconds(3600).toString());
    expiredPayload.put("reasonCode", "RESERVATION_EXPIRED");
    InternalEvent expired =
        internalEvent(
            EventType.parse("capacity.reservation-expired.v1"),
            reservationContext(scope),
            "capacity-reservation/" + reservationId,
            2,
            Instant.now().minusSeconds(3600),
            expiredPayload);
    jdbc.update(
        "UPDATE transfer.capacity_reservation_request SET next_attempt_at = CURRENT_TIMESTAMP "
            + "- INTERVAL '1 hour' WHERE transfer_id = ? AND outcome = 'UNAVAILABLE'",
        transferId);

    TransferCapacityReservationRecovery restarted =
        beanFactory.createBean(TransferCapacityReservationRecovery.class);
    assertThat(restarted.reconcileDue()).isGreaterThanOrEqualTo(1);

    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("EXPIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_id FROM transfer.capacity_reservation_request "
                    + "WHERE transfer_id = ?",
                UUID.class,
                transferId))
        .isEqualTo(reservationId);
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("AWAITING_RESERVATION");

    grantOwnerEventConsumer(
        scope, scope.destinationFacilityId(), "TransferReservationReconciliation");
    ownerEventHandler.handle(expired);
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("COORDINATION_EXCEPTION");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.consumer_event_receipt WHERE event_id = ?",
                Integer.class,
                expired.eventId().value()))
        .isEqualTo(1);
  }

  @Test
  void capacityReservationOutcomeQueryUsesCurrentLifecycleAndOwnerClock() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "capacity-outcome-current-lifecycle");
    InternalId transferReference = InternalId.parse(transferId);
    UUID reservationId =
        jdbc.queryForObject(
            "SELECT reservation_id FROM capacity.coordination_reservation WHERE transfer_id = ?",
            UUID.class,
            UUID.fromString(transferId));
    var context = reservationContext(scope);

    CapacityReservationOutcome active =
        capacityReservations.getReservationOutcome(context, transferReference, transferReference);
    assertThat(active.status()).isEqualTo(CapacityReservationOutcome.Status.RESERVED);
    assertThat(active.reservationId()).contains(new InternalId(reservationId));
    var readAudit =
        jdbc.queryForMap(
            "SELECT action_kind, actor_kind, actor_reference, scope_kind, facility_reference, "
                + "outcome, authorization_outcome, reason_code FROM audit.audit_record "
                + "WHERE action_code = 'CAPACITY_RESERVATION_OUTCOME_READ' AND target_id = ? "
                + "ORDER BY recorded_at DESC LIMIT 1",
            transferReference.value());
    assertThat(readAudit)
        .containsEntry("action_kind", "SENSITIVE_READ")
        .containsEntry("actor_kind", "SERVICE")
        .containsEntry("actor_reference", "ayuvahak-transfer-reservation-worker")
        .containsEntry("scope_kind", "FACILITY")
        .containsEntry("facility_reference", scope.destinationFacilityId())
        .containsEntry("outcome", "SUCCEEDED")
        .containsEntry("authorization_outcome", "ALLOWED")
        .containsEntry("reason_code", "CURRENT_LIFECYCLE_RESERVED");

    jdbc.update(
        "UPDATE capacity.coordination_reservation SET reservation_status = 'AT_RISK' "
            + "WHERE reservation_id = ?",
        reservationId);
    CapacityReservationOutcome atRisk =
        capacityReservations.getReservationOutcome(context, transferReference, transferReference);
    assertThat(atRisk.status()).isEqualTo(CapacityReservationOutcome.Status.AT_RISK);

    jdbc.update(
        "UPDATE capacity.coordination_reservation SET reservation_status = 'RELEASED' "
            + "WHERE reservation_id = ?",
        reservationId);
    CapacityReservationOutcome released =
        capacityReservations.getReservationOutcome(context, transferReference, transferReference);
    assertThat(released.status()).isEqualTo(CapacityReservationOutcome.Status.RELEASED);

    jdbc.update(
        "UPDATE capacity.coordination_reservation SET reservation_status = 'ACTIVE', "
            + "interval_start = CURRENT_TIMESTAMP "
            + "- INTERVAL '3 hours', interval_end = CURRENT_TIMESTAMP - INTERVAL '1 hour', "
            + "expires_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE reservation_id = ?",
        reservationId);
    CapacityReservationOutcome expiredByOwnerClock =
        capacityReservations.getReservationOutcome(context, transferReference, transferReference);
    assertThat(expiredByOwnerClock.status()).isEqualTo(CapacityReservationOutcome.Status.EXPIRED);
    assertThat(expiredByOwnerClock.reservationId()).contains(new InternalId(reservationId));

    CapacityReservationOutcome unknown =
        capacityReservations.getReservationOutcome(
            context, InternalId.generate(), transferReference);
    assertThat(unknown.status()).isEqualTo(CapacityReservationOutcome.Status.UNKNOWN);
    assertThat(unknown.reservationId()).isEmpty();

    jdbc.execute(
        "CREATE FUNCTION transfer.reject_test_reservation_outcome_audit() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN "
            + "IF NEW.action_code = 'CAPACITY_RESERVATION_OUTCOME_READ' THEN "
            + "RAISE EXCEPTION 'synthetic reservation outcome audit failure'; "
            + "END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_test_reservation_outcome_audit BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION transfer.reject_test_reservation_outcome_audit()");
    try {
      assertThatThrownBy(
              () ->
                  capacityReservations.getReservationOutcome(
                      context, transferReference, transferReference))
          .isInstanceOfSatisfying(
              PlatformProblemException.class,
              failure -> assertThat(failure.code()).isEqualTo(PlatformErrorCode.AUDIT_UNAVAILABLE));
    } finally {
      jdbc.execute(
          "DROP TRIGGER IF EXISTS reject_test_reservation_outcome_audit ON audit.audit_record");
      jdbc.execute("DROP FUNCTION IF EXISTS transfer.reject_test_reservation_outcome_audit()");
    }
  }

  @Test
  void departureFailsClosedWhenCapacityLifecycleIsExpired() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "departure-after-capacity-expiry");
    UUID transferUuid = UUID.fromString(transferId);
    UUID reservationId =
        jdbc.queryForObject(
            "SELECT reservation_id FROM capacity.coordination_reservation WHERE transfer_id = ?",
            UUID.class,
            transferUuid);
    jdbc.update(
        "UPDATE transfer.capacity_reservation_request SET next_attempt_at = CURRENT_TIMESTAMP "
            + "- INTERVAL '1 hour' WHERE transfer_id = ? AND outcome = 'RESERVED'",
        transferUuid);
    TransferCapacityReservationRecovery restarted =
        beanFactory.createBean(TransferCapacityReservationRecovery.class);
    assertThat(restarted.reconcileDue()).isGreaterThanOrEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("RESERVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("READY");
    jdbc.update(
        "UPDATE capacity.coordination_reservation SET interval_start = CURRENT_TIMESTAMP "
            + "- INTERVAL '2 hours', interval_end = CURRENT_TIMESTAMP - INTERVAL '1 hour', "
            + "expires_at = CURRENT_TIMESTAMP - INTERVAL '1 hour', "
            + "reservation_status = 'EXPIRED', lifecycle_sequence = lifecycle_sequence + 1, "
            + "status_recorded_at = CURRENT_TIMESTAMP WHERE reservation_id = ?",
        reservationId);
    jdbc.update(
        "UPDATE transfer.capacity_reservation_request SET next_attempt_at = CURRENT_TIMESTAMP "
            + "- INTERVAL '1 hour' WHERE transfer_id = ? AND outcome = 'RESERVED'",
        transferUuid);
    assertThat(restarted.reconcileDue()).isGreaterThanOrEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("EXPIRED");
    grantFacilityAuthority("synthetic-transfer-mover", "TransferProgressAuthority", scope);

    MvcResult departureResponse =
        progressCommand(
                scope,
                transferId,
                "departure",
                "synthetic-transfer-mover",
                "departure-after-capacity-expiry",
                "{\"expectedVersion\":2,\"occurredAt\":\"2026-10-10T10:00:00Z\","
                    + "\"sourceReference\":\"synthetic:departure-after-expiry\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRANSFER_STATE_CONFLICT"))
            .andReturn();
    String requestCorrelation = departureResponse.getResponse().getHeader("X-Correlation-ID");
    assertThat(requestCorrelation).isNotBlank();
    assertThat(
            jdbc.queryForObject(
                "SELECT correlation_id FROM audit.audit_record "
                    + "WHERE action_code = 'CAPACITY_RESERVATION_OUTCOME_READ' AND target_id = ? "
                    + "ORDER BY recorded_at DESC LIMIT 1",
                String.class,
                transferUuid))
        .isEqualTo(requestCorrelation);

    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("READY");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.progress_fact "
                    + "WHERE transfer_id = ? AND fact_kind = 'DEPARTURE'",
                Integer.class,
                transferUuid))
        .isZero();
  }

  @Test
  void reducedCapacityEvidenceMarksOwnerHoldAtRiskAndWritesSequencedEvent() throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "capacity-at-risk-producer");
    grantCapacityLifecycleWorker(scope);
    UUID transferUuid = UUID.fromString(transferId);
    UUID reservationId =
        jdbc.queryForObject(
            "SELECT reservation_id FROM capacity.coordination_reservation WHERE transfer_id = ?",
            UUID.class,
            transferUuid);

    jdbc.update(
        "UPDATE capacity.coordination_reservation SET lifecycle_checked_at = "
            + "CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE reservation_id = ?",
        reservationId);
    recordCoordinationSlotObservation(scope, 0, "capacity-reduced-after-reservation");
    assertThat(capacityReservations.reconcileLifecycle()).isGreaterThan(0);

    String capacityCorrelation =
        jdbc.queryForObject(
            "SELECT correlation_id FROM audit.audit_record WHERE action_code = "
                + "'CAPACITY_RESERVATION_AT_RISK' AND target_id = ?",
            String.class,
            transferUuid);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'DIRECTORY_CAPACITY_LIFECYCLE_ORGANIZATION_READ' "
                    + "AND correlation_id = ? AND action_kind = 'SENSITIVE_READ' "
                    + "AND actor_reference = ? AND scope_kind = 'PLATFORM' "
                    + "AND target_facility_reference = ? AND target_organization_reference = ?",
                Integer.class,
                capacityCorrelation,
                capacityLifecycleProperties.getWorkerClientId(),
                scope.destinationFacilityId(),
                scope.originOrganizationId()))
        .isEqualTo(1);

    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE reservation_id = ?",
                String.class,
                reservationId))
        .isEqualTo("AT_RISK");
    var event =
        jdbc.queryForMap(
            "SELECT e.event_type, e.aggregate_sequence, e.envelope::text AS envelope, d.state "
                + "FROM platform_eventing.internal_event e "
                + "JOIN platform_eventing.outbox_delivery d USING (event_id) "
                + "WHERE e.event_type = 'capacity.reservation-at-risk.v1' "
                + "AND e.aggregate_ref = ?",
            "capacity-reservation/" + reservationId);
    assertThat(event.get("event_type")).isEqualTo("capacity.reservation-at-risk.v1");
    assertThat(event.get("aggregate_sequence")).isEqualTo(2L);
    assertThat(event.get("state")).isEqualTo("PENDING");
    assertThat(event.get("envelope").toString())
        .contains("evidenceVersion", "CAPACITY_EVIDENCE_NO_LONGER_SUPPORTS_HOLD");

    grantOwnerEventConsumer(
        scope, scope.destinationFacilityId(), "TransferReservationReconciliation");
    outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("COORDINATION_EXCEPTION");

    jdbc.update(
        "UPDATE capacity.coordination_reservation SET lifecycle_checked_at = "
            + "CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE reservation_id = ?",
        reservationId);
    recordCoordinationSlotObservation(scope, 4, "capacity-restored-after-reservation");
    assertThat(capacityReservations.reconcileLifecycle()).isGreaterThan(0);
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE reservation_id = ?",
                String.class,
                reservationId))
        .isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT aggregate_sequence FROM platform_eventing.internal_event "
                    + "WHERE event_type = 'capacity.reservation-recovered.v1' "
                    + "AND aggregate_ref = ?",
                Long.class,
                "capacity-reservation/" + reservationId))
        .isEqualTo(3L);
    outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("COORDINATION_EXCEPTION");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("RESERVED");

    long exceptionVersion =
        jdbc.queryForObject(
            "SELECT version FROM transfer.transfer_record WHERE transfer_id = ?",
            Long.class,
            transferUuid);
    grantOriginAuthority("synthetic-origin-receiver", "AcceptanceAuthority", scope);
    mockMvc
        .perform(
            post("/api/v1/transfers/{transferId}/capacity-reconfirmation", transferId)
                .header("Authorization", "Bearer " + token("synthetic-origin-receiver"))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "origin-cannot-reconfirm-capacity")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":"
                        + exceptionVersion
                        + ",\"occurredAt\":\""
                        + Instant.now()
                        + "\",\"reasonCode\":\"RECEIVING_AUTHORITY_RECONFIRMED\"}"))
        .andExpect(status().isForbidden());
    progressCommand(
            scope,
            transferId,
            "capacity-reconfirmation",
            "synthetic-receiver",
            "capacity-reconfirmation-after-recovery",
            "{\"expectedVersion\":"
                + exceptionVersion
                + ",\"occurredAt\":\""
                + Instant.now()
                + "\",\"reasonCode\":\"RECEIVING_AUTHORITY_RECONFIRMED\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.kind").value("CAPACITY_RECONFIRMATION"))
        .andExpect(jsonPath("$.status").value("READY"));

    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("READY");

    jdbc.update(
        "UPDATE capacity.coordination_reservation SET interval_start = CURRENT_TIMESTAMP "
            + "- INTERVAL '2 hours', interval_end = CURRENT_TIMESTAMP - INTERVAL '1 hour', "
            + "expires_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE reservation_id = ?",
        reservationId);
    assertThat(capacityReservations.reconcileLifecycle()).isGreaterThan(0);
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE reservation_id = ?",
                String.class,
                reservationId))
        .isEqualTo("EXPIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT aggregate_sequence FROM platform_eventing.internal_event "
                    + "WHERE event_type = 'capacity.reservation-expired.v1' "
                    + "AND aggregate_ref = ?",
                Long.class,
                "capacity-reservation/" + reservationId))
        .isEqualTo(4L);
  }

  @Test
  void delayedCapacityRecoveryAfterArrivalRestoresOwnerOutcomeWithoutRewindingTransfer()
      throws Exception {
    Scope scope = createOriginAndDestination();
    String transferId = createReadyTransfer(scope, "delayed-capacity-recovery-after-arrival");
    grantCapacityLifecycleWorker(scope);
    grantOwnerEventConsumer(
        scope, scope.destinationFacilityId(), "TransferReservationReconciliation");
    UUID transferUuid = UUID.fromString(transferId);
    UUID reservationId =
        jdbc.queryForObject(
            "SELECT reservation_id FROM capacity.coordination_reservation WHERE transfer_id = ?",
            UUID.class,
            transferUuid);
    String aggregateRef = "capacity-reservation/" + reservationId;

    jdbc.update(
        "UPDATE capacity.coordination_reservation SET lifecycle_checked_at = "
            + "CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE reservation_id = ?",
        reservationId);
    recordCoordinationSlotObservation(scope, 0, "delayed-recovery-capacity-reduced");
    assertThat(capacityReservations.reconcileLifecycle()).isGreaterThan(0);
    ownerEventHandler.handle(
        capacityEventFromOutbox(EventType.parse("capacity.reservation-at-risk.v1"), aggregateRef));
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("COORDINATION_EXCEPTION");

    jdbc.update(
        "UPDATE capacity.coordination_reservation SET lifecycle_checked_at = "
            + "CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE reservation_id = ?",
        reservationId);
    recordCoordinationSlotObservation(scope, 4, "delayed-recovery-capacity-restored");
    assertThat(capacityReservations.reconcileLifecycle()).isGreaterThan(0);

    grantFacilityAuthority("synthetic-transfer-receiver", "TransferProgressAuthority", scope);
    long exceptionVersion =
        jdbc.queryForObject(
            "SELECT version FROM transfer.transfer_record WHERE transfer_id = ?",
            Long.class,
            transferUuid);
    progressCommand(
            scope,
            transferId,
            "arrival",
            "synthetic-transfer-receiver",
            "arrival-before-delayed-capacity-recovery",
            "{\"expectedVersion\":"
                + exceptionVersion
                + ",\"occurredAt\":\"2026-10-10T10:45:00Z\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ARRIVED"));

    ownerEventHandler.handle(
        capacityEventFromOutbox(
            EventType.parse("capacity.reservation-recovered.v1"), aggregateRef));

    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("ARRIVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferUuid))
        .isEqualTo("RESERVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.consumer_event_receipt "
                    + "WHERE event_id = (SELECT event_id FROM platform_eventing.internal_event "
                    + "WHERE event_type = 'capacity.reservation-recovered.v1' "
                    + "AND aggregate_ref = ?)",
                String.class,
                aggregateRef))
        .isEqualTo("CAPACITY_OUTCOME_RECOVERED_AFTER_PROGRESS");
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
            + "INTERVAL '1 hour' WHERE transfer_id = ?",
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
    CapacityReservationResult separateExpiredOperation =
        capacityReservations.reserve(
            context, conflictingOperation, "different-operation-same-transfer");
    assertThat(separateExpiredOperation.outcome())
        .isEqualTo(CapacityReservationResult.Outcome.INVALID);
    assertThat(separateExpiredOperation.reasonCode()).contains("RESERVATION_EXPIRY_NOT_FUTURE");
    assertThat(separateExpiredOperation.reservationId()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity.reservation_operation WHERE transfer_id = ?",
                Integer.class,
                transferId.value()))
        .isEqualTo(2);

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
    while (Instant.now().isBefore(expiresAt.plusSeconds(2))) {
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
    assertThat(capacityPort.idempotencyKeys())
        .containsExactly("transfer-capacity-" + transferId, "transfer-capacity-" + transferId);

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
        .isEqualTo("EXPIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT reason_code FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                transferId))
        .isEqualTo("CAPACITY_RESERVATION_EXPIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_id FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                UUID.class,
                transferId))
        .isNotNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM capacity.reservation_operation WHERE operation_id = ?",
                String.class,
                transferId))
        .isEqualTo("RESERVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity.reservation_command_receipt WHERE operation_id = ?",
                Integer.class,
                transferId))
        .isEqualTo(1);
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

  private void grantOwnerEventConsumer(Scope scope, String facilityId, String authority) {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, organization_issuer, organization_identifier_type, organization_identifier, "
            + "facility_issuer, facility_identifier_type, facility_identifier, grant_status, created_at) "
            + "VALUES (gen_random_uuid(), 'SERVICE', ?, 'client_id', ?, "
            + "?, 'FACILITY', ?, 'Organization', ?, ?, "
            + "'Facility', ?, 'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        transferProperties.getOwnerEventConsumerClientId(),
        authority,
        DIRECTORY_ISSUER,
        scope.originOrganizationId(),
        DIRECTORY_ISSUER,
        facilityId);
  }

  private void grantCapacityLifecycleWorker(Scope scope) {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, organization_issuer, organization_identifier_type, organization_identifier, "
            + "facility_issuer, facility_identifier_type, facility_identifier, grant_status, created_at) "
            + "VALUES (gen_random_uuid(), 'SERVICE', ?, 'client_id', ?, 'CapacityReservation', "
            + "'FACILITY', ?, 'Organization', ?, ?, 'Facility', ?, 'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        capacityLifecycleProperties.getWorkerClientId(),
        DIRECTORY_ISSUER,
        scope.originOrganizationId(),
        DIRECTORY_ISSUER,
        scope.destinationFacilityId());
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, grant_status, created_at) VALUES (gen_random_uuid(), 'SERVICE', ?, "
            + "'client_id', ?, 'DirectoryCapacityLifecycleOrganizationRead', 'PLATFORM', "
            + "'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        capacityLifecycleProperties.getWorkerClientId());
  }

  private void grantDirectoryLifecycleLookupOnly() {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, grant_status, created_at) VALUES (gen_random_uuid(), 'SERVICE', ?, "
            + "'client_id', ?, 'DirectoryCapacityLifecycleOrganizationRead', 'PLATFORM', "
            + "'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        capacityLifecycleProperties.getWorkerClientId());
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

  private void grantFacilityAuthorityAt(
      String subject, String authority, String organizationId, String facilityId) {
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
        organizationId,
        DIRECTORY_ISSUER,
        facilityId);
  }

  private void grantOrganizationAuthority(String subject, String authority, String organizationId) {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, organization_issuer, organization_identifier_type, organization_identifier, "
            + "grant_status, created_at) VALUES (gen_random_uuid(), 'HUMAN', ?, 'subject', ?, ?, "
            + "'ORGANIZATION', ?, 'Organization', ?, 'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        subject,
        authority,
        DIRECTORY_ISSUER,
        organizationId);
  }

  private void grantOriginAuthority(String subject, String authority, Scope scope) {
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
        scope.originFacilityId());
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
    return createReadyTransfer(scope, fixtureName, false);
  }

  private String createReadyTransfer(Scope scope, String fixtureName, boolean transportRequired)
      throws Exception {
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    ensureReservableCapacityCatalog();
    grantCapacityReporter(scope);
    recordCoordinationSlotObservation(scope, 1, fixtureName + "-capacity");
    grantReservationWorker(scope);
    String requestAttempt =
        createRequestAttempt(scope, fixtureName + "-request", transportRequired);
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

  private org.springframework.test.web.servlet.ResultActions progressCommandAtOrigin(
      Scope scope, String transferId, String subject, String idempotencyKey, String body)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/transfers/{transferId}/abandonment", transferId)
            .header("Authorization", "Bearer " + token(subject))
            .header("X-AyuVahak-Execution-Scope", "FACILITY")
            .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
            .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
            .header("Idempotency-Key", idempotencyKey)
            .contentType("application/json")
            .content(body));
  }

  private org.ayuvahak.platform.executioncontext.ExecutionContext reservationContext(Scope scope) {
    return reservationContext(scope, CorrelationId.generate());
  }

  private org.ayuvahak.platform.executioncontext.ExecutionContext reservationContext(
      Scope scope, CorrelationId correlationId) {
    return executionContexts.forBackgroundWork(
        ActorReference.service(new IssuerId(ISSUER), "ayuvahak-transfer-reservation-worker"),
        RequestedExecutionScope.facility(
            directoryReference("Organization", scope.originOrganizationId()),
            directoryReference("Facility", scope.destinationFacilityId())),
        correlationId,
        java.util.Optional.empty());
  }

  private InternalEvent internalEvent(
      EventType type,
      org.ayuvahak.platform.executioncontext.ExecutionContext producer,
      String aggregateRef,
      long sequence,
      Instant occurredAt,
      tools.jackson.databind.node.ObjectNode payload) {
    Instant recordedAt = Instant.now();
    EventEnvelope envelope =
        new EventEnvelope(
            InternalId.generate(),
            type,
            type.context(),
            new OccurredAt(occurredAt),
            new RecordedAt(recordedAt),
            producer,
            java.util.Optional.of(new AggregatePosition(aggregateRef, sequence)),
            payload);
    return new InternalEvent(
        envelope.eventId(),
        envelope.eventType(),
        envelope.producerContext(),
        envelope.aggregatePosition(),
        eventCodec.encode(envelope));
  }

  private InternalEvent capacityEventFromOutbox(EventType eventType, String aggregateRef)
      throws Exception {
    var row =
        jdbc.queryForMap(
            "SELECT e.event_id, e.event_type, e.producer_context, e.aggregate_ref, "
                + "e.aggregate_sequence, e.envelope::text AS envelope "
                + "FROM platform_eventing.internal_event e WHERE e.event_type = ? "
                + "AND e.aggregate_ref = ? ORDER BY e.aggregate_sequence DESC LIMIT 1",
            eventType.toString(),
            aggregateRef);
    String json = (String) row.get("envelope");
    var envelope = objectMapper.readTree(json);
    return new InternalEvent(
        InternalId.parse(envelope.get("eventId").stringValue()),
        EventType.parse((String) row.get("event_type")),
        (String) row.get("producer_context"),
        java.util.Optional.of(
            new AggregatePosition(
                (String) row.get("aggregate_ref"),
                ((Number) row.get("aggregate_sequence")).longValue())),
        json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private InternalEvent transportEventFromOutbox(EventType eventType, String aggregateRef)
      throws Exception {
    var row =
        jdbc.queryForMap(
            "SELECT e.event_id, e.event_type, e.producer_context, e.aggregate_ref, "
                + "e.aggregate_sequence, e.envelope::text AS envelope "
                + "FROM platform_eventing.internal_event e WHERE e.event_type = ? "
                + "AND e.aggregate_ref = ? ORDER BY e.aggregate_sequence DESC LIMIT 1",
            eventType.toString(),
            aggregateRef);
    String json = (String) row.get("envelope");
    return new InternalEvent(
        InternalId.parse(row.get("event_id").toString()),
        EventType.parse((String) row.get("event_type")),
        (String) row.get("producer_context"),
        java.util.Optional.of(
            new AggregatePosition(
                (String) row.get("aggregate_ref"),
                ((Number) row.get("aggregate_sequence")).longValue())),
        json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
  void rejectedDestinationCanBeFollowedByAnOrderedRevalidatedAttempt() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String firstAttemptId = createRequestAttempt(scope, "reroute-first-request");
    UUID journeyId =
        jdbc.queryForObject(
            "SELECT journey_id FROM transfer.request_attempt WHERE request_attempt_id = ?",
            UUID.class,
            UUID.fromString(firstAttemptId));
    grantReceivingAuthority(scope, "synthetic-receiver", "AcceptanceAuthority");

    receivingCommand(
            scope,
            firstAttemptId,
            "rejection",
            "synthetic-receiver",
            "reroute-first-rejection",
            "{\"expectedVersion\":1,\"reasonCode\":\"DESTINATION_UNAVAILABLE\","
                + "\"occurredAt\":\"2026-10-10T10:20:00Z\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("REJECTED"));

    String secondDestination = InternalId.generate().toString();
    matching.returnApplicable(scope.referralId());
    mockMvc
        .perform(
            post("/api/v1/transfer-journeys/{journeyId}/request-attempts", journeyId)
                .header("Authorization", "Bearer " + token(SENDER))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "reroute-second-request")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":1,\"referralRevision\":4,\"recommendationId\":\""
                        + scope.recommendationId()
                        + "\",\"candidateFacilityId\":\""
                        + secondDestination
                        + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.attemptNumber").value(2))
        .andExpect(jsonPath("$.destinationFacilityId").value(secondDestination));

    assertThat(matching.calls()).isEqualTo(2);
    assertThat(
            count(
                "SELECT COUNT(*) FROM transfer.request_attempt WHERE journey_id = '"
                    + journeyId
                    + "'::uuid"))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT request_status FROM transfer.request_attempt WHERE request_attempt_id = ?",
                String.class,
                UUID.fromString(firstAttemptId)))
        .isEqualTo("REJECTED");
  }

  @Test
  void rerouteRequiresTheCallerExpectedJourneyVersion() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable(scope.referralId());
    String firstAttemptId = createRequestAttempt(scope, "reroute-stale-version-request");
    UUID journeyId =
        jdbc.queryForObject(
            "SELECT journey_id FROM transfer.request_attempt WHERE request_attempt_id = ?",
            UUID.class,
            UUID.fromString(firstAttemptId));
    grantReceivingAuthority(scope, "synthetic-stale-version-receiver", "AcceptanceAuthority");
    receivingCommand(
            scope,
            firstAttemptId,
            "rejection",
            "synthetic-stale-version-receiver",
            "reroute-stale-version-rejection",
            "{\"expectedVersion\":1,\"reasonCode\":\"DESTINATION_UNAVAILABLE\","
                + "\"occurredAt\":\"2026-10-10T10:20:00Z\"}")
        .andExpect(status().isCreated());

    String secondDestination = scope.destinationFacilityId();
    matching.returnApplicable(scope.referralId());
    String secondResponse =
        mockMvc
            .perform(
                post("/api/v1/transfer-journeys/{journeyId}/request-attempts", journeyId)
                    .header("Authorization", "Bearer " + token(SENDER))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                    .header("Idempotency-Key", "reroute-version-advance")
                    .contentType("application/json")
                    .content(
                        "{\"expectedVersion\":1,\"referralRevision\":4,\"recommendationId\":\""
                            + scope.recommendationId()
                            + "\",\"candidateFacilityId\":\""
                            + secondDestination
                            + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.attemptNumber").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String secondAttemptId =
        com.jayway.jsonpath.JsonPath.read(secondResponse, "$.requestAttemptId");
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM transfer.journey WHERE journey_id = ?", Long.class, journeyId))
        .isEqualTo(2L);
    receivingCommand(
            scope,
            secondAttemptId,
            "rejection",
            "synthetic-stale-version-receiver",
            "reroute-stale-version-second-rejection",
            "{\"expectedVersion\":1,\"reasonCode\":\"DESTINATION_UNAVAILABLE\","
                + "\"occurredAt\":\"2026-10-10T10:21:00Z\"}")
        .andExpect(status().isCreated());
    matching.returnApplicable(scope.referralId());

    mockMvc
        .perform(
            post("/api/v1/transfer-journeys/{journeyId}/request-attempts", journeyId)
                .header("Authorization", "Bearer " + token(SENDER))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "reroute-stale-version")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":1,\"referralRevision\":4,\"recommendationId\":\""
                        + scope.recommendationId()
                        + "\",\"candidateFacilityId\":\""
                        + scope.destinationFacilityId()
                        + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSFER_STATE_CONFLICT"));

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.request_attempt WHERE journey_id = ?",
                Integer.class,
                journeyId))
        .isEqualTo(2);
  }

  @Test
  void terminalUnfulfilledJourneyCanBeClosedWithAnOrderedReferralEvent() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    grantOriginAuthority(SENDER, "TransferJourneyClosureAuthority", scope);
    matching.returnApplicable(scope.referralId());
    String requestAttemptId = createRequestAttempt(scope, "unfulfilled-closure-request");
    UUID journeyId =
        jdbc.queryForObject(
            "SELECT journey_id FROM transfer.request_attempt WHERE request_attempt_id = ?",
            UUID.class,
            UUID.fromString(requestAttemptId));
    grantReceivingAuthority(scope, "synthetic-closure-receiver", "AcceptanceAuthority");
    receivingCommand(
            scope,
            requestAttemptId,
            "rejection",
            "synthetic-closure-receiver",
            "unfulfilled-closure-rejection",
            "{\"expectedVersion\":1,\"reasonCode\":\"DESTINATION_UNAVAILABLE\","
                + "\"occurredAt\":\"2026-10-10T10:20:00Z\"}")
        .andExpect(status().isCreated());

    var closed =
        mockMvc
            .perform(
                post("/api/v1/transfer-journeys/{journeyId}/closure", journeyId)
                    .header("Authorization", "Bearer " + token(SENDER))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                    .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                    .header("Idempotency-Key", "unfulfilled-closure")
                    .contentType("application/json")
                    .content(
                        "{\"expectedVersion\":1,\"reasonCode\":\"NO_SAFE_DESTINATION\","
                            + "\"occurredAt\":\"2026-10-10T10:30:00Z\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.journeyId").value(journeyId.toString()))
            .andExpect(jsonPath("$.outcome").value("UNFULFILLED"))
            .andExpect(jsonPath("$.reasonCode").value("NO_SAFE_DESTINATION"))
            .andExpect(jsonPath("$.journeyVersion").value(2))
            .andReturn();

    mockMvc
        .perform(
            post("/api/v1/transfer-journeys/{journeyId}/closure", journeyId)
                .header("Authorization", "Bearer " + token(SENDER))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "unfulfilled-closure")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":1,\"reasonCode\":\"NO_SAFE_DESTINATION\","
                        + "\"occurredAt\":\"2026-10-10T10:30:00Z\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"));

    receivingCommand(
            scope,
            requestAttemptId,
            "acceptance",
            "synthetic-closure-receiver",
            "accept-after-unfulfilled-closure",
            "{\"expectedVersion\":2,\"occurredAt\":\"2026-10-10T10:35:00Z\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSFER_STATE_CONFLICT"));
    mockMvc
        .perform(
            post("/api/v1/transfer-journeys/{journeyId}/request-attempts", journeyId)
                .header("Authorization", "Bearer " + token(SENDER))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "reroute-after-unfulfilled-closure")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":2,\"referralRevision\":5,\"recommendationId\":\""
                        + scope.recommendationId()
                        + "\",\"candidateFacilityId\":\""
                        + scope.destinationFacilityId()
                        + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSFER_STATE_CONFLICT"));

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.journey_closure WHERE journey_id = ?",
                Integer.class,
                journeyId))
        .isEqualTo(1);
    var event =
        jdbc.queryForMap(
            "SELECT e.aggregate_sequence, e.aggregate_ref, e.envelope::text AS envelope "
                + "FROM platform_eventing.internal_event e "
                + "WHERE e.event_type = 'transfer.unfulfilled.v1'");
    assertThat(event.get("aggregate_sequence")).isEqualTo(2L);
    assertThat(event.get("aggregate_ref")).isEqualTo("transfer-journey/" + journeyId);
    assertThat(event.get("envelope").toString())
        .contains("referralReference", scope.referralId(), "journeyReference", "UNFULFILLED");
    assertThat(closed.getResponse().getContentAsString()).contains("NO_SAFE_DESTINATION");
  }

  @Test
  void unfulfilledClosureRejectsPendingRequest() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    grantOriginAuthority(SENDER, "TransferJourneyClosureAuthority", scope);
    matching.returnApplicable(scope.referralId());
    String requestAttemptId = createRequestAttempt(scope, "pending-closure-request");
    UUID journeyId =
        jdbc.queryForObject(
            "SELECT journey_id FROM transfer.request_attempt WHERE request_attempt_id = ?",
            UUID.class,
            UUID.fromString(requestAttemptId));

    mockMvc
        .perform(
            post("/api/v1/transfer-journeys/{journeyId}/closure", journeyId)
                .header("Authorization", "Bearer " + token(SENDER))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                .header("Idempotency-Key", "pending-request-closure")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":1,\"reasonCode\":\"NO_SAFE_DESTINATION\","
                        + "\"occurredAt\":\"2026-10-10T10:30:00Z\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSFER_STATE_CONFLICT"));

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.journey_closure WHERE journey_id = ?",
                Integer.class,
                journeyId))
        .isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM platform_eventing.internal_event WHERE event_type = "
                    + "'transfer.unfulfilled.v1'"))
        .isZero();
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
          .as(
              "acceptance status=%s correlation=%s body=%s; withdrawal status=%s correlation=%s body=%s",
              accepted.getResponse().getStatus(),
              accepted.getResponse().getHeader("X-Correlation-ID"),
              accepted.getResponse().getContentAsString(),
              withdrawn.getResponse().getStatus(),
              withdrawn.getResponse().getHeader("X-Correlation-ID"),
              withdrawn.getResponse().getContentAsString())
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

  private org.springframework.test.web.servlet.ResultActions receivingCommandAt(
      String organizationId,
      String facilityId,
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
            .header("X-AyuVahak-Organization-Id", organizationId)
            .header("X-AyuVahak-Facility-Id", facilityId)
            .header("Idempotency-Key", idempotencyKey)
            .contentType("application/json")
            .content(body));
  }

  private org.springframework.test.web.servlet.ResultActions postDestinationChangeCommand(
      String transferId,
      String operationId,
      ReceivingFacility facility,
      String command,
      String subject,
      String idempotencyKey,
      String body)
      throws Exception {
    return mockMvc.perform(
        post(
                "/api/v1/transfers/{transferId}/destination-changes/{operationId}/{command}",
                transferId,
                operationId,
                command)
            .header("Authorization", "Bearer " + token(subject))
            .header("X-AyuVahak-Execution-Scope", "FACILITY")
            .header("X-AyuVahak-Organization-Id", facility.organizationId())
            .header("X-AyuVahak-Facility-Id", facility.facilityId())
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
    return createRequestAttempt(scope, idempotencyKey, false);
  }

  private String createRequestAttempt(Scope scope, String idempotencyKey, boolean transportRequired)
      throws Exception {
    return com.jayway.jsonpath.JsonPath.read(
        createRequest(scope, idempotencyKey, SENDER, transportRequired)
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
      int requestAttemptWaiters =
          count(
              "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() "
                  + "AND wait_event_type = 'Lock' "
                  + "AND query ILIKE 'UPDATE transfer.request_attempt SET request_status = %'");
      int journeyWaiters =
          count(
              "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() "
                  + "AND wait_event_type = 'Lock' "
                  + "AND query ILIKE 'SELECT journey_id, referral_id, origin_organization_id%' "
                  + "AND query ILIKE '%FROM transfer.journey%' "
                  + "AND query ILIKE '%FOR UPDATE%'");
      if (requestAttemptWaiters >= 1 && journeyWaiters >= 1) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError(
        "Acceptance did not reach the request compare-and-set while withdrawal waited on the "
            + "Journey lock.");
  }

  private void awaitTransportRequestLockWaiters(int expectedWaiters) throws Exception {
    Instant timeout = Instant.now().plusSeconds(10);
    while (Instant.now().isBefore(timeout)) {
      int waiters =
          count(
              "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() "
                  + "AND wait_event_type = 'Lock' "
                  + "AND query ILIKE '%FROM transport.transport_request%' "
                  + "AND query ILIKE '%FOR UPDATE%'");
      if (waiters >= expectedWaiters) return;
      Thread.sleep(20);
    }
    throw new AssertionError(
        "Concurrent cancellation commands did not both reach the Transport request lock.");
  }

  private Future<MvcResult> cancellationRequest(
      ExecutorService executor,
      CountDownLatch ready,
      CountDownLatch start,
      Scope scope,
      String requestUrl,
      String operator,
      String idempotencyKey) {
    return executor.submit(
        () -> {
          ready.countDown();
          start.await();
          return mockMvc
              .perform(
                  post(requestUrl)
                      .header("Authorization", "Bearer " + token(operator))
                      .header("X-AyuVahak-Execution-Scope", "FACILITY")
                      .header("X-AyuVahak-Organization-Id", scope.originOrganizationId())
                      .header("X-AyuVahak-Facility-Id", scope.originFacilityId())
                      .header("Idempotency-Key", idempotencyKey)
                      .contentType("application/json")
                      .content("{\"reasonCode\":\"TRANSFER_CANCELLED\"}"))
              .andReturn();
        });
  }

  private org.springframework.test.web.servlet.ResultActions createRequest(
      Scope scope, String idempotencyKey) throws Exception {
    return createRequest(scope, idempotencyKey, SENDER, false);
  }

  private org.springframework.test.web.servlet.ResultActions createRequest(
      Scope scope, String idempotencyKey, String subject) throws Exception {
    return createRequest(scope, idempotencyKey, subject, false);
  }

  private org.springframework.test.web.servlet.ResultActions createRequest(
      Scope scope, String idempotencyKey, String subject, boolean transportRequired)
      throws Exception {
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
                    + "\",\"transportRequired\":"
                    + transportRequired
                    + "}"));
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

  private ReceivingFacility createReceivingFacility(String name) {
    InternalId organizationId = InternalId.generate();
    InternalId facilityId = InternalId.generate();
    Instant now = Instant.now();
    ActorReference actor =
        ActorReference.human(new IssuerId("urn:synthetic:fixture"), "directory-fixture");
    directory.insertOrganization(
        new Organization(
            organizationId,
            name,
            java.util.Optional.empty(),
            new DirectorySource(
                new ExternalReference(
                    new IssuerId("urn:synthetic:directory-source"),
                    "Organization",
                    "organization-" + UUID.randomUUID())),
            new DirectoryProvenance("MANUAL_REVIEW", now, actor, now, "TEST_FIXTURE"),
            DirectoryLifecycleStatus.ACTIVE,
            1));
    directory.insertFacility(
        new Facility(
            facilityId,
            organizationId,
            name + " facility",
            new DirectorySource(
                new ExternalReference(
                    new IssuerId("urn:synthetic:directory-source"),
                    "Facility",
                    "facility-" + UUID.randomUUID())),
            new DirectoryProvenance("MANUAL_REVIEW", now, actor, now, "TEST_FIXTURE"),
            DirectoryLifecycleStatus.ACTIVE,
            1));
    return new ReceivingFacility(organizationId.toString(), facilityId.toString());
  }

  private org.springframework.test.web.servlet.ResultActions organizationJourneyView(
      String journeyId, String subject, String organizationId) throws Exception {
    return mockMvc.perform(
        get("/api/v1/transfer-journeys/{journeyId}", journeyId)
            .header("Authorization", "Bearer " + token(subject))
            .header("X-AyuVahak-Execution-Scope", "ORGANIZATION")
            .header("X-AyuVahak-Organization-Id", organizationId)
            .param("page", "0")
            .param("size", "10"));
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

  private void grantReceivingAuthorityAt(
      String organizationId, String facilityId, String subject, String authority) {
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
        organizationId,
        DIRECTORY_ISSUER,
        facilityId);
  }

  private void grantTransportWorker(Scope scope) {
    for (String authority :
        List.of(
            "TransportRequestAuthority",
            "TransferTransportRequestEligibility",
            "TransportCancellationAuthority",
            "TransportDestinationChangeAuthority",
            "TransferTransportDestinationChangeEligibility")) {
      jdbc.update(
          "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
              + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
              + "scope_kind, organization_issuer, organization_identifier_type, "
              + "organization_identifier, facility_issuer, facility_identifier_type, "
              + "facility_identifier, grant_status, created_at) VALUES (gen_random_uuid(), "
              + "'SERVICE', ?, 'client_id', ?, ?, 'FACILITY', ?, 'Organization', ?, "
              + "?, 'Facility', ?, 'ACTIVE', CURRENT_TIMESTAMP)",
          ISSUER,
          TRANSPORT_WORKER,
          authority,
          DIRECTORY_ISSUER,
          scope.originOrganizationId(),
          DIRECTORY_ISSUER,
          scope.originFacilityId());
    }
  }

  private void grantTransportAuthority(Scope scope, String subject, String authority) {
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
        scope.originFacilityId());
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
    return token(subject, null);
  }

  private String serviceToken(String clientId) throws Exception {
    return token(clientId, clientId);
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

    Scope withDestinationFacility(String facilityId) {
      return new Scope(
          originOrganizationId, originFacilityId, facilityId, referralId, recommendationId);
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

  private record ReceivingFacility(String organizationId, String facilityId) {}

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
    private final java.util.concurrent.atomic.AtomicBoolean loseReleaseResponse =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<ReserveGate> nextReserveGate =
        new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<RuntimeException> nextFailure =
        new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<RuntimeException> nextReleaseFailure =
        new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.CopyOnWriteArrayList<String> idempotencyKeys =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<UUID> releaseOperationIds =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger releaseCallCount =
        new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger reservationOutcomeReadCallCount =
        new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicReference<
            org.ayuvahak.platform.executioncontext.ExecutionContext>
        lastReleaseContext = new java.util.concurrent.atomic.AtomicReference<>();

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
      ReserveGate gate = nextReserveGate.getAndSet(null);
      if (gate != null && !gate.afterCommit) {
        pause(gate);
      }
      CapacityReservationResult result = commands.reserve(context, request, idempotencyKey);
      if (gate != null && gate.afterCommit) {
        pause(gate);
      }
      if (loseResponse.compareAndSet(true, false)) {
        throw new IllegalStateException("Synthetic lost Capacity response after commit");
      }
      return result;
    }

    @Override
    public org.ayuvahak.capacity.domain.CapacityReleaseResult release(
        org.ayuvahak.platform.executioncontext.ExecutionContext context, InternalId transferId) {
      releaseCallCount.incrementAndGet();
      lastReleaseContext.set(context);
      RuntimeException configuredFailure = nextReleaseFailure.getAndSet(null);
      if (configuredFailure != null) {
        throw configuredFailure;
      }
      return commands.release(context, transferId);
    }

    @Override
    public org.ayuvahak.capacity.domain.CapacityReleaseResult release(
        org.ayuvahak.platform.executioncontext.ExecutionContext context,
        InternalId releaseOperationId,
        InternalId reservationOperationId,
        InternalId transferId) {
      releaseCallCount.incrementAndGet();
      releaseOperationIds.add(UUID.fromString(releaseOperationId.toString()));
      lastReleaseContext.set(context);
      RuntimeException configuredFailure = nextReleaseFailure.getAndSet(null);
      if (configuredFailure != null) {
        throw configuredFailure;
      }
      CapacityReleaseResult result =
          commands.release(context, releaseOperationId, reservationOperationId, transferId);
      if (loseReleaseResponse.compareAndSet(true, false)) {
        throw new IllegalStateException("Synthetic lost Capacity release response after commit");
      }
      return result;
    }

    @Override
    public CapacityReservationOutcome getReservationOutcome(
        org.ayuvahak.platform.executioncontext.ExecutionContext context,
        InternalId operationId,
        InternalId transferId) {
      reservationOutcomeReadCallCount.incrementAndGet();
      return commands.getReservationOutcome(context, operationId, transferId);
    }

    void loseNextResponseAfterCommit() {
      loseResponse.set(true);
    }

    void failNext(RuntimeException failure) {
      nextFailure.set(failure);
    }

    void failNextRelease(RuntimeException failure) {
      nextReleaseFailure.set(failure);
    }

    void loseNextReleaseResponseAfterCommit() {
      loseReleaseResponse.set(true);
    }

    ReserveGate pauseNextReserve() {
      ReserveGate gate = new ReserveGate(false);
      nextReserveGate.set(gate);
      return gate;
    }

    ReserveGate pauseNextResponseAfterCommit() {
      ReserveGate gate = new ReserveGate(true);
      nextReserveGate.set(gate);
      return gate;
    }

    private static void pause(ReserveGate gate) {
      gate.entered.countDown();
      try {
        if (!gate.resume.await(15, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Synthetic Capacity reservation barrier timed out");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Synthetic Capacity reservation barrier interrupted");
      }
    }

    java.util.List<String> idempotencyKeys() {
      return java.util.List.copyOf(idempotencyKeys);
    }

    int releaseCalls() {
      return releaseCallCount.get();
    }

    java.util.List<UUID> releaseOperationIds() {
      return java.util.List.copyOf(releaseOperationIds);
    }

    int reservationOutcomeReadCalls() {
      return reservationOutcomeReadCallCount.get();
    }

    org.ayuvahak.platform.executioncontext.ExecutionContext lastReleaseContext() {
      return lastReleaseContext.get();
    }

    void reset() {
      loseResponse.set(false);
      loseReleaseResponse.set(false);
      nextFailure.set(null);
      nextReleaseFailure.set(null);
      releaseCallCount.set(0);
      releaseOperationIds.clear();
      reservationOutcomeReadCallCount.set(0);
      lastReleaseContext.set(null);
      ReserveGate gate = nextReserveGate.getAndSet(null);
      if (gate != null) {
        gate.resume.countDown();
      }
      idempotencyKeys.clear();
    }
  }

  static final class ReserveGate {
    final boolean afterCommit;
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);

    ReserveGate(boolean afterCommit) {
      this.afterCommit = afterCommit;
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
    private volatile ApplicabilityGate nextGate;

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
      RecommendationApplicabilityResult result =
          new RecommendationApplicabilityResult(
              recommendationId, candidateFacility, effectiveStatus, reasons, Instant.now());
      ApplicabilityGate gate = nextGate;
      if (gate != null && nextGate == gate) {
        nextGate = null;
        gate.entered.countDown();
        try {
          if (!gate.resume.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting to resume Matching applicability.");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Matching applicability was interrupted.", interrupted);
        }
      }
      return result;
    }

    ApplicabilityGate pauseNextRevalidation() {
      ApplicabilityGate gate = new ApplicabilityGate();
      nextGate = gate;
      return gate;
    }

    void clearApplicabilityGate(ApplicabilityGate gate) {
      if (nextGate == gate) {
        nextGate = null;
      }
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

  static final class ApplicabilityGate {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);
  }
}
