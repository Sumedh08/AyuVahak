package org.ayuvahak.platform.eventing;

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
import java.util.Set;
import org.ayuvahak.platform.authorization.adapter.api.PlatformAccessGrantController;
import org.ayuvahak.platform.authorization.application.BootstrapGrantProperties;
import org.ayuvahak.platform.authorization.application.BootstrapGrantService;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.adapter.api.PlatformEventDeliveryController;
import org.ayuvahak.platform.eventing.application.InternalEvent;
import org.ayuvahak.platform.eventing.application.InternalEventHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
    properties = {
      "management.tracing.sampling.probability=0.0",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-worker",
      "ayuvahak.authentication.allow-http=true",
      "ayuvahak.eventing.outbox.dispatcher-enabled=false"
    })
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@Import(EventDeliveryOperationsIT.HandlerFixture.class)
class EventDeliveryOperationsIT {

  private static final String PASSWORD = "local-test-only";
  private static final String ISSUER = "https://identity.example.test/realms/ayuvahak";
  private static final String LOCAL_ISSUER = "urn:ayuvahak:local-operator";
  private static final String ADMIN = "synthetic-admin-11";
  private static final String OPERATOR = "synthetic-event-operator-11";
  private static final RSAKey SIGNING_KEY = newSigningKey();
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(PASSWORD);
  private static HttpServer jwksServer;
  @Container private static final PostgreSQLContainer CONTAINER = POSTGRES;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private BootstrapGrantService bootstrap;

