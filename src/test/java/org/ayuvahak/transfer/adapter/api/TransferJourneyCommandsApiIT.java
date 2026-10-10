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
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
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
      "ayuvahak.transfer.request-deadline=PT1H"
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
    matching.returnApplicable();

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
    matching.returnApplicable();

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
  void missingDeadlinePolicyAndUnauthorizedSenderFailBeforePersistence() throws Exception {
    Scope scope = createOriginAndDestination();
    grantSender(scope);
    matching.returnApplicable();
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
    private volatile long lastReferralRevision;

    @Override
    public RecommendationApplicabilityResult revalidate(
        org.ayuvahak.platform.executioncontext.ExecutionContext caller,
        InternalId recommendationId,
        ExternalReference candidateFacility,
        long exactReferralRevision) {
      calls.incrementAndGet();
      lastCaller = caller;
      lastRecommendationId = recommendationId;
      lastCandidateFacility = candidateFacility;
      lastReferralRevision = exactReferralRevision;
      return new RecommendationApplicabilityResult(
          recommendationId, candidateFacility, status, java.util.List.of(), Instant.now());
    }

    void returnApplicable() {
      status = RecommendationApplicabilityResult.Status.APPLICABLE;
    }

    void returnStatus(RecommendationApplicabilityResult.Status status) {
      this.status = status;
    }

    void reset() {
      status = RecommendationApplicabilityResult.Status.APPLICABLE;
      calls.set(0);
      lastCaller = null;
      lastRecommendationId = null;
      lastCandidateFacility = null;
      lastReferralRevision = 0;
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
  }
}