  @BeforeAll
  static void startJwks() throws Exception {
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
  static void stopJwks() {
    if (jwksServer != null) jwksServer.stop(0);
  }

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    registry.add("AYUVAHAK_DATABASE_URL", POSTGRES::getJdbcUrl);
    registry.add("AYUVAHAK_DATABASE_USERNAME", POSTGRES::getUsername);
    registry.add("AYUVAHAK_DATABASE_PASSWORD", POSTGRES::getPassword);
    registry.add("ayuvahak.authentication.issuer", () -> ISSUER);
    registry.add(
        "ayuvahak.authentication.jwk-set-uri",
        () -> "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks");
  }

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM platform_eventing.delivery_command_receipt");
    jdbc.update("DELETE FROM identity_access.grant_command_receipt");
    jdbc.update("DELETE FROM identity_access.bootstrap_marker");
    jdbc.update("DELETE FROM audit.audit_record");
    jdbc.update("DELETE FROM platform_eventing.outbox_delivery");
    jdbc.update("DELETE FROM platform_eventing.internal_event");
    jdbc.update("DELETE FROM identity_access.authorization_grant");
    jdbc.execute("DROP TRIGGER IF EXISTS test_fail_replay_audit ON audit.audit_record");
    jdbc.execute("DROP FUNCTION IF EXISTS test_fail_replay_audit()");
  }

  @Test
  void listsMinimizedDeliveryMetadataOnlyForEventOperationsGrantAndAuditsBeforeDisclosure()
      throws Exception {
    provisionOperator();
    String eventId = createGrantEvent();
    jdbc.update(
        "UPDATE platform_eventing.outbox_delivery SET state = 'UNHANDLED' WHERE event_id = ? AND delivery_generation = 1",
        java.util.UUID.fromString(eventId));

    mvc.perform(
            get("/api/v1/platform/event-deliveries")
                .header("Authorization", token(ADMIN))
                .header(PlatformEventDeliveryController.EXECUTION_SCOPE_HEADER, "PLATFORM"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));

    var result =
        mvc.perform(
                get("/api/v1/platform/event-deliveries")
                    .header("Authorization", token(OPERATOR))
                    .header(PlatformEventDeliveryController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                    .param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].eventId").exists())
            .andExpect(jsonPath("$.items[0].state").exists())
            .andExpect(jsonPath("$.items[0].payload").doesNotExist())
            .andReturn();
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("synthetic-event-operator-11", "envelope");
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'EVENT_DELIVERY_LIST' AND action_kind = 'SENSITIVE_READ' AND outcome = 'SUCCEEDED'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'EVENT_DELIVERY_LIST' AND outcome = 'DENIED'"))
        .isEqualTo(1);
  }

  @Test
  void replayIsAuthorizedIdempotentAuditedAndCreatesNewGenerationWithoutChangingEvent()
      throws Exception {
    provisionOperator();
    String eventId = createGrantEvent();
    java.util.UUID id = java.util.UUID.fromString(eventId);
    jdbc.update(
        "UPDATE platform_eventing.outbox_delivery SET state = 'STALLED', attempt_count = 10 WHERE event_id = ? AND delivery_generation = 1",
        id);
    String envelope =
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform_eventing.internal_event WHERE event_id = ?",
            String.class,
            id);

    var first =
        mvc.perform(
                post("/api/v1/platform/event-deliveries/{id}/replay", eventId)
                    .header("Authorization", token(OPERATOR))
                    .header(PlatformEventDeliveryController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                    .header("Idempotency-Key", "event-replay-11"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.deliveryGeneration").value(2))
            .andReturn();
    mvc.perform(
            post("/api/v1/platform/event-deliveries/{id}/replay", eventId)
                .header("Authorization", token(OPERATOR))
                .header(PlatformEventDeliveryController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                .header("Idempotency-Key", "event-replay-11"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.deliveryGeneration").value(2));

    assertThat(first.getResponse().getContentAsString()).contains(eventId);
    assertThat(
            jdbc.queryForObject(
                "SELECT current_delivery_generation FROM platform_eventing.internal_event WHERE event_id = ?",
                Integer.class,
                id))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT envelope::text FROM platform_eventing.internal_event WHERE event_id = ?",
                String.class,
                id))
        .isEqualTo(envelope);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM platform_eventing.outbox_delivery WHERE event_id = ? AND delivery_generation = 1",
                String.class,
                id))
        .isEqualTo("STALLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM platform_eventing.outbox_delivery WHERE event_id = ? AND delivery_generation = 2",
                String.class,
                id))
        .isEqualTo("PENDING");
    assertThat(
            count(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'EVENT_DELIVERY_REPLAY' AND outcome = 'SUCCEEDED'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM platform_eventing.delivery_command_receipt WHERE receipt_state = 'COMPLETED'"))
        .isEqualTo(1);
  }

  @Test
  void rejectsIneligibleAndHandlerlessEventsWithoutClaimingReplay() throws Exception {
    provisionOperator();
    String eligibleTypeEvent = createGrantEvent();
    java.util.UUID eligibleId = java.util.UUID.fromString(eligibleTypeEvent);
    var pending =
        mvc.perform(
                post("/api/v1/platform/event-deliveries/{id}/replay", eligibleTypeEvent)
                    .header("Authorization", token(OPERATOR))
                    .header(PlatformEventDeliveryController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                    .header("Idempotency-Key", "pending-replay-11"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EVENT_NOT_REPLAYABLE"))
            .andReturn();
    assertThat(pending.getResponse().getContentAsString()).contains("EVENT_NOT_REPLAYABLE");
    assertThat(
            jdbc.queryForObject(
                "SELECT current_delivery_generation FROM platform_eventing.internal_event WHERE event_id = ?",
                Integer.class,
                eligibleId))
        .isEqualTo(1);

    String unavailableId = insertHandlerlessEvent();
    jdbc.update(
        "UPDATE platform_eventing.outbox_delivery SET state = 'UNHANDLED' WHERE event_id = ? AND delivery_generation = 1",
        java.util.UUID.fromString(unavailableId));
    mvc.perform(
            post("/api/v1/platform/event-deliveries/{id}/replay", unavailableId)
                .header("Authorization", token(OPERATOR))
                .header(PlatformEventDeliveryController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                .header("Idempotency-Key", "no-handler-replay-11"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EVENT_HANDLER_UNAVAILABLE"));
    assertThat(count("SELECT COUNT(*) FROM platform_eventing.delivery_command_receipt")).isZero();
  }

  @Test
  void doesNotReportReplaySuccessWhenRequiredAuditWriteFails() throws Exception {
    provisionOperator();
    String eventId = createGrantEvent();
    java.util.UUID id = java.util.UUID.fromString(eventId);
    jdbc.update(
        "UPDATE platform_eventing.outbox_delivery SET state = 'STALLED' WHERE event_id = ? AND delivery_generation = 1",
        id);
    jdbc.execute(
        "CREATE FUNCTION test_fail_replay_audit() RETURNS trigger LANGUAGE plpgsql AS "
            + "'BEGIN IF NEW.action_code = ''EVENT_DELIVERY_REPLAY'' THEN RAISE EXCEPTION ''synthetic audit failure''; END IF; RETURN NEW; END'");
    jdbc.execute(
        "CREATE TRIGGER test_fail_replay_audit BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION test_fail_replay_audit()");

    mvc.perform(
            post("/api/v1/platform/event-deliveries/{id}/replay", eventId)
                .header("Authorization", token(OPERATOR))
                .header(PlatformEventDeliveryController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                .header("Idempotency-Key", "audit-failure-replay-11"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));

    assertThat(
            jdbc.queryForObject(
                "SELECT current_delivery_generation FROM platform_eventing.internal_event WHERE event_id = ?",
                Integer.class,
                id))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM platform_eventing.delivery_command_receipt")).isZero();
  }

  private void provisionOperator() throws Exception {
    bootstrap.provisionFirstGrant(
        new BootstrapGrantProperties(
            LOCAL_ISSUER,
            "operator-fixture-11",
            ISSUER,
            ActorKind.HUMAN.name(),
            ADMIN,
            "PLATFORM",
            "CREATE_FIRST_ACCESS_GRANT"),
        "test-host");
    mvc.perform(
            post("/api/v1/platform/access-grants")
                .header("Authorization", token(ADMIN))
                .header(PlatformAccessGrantController.EXECUTION_SCOPE_HEADER, "PLATFORM")
                .header("Idempotency-Key", "event-ops-grant-11")
                .contentType("application/json")
                .content(
                    "{\"principal\":{\"kind\":\"HUMAN\",\"issuer\":\""
                        + ISSUER
                        + "\",\"subject\":\""
                        + OPERATOR
                        + "\"},\"authority\":\"EventDeliveryOperations\","
                        + "\"scope\":{\"kind\":\"PLATFORM\"},\"reasonCode\":\"ACCESS_SETUP\"}"))
        .andExpect(status().isCreated());
  }

  private String createGrantEvent() {
    return jdbc.queryForObject(
        "SELECT event_id::text FROM platform_eventing.internal_event ORDER BY recorded_at DESC, event_id DESC LIMIT 1",
        String.class);
  }

  private String insertHandlerlessEvent() {
    String id =
        jdbc.queryForObject(
            "INSERT INTO platform_eventing.internal_event (event_id,event_type,producer_context,aggregate_ref,aggregate_sequence,envelope,recorded_at) "
                + "SELECT gen_random_uuid(),'identity-access.grant-revoked.v1','identity-access',NULL,NULL,"
                + "jsonb_set(envelope,'{eventType}','\"identity-access.grant-revoked.v1\"'::jsonb),now() "
                + "FROM platform_eventing.internal_event ORDER BY recorded_at DESC LIMIT 1 RETURNING event_id::text",
            String.class);
    jdbc.update(
        "INSERT INTO platform_eventing.outbox_delivery (event_id,delivery_generation,state,next_attempt_at,created_at,updated_at) "
            + "VALUES (?,1,'UNHANDLED',now(),now(),now())",
        java.util.UUID.fromString(id));
    return id;
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  private static String token(String subject) throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject(subject)
            .audience("ayuvahak-api")
            .issueTime(Date.from(now.minusSeconds(5)))
            .notBeforeTime(Date.from(now.minusSeconds(1)))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .claim("typ", "Bearer")
            .build();
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(SIGNING_KEY.getKeyID())
                .build(),
            claims);
    jwt.sign(new RSASSASigner(SIGNING_KEY));
    return "Bearer " + jwt.serialize();
  }

  private static RSAKey newSigningKey() {
    try {
      return new RSAKeyGenerator(2048).keyID("event-operations-fixture").generate();
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  @TestConfiguration
  static class HandlerFixture {
    @Bean
    InternalEventHandler grantCreatedFixtureHandler() {
      return new InternalEventHandler() {
        @Override
        public Set<EventType> supportedEventTypes() {
          return Set.of(EventType.parse("identity-access.grant-created.v1"));
        }

        @Override
        public void handle(InternalEvent event) {}
      };
    }
  }
}
