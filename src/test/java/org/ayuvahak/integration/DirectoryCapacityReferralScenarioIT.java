package org.ayuvahak.integration;

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
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.ayuvahak.capacity.application.CapacityEvidenceStore;
import org.ayuvahak.capacity.application.CapacityReservationCommands;
import org.ayuvahak.capacity.domain.CapacityObservationFact;
import org.ayuvahak.capacity.domain.CapacityReadinessOutcome;
import org.ayuvahak.capacity.domain.CapacityReleaseResult;
import org.ayuvahak.capacity.domain.CapacityReservationOutcome;
import org.ayuvahak.capacity.domain.CapacityReservationRequest;
import org.ayuvahak.capacity.domain.CapacityReservationResult;
import org.ayuvahak.capacity.domain.FederatedReceivingCapacityReservationOutcome;
import org.ayuvahak.capacity.domain.FederatedReceivingCapacityReservationRequest;
import org.ayuvahak.directory.application.DirectoryMcsdSynchronizationService;
import org.ayuvahak.directory.application.DirectoryStore;
import org.ayuvahak.directory.domain.DirectoryExternalIdentifier;
import org.ayuvahak.matching.application.MatchingEligibilityApplicationService;
import org.ayuvahak.matching.application.MatchingOwnerEvidenceReader;
import org.ayuvahak.matching.application.MatchingPolicyCatalog;
import org.ayuvahak.matching.application.TravelTimePort;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator;
import org.ayuvahak.matching.domain.CapabilityOperationalMapping;
import org.ayuvahak.matching.domain.MatchingRecommendationPolicy;
import org.ayuvahak.matching.domain.RecommendationRanker.MetricKind;
import org.ayuvahak.matching.domain.RecommendationRanker.Policy;
import org.ayuvahak.matching.domain.RecommendationSnapshot.PolicyVersion;
import org.ayuvahak.platform.AyuVahakApplication;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.audit.application.AuditRecordAppender;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.eventing.application.OutboxDispatcher;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.referral.application.PatientContextCategory;
import org.ayuvahak.referral.application.PatientContextDisclosureDecision;
import org.ayuvahak.referral.application.PatientContextDisclosurePolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
      "ayuvahak.authentication.service-client-ids=synthetic-integration-service,synthetic-federated-origin-peer,synthetic-federated-capacity-peer,synthetic-federated-directory-peer,ayuvahak-transport-worker,ayuvahak-transfer-owner-event-consumer,synthetic-transport-provider,synthetic-mcsd-worker",
      "ayuvahak.authentication.allow-http=true",
      "ayuvahak.test.partner.enabled=true",
      "ayuvahak.transfer.request-deadline=PT1H",
      "ayuvahak.transfer.reservation-dimension=SYN_TRANSFER_SLOT",
      "ayuvahak.transfer.reservation-unit=slot",
      "ayuvahak.transfer.reservation-quantity=1",
      "ayuvahak.transfer.reservation-duration=PT2H",
      "ayuvahak.transfer.expiry-polling-enabled=false",
      "ayuvahak.transfer.reservation-polling-enabled=false",
      "ayuvahak.capacity.reservation-lifecycle.enabled=false",
      "ayuvahak.transport.provider-partner-key=SYNTHETIC-TRANSPORT-PROVIDER"
    })
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({
  DirectoryCapacityReferralScenarioIT.MatchingTestConfiguration.class,
  DirectoryCapacityReferralScenarioIT.PatientContextTestConfiguration.class,
  org.ayuvahak.platform.exchange.adapter.http.LocalPartnerExchangeTestConfiguration.class
})
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
  private static final String MCSD_NAMESPACE = "urn:synthetic:mcsd:volume-three";
  private static final String MCSD_PROFILE =
      "https://profiles.ihe.net/ITI/mCSD/StructureDefinition/IHE.mCSD.Organization|4.0.0";
  private static final String IPS_BUNDLE_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Bundle-uv-ips|2.0.1";
  private static final String IPS_PATIENT_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Patient-uv-ips|2.0.1";
  private static final String IPS_COMPOSITION_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips|2.0.1";
  private static final String IPS_CONDITION_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Condition-uv-ips|2.0.1";
  private static final java.util.concurrent.atomic.AtomicReference<byte[]> PARTNER_RESPONSE =
      new java.util.concurrent.atomic.AtomicReference<>(new byte[0]);
  private static final java.util.concurrent.atomic.AtomicReference<String> PROVIDER_REQUEST_TARGET =
      new java.util.concurrent.atomic.AtomicReference<>();
  private static final java.util.concurrent.atomic.AtomicInteger PROVIDER_REQUESTS =
      new java.util.concurrent.atomic.AtomicInteger();
  private static final PostgreSQLContainer POSTGRES =
      org.ayuvahak.testcontainers.PostgresTestContainerFactory.create(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(TEST_PASSWORD);

  private static final PostgreSQLContainer ORIGIN_POSTGRES =
      org.ayuvahak.testcontainers.PostgresTestContainerFactory.create(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak_origin")
          .withUsername("ayuvahak")
          .withPassword(TEST_PASSWORD);

  @Container private static final PostgreSQLContainer CONTAINER = POSTGRES;
  @Container private static final PostgreSQLContainer ORIGIN_CONTAINER = ORIGIN_POSTGRES;
  private static HttpServer jwksServer;
  private static HttpsServer partnerServer;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DirectoryStore directoryStore;
  @Autowired private DirectoryMcsdSynchronizationService directoryMcsdSynchronization;
  @Autowired private CapacityReservationCommands capacityReservationCommands;

  @Autowired
  private org.ayuvahak.platform.exchange.application.PartnerTrustProfileStore
      partnerTrustProfileStore;

  @Autowired
  private org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorizationService
      federationPeerInboundAuthorizationService;

  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired
  private org.ayuvahak.transfer.application.FederatedReceivingCoordinationCommands
      federatedReceivingCommands;

  @Autowired private ExecutionContextFactory executionContextFactory;
  @Autowired private MatchingOwnerEvidenceReader matchingOwnerEvidenceReader;
  @Autowired private MatchingEligibilityApplicationService matchingEligibility;
  @Autowired private MutableMatchingPolicyCatalog testPolicies;
  @Autowired private CapacityIsolationProbe capacityIsolationProbe;
  @Autowired private FederatedReceivingCapacityFailureProbe federatedReceivingCapacityFailureProbe;
  @Autowired private DirectoryIsolationProbe directoryIsolationProbe;
  @Autowired private MutableTravelTimePort testTravelTimePort;
  @Autowired private OutboxDispatcher outboxDispatcher;

  @DynamicPropertySource
  static void localInfrastructure(DynamicPropertyRegistry registry) {
    registry.add("AYUVAHAK_DATABASE_URL", POSTGRES::getJdbcUrl);
    registry.add("AYUVAHAK_DATABASE_USERNAME", POSTGRES::getUsername);
    registry.add("AYUVAHAK_DATABASE_PASSWORD", POSTGRES::getPassword);
    registry.add("ayuvahak.authentication.issuer", () -> ISSUER);
    registry.add("ayuvahak.federation.identity.deployment-id", () -> "SYN-DEPLOYMENT-RECEIVER");
    registry.add(
        "ayuvahak.federation.identity.installation-binding",
        () -> "SYNTHETIC-LOCAL-TEST-BINDING-ONLY-DO-NOT-USE-PRODUCTION-001");
    registry.add(
        "ayuvahak.authentication.jwk-set-uri",
        () -> "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks");
    registry.add(
        "ayuvahak.test.partner.port", () -> Integer.toString(partnerServer.getAddress().getPort()));
    registry.add(
        "ayuvahak.directory.mcsd.source-bindings[0].source-namespace", () -> MCSD_NAMESPACE);
    registry.add(
        "ayuvahak.directory.mcsd.source-bindings[0].partner-key", () -> "synthetic-directory-feed");
    registry.add(
        "ayuvahak.referral.ips.source-bindings[0].patient-identifier-system",
        () -> "urn:synthetic:source-ehr");
    registry.add(
        "ayuvahak.referral.ips.source-bindings[0].partner-key", () -> "synthetic-directory-feed");
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

    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    char[] password = "local-test-only".toCharArray();
    try (InputStream input =
        DirectoryCapacityReferralScenarioIT.class.getResourceAsStream(
            "/exchange/synthetic-partner.p12")) {
      if (input == null) {
        throw new IllegalStateException("Synthetic local TLS fixture is missing.");
      }
      keyStore.load(input, password);
    }
    KeyManagerFactory keyManagers =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(keyStore, password);
    SSLContext serverContext = SSLContext.getInstance("TLS");
    serverContext.init(keyManagers.getKeyManagers(), null, null);
    partnerServer =
        HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    partnerServer.setHttpsConfigurator(new HttpsConfigurator(serverContext));
    partnerServer.createContext(
        "/",
        exchange -> {
          if ("POST".equals(exchange.getRequestMethod())) {
            PROVIDER_REQUESTS.incrementAndGet();
            PROVIDER_REQUEST_TARGET.set(
                exchange.getRequestMethod() + " " + exchange.getRequestURI());
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
          }
          byte[] response = PARTNER_RESPONSE.get();
          exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
          exchange.sendResponseHeaders(200, response.length);
          try (var output = exchange.getResponseBody()) {
            output.write(response);
          }
        });
    partnerServer.start();
  }

  @AfterAll
  static void stopLocalJwksServer() {
    if (partnerServer != null) {
      partnerServer.stop(0);
    }
    if (jwksServer != null) {
      jwksServer.stop(0);
    }
  }

  @BeforeEach
  void resetMutableMatchingFixtures() {
    testPolicies.set(null);
    testTravelTimePort.setEstimates(List.of());
    capacityIsolationProbe.clear();
    directoryIsolationProbe.clear();
    federatedReceivingCapacityFailureProbe.reset();
  }

  @Test
  void directoryFactsCapacityEvidenceAndReferralRequirementComposeWithoutCrossOwnership()
      throws Exception {
    assertThat(jdbc.getQueryTimeout())
        .as("all JDBC statements have a finite ceiling, including owner authorization reads")
        .isEqualTo(30);
    grantPlatform(CURATOR, "DirectoryCuration");
    grantPlatform(CURATOR, "DirectoryVocabularyAdministration");
    grantPlatform(CAPACITY_ADMIN, "CapacityPolicyAdministration");

    String organizationId = createOrganization("synthetic-cross-phase-organization");
    String originFacilityId =
        createFacility("synthetic-cross-phase-origin", organizationId, "Synthetic Origin");
    String destinationFacilityId =
        createFacility(
            "synthetic-cross-phase-destination", organizationId, "Synthetic Destination");
    createLocation(originFacilityId);
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

    String matchingOwner = "ayuvahak-matching-owner-read";
    grantFacility(REFERRAL_ORIGINATOR, "RecommendationRequest", organizationId, originFacilityId);
    grantService(matchingOwner, "ReferralRead", "FACILITY", organizationId, originFacilityId);
    grantService(matchingOwner, "DirectoryRead", "FACILITY", organizationId, originFacilityId);
    grantService(
        matchingOwner, "CapacityEvidenceReview", "FACILITY", organizationId, originFacilityId);
    grantService(
        matchingOwner, "CapacityEvidenceReview", "FACILITY", organizationId, destinationFacilityId);

    mockMvc
        .perform(
            post("/api/v1/recommendations")
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .header("Idempotency-Key", "synthetic-policy-unconfigured")
                .contentType("application/json")
                .content("{\"referralId\":\"" + referralId + "\",\"revisionNumber\":1}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("MATCHING_POLICY_UNCONFIGURED"));

    ExecutionContext requesterContext =
        executionContextFactory.forInteractiveRequest(
            ActorReference.human(new IssuerId(ISSUER), REFERRAL_ORIGINATOR),
            RequestedExecutionScope.facility(
                new ExternalReference(
                    new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId),
                new ExternalReference(
                    new IssuerId(DIRECTORY_ISSUER), "Facility", originFacilityId)),
            new CorrelationId("matching-owner-reader-correlation"));
    var requirement =
        matchingOwnerEvidenceReader.readSubmittedRequirement(
            requesterContext, InternalId.parse(referralId), 1);
    var originLocation =
        matchingOwnerEvidenceReader.readOriginLocation(
            requesterContext, requirement, Instant.now());
    assertThat(originLocation).isPresent();
    assertThat(originLocation.orElseThrow().facility().value()).isEqualTo(originFacilityId);
    assertThat(originLocation.orElseThrow().organization().value()).isEqualTo(organizationId);
    assertThat(originLocation.orElseThrow().primaryLocation()).isPresent();
    assertThat(originLocation.orElseThrow().primaryLocation().orElseThrow().coordinate())
        .contains(
            new org.ayuvahak.directory.application.DirectoryMatchingOriginLocation.Coordinate(
                72.8, 19.1));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM identity_access.authorization_grant WHERE "
                    + "principal_kind = 'HUMAN' AND principal_identifier = ? AND "
                    + "authority_code IN ('DirectoryRead', 'CapacityEvidenceReview')",
                Integer.class,
                REFERRAL_ORIGINATOR))
        .isZero();
    Instant evaluationTime = Instant.now();
    assertThatThrownBy(
            () ->
                matchingOwnerEvidenceReader.findCandidatePages(
                    requesterContext,
                    requirement,
                    new ExternalReference(
                        new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId),
                    100,
                    evaluationTime))
        .isInstanceOf(PlatformProblemException.class)
        .satisfies(
            failure ->
                assertThat(((PlatformProblemException) failure).code().name())
                    .isEqualTo("AUTHORIZATION_DENIED"));
    grantService(matchingOwner, "DirectoryRead", "ORGANIZATION", organizationId, null);
    var candidatePages =
        matchingOwnerEvidenceReader.findCandidatePages(
            requesterContext,
            requirement,
            new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId),
            100,
            evaluationTime);
    var directoryCandidates =
        candidatePages.stream().flatMap(page -> page.candidates().stream()).toList();
    var mappedRequirements =
        new CapabilityOperationalMapping(
                "SYNTHETIC_CROSS_PHASE",
                1,
                java.util.Map.of(CAPABILITY, java.util.Set.of(DIMENSION)))
            .resolve(requirement.requiredCareCapabilityCodes());
    var readiness =
        matchingOwnerEvidenceReader.evaluateCapacityReadiness(
            requesterContext,
            directoryCandidates,
            mappedRequirements.uniqueDimensionCodes(),
            evaluationTime);
    var eligibility =
        matchingEligibility.evaluate(
            requirement,
            mappedRequirements,
            candidatePages,
            readiness,
            java.util.Set.of(
                new ExternalReference(
                    new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId)),
            evaluationTime,
            false);

    assertThat(requirement.toString()).doesNotContain("synthetic-patient-01");
    assertThat(directoryCandidates).hasSize(2);
    assertThat(candidatePages).hasSize(1);
    assertThat(candidatePages.getFirst().hasMore()).isFalse();
    var capacityBatch = readiness.orElseThrow();
    assertThat(capacityBatch.evaluationTime()).isEqualTo(evaluationTime);
    assertThat(capacityBatch.facilities())
        .hasSize(2)
        .allSatisfy(
            result -> {
              assertThat(result.evaluationTime()).isEqualTo(evaluationTime);
              assertThat(result.dimensions())
                  .singleElement()
                  .satisfies(
                      dimension -> {
                        assertThat(dimension.dimensionCode()).isEqualTo(DIMENSION);
                        assertThat(dimension.freshness()).isNotNull();
                        assertThat(dimension.policyVersion()).isPresent();
                        assertThat(dimension.projectionVersion()).isNotBlank();
                        assertThat(dimension.reasonCodes()).isNotEmpty();
                      });
            });
    var originCapacity =
        capacityBatch.facilities().stream()
            .filter(result -> result.facility().value().equals(originFacilityId))
            .findFirst()
            .orElseThrow();
    assertThat(originCapacity.dimensions())
        .singleElement()
        .satisfies(
            dimension -> {
              assertThat(dimension.freshness().name()).isEqualTo("CURRENT");
              assertThat(dimension.evidence())
                  .singleElement()
                  .satisfies(
                      observation -> {
                        assertThat(observation.sourceCode()).isEqualTo("manual");
                        assertThat(observation.sourceObservation().value())
                            .isEqualTo("cross-phase-observation-01");
                        assertThat(observation.observedAt()).isBeforeOrEqualTo(evaluationTime);
                      });
            });
    var destinationCapacity =
        capacityBatch.facilities().stream()
            .filter(result -> result.facility().value().equals(destinationFacilityId))
            .findFirst()
            .orElseThrow();
    assertThat(destinationCapacity.dimensions())
        .singleElement()
        .satisfies(
            dimension -> {
              assertThat(dimension.freshness().name()).isEqualTo("UNKNOWN");
              assertThat(dimension.evidence()).isEmpty();
              assertThat(dimension.reasonCodes()).contains("NO_USABLE_EVIDENCE");
            });
    assertThat(capacityBatch.facilities())
        .extracting(result -> result.facility().value(), result -> result.outcome())
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(originFacilityId, CapacityReadinessOutcome.READY),
            org.assertj.core.groups.Tuple.tuple(
                destinationFacilityId, CapacityReadinessOutcome.UNKNOWN));
    assertThat(eligibility.completeness())
        .isEqualTo(CandidateEligibilityEvaluator.Completeness.INCOMPLETE);
    assertThat(eligibility.reasonCodes()).contains("GEOGRAPHIC_CONSTRAINT_UNRESOLVED");
    assertThat(eligibility.candidates())
        .extracting(
            result -> result.facility().value(),
            CandidateEligibilityEvaluator.CandidateResult::outcome)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(
                originFacilityId, CandidateEligibilityEvaluator.CandidateOutcome.INELIGIBLE),
            org.assertj.core.groups.Tuple.tuple(
                destinationFacilityId, CandidateEligibilityEvaluator.CandidateOutcome.INELIGIBLE));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE actor_reference = ? "
                    + "AND correlation_id = ? AND reason_code = 'MATCHING_EVALUATION' AND "
                    + "action_code IN ('REFERRAL_MATCHING_REQUIREMENT_READ', "
                    + "'DIRECTORY_MATCHING_CANDIDATE_SEARCH', "
                    + "'DIRECTORY_MATCHING_EVALUATION_READ', "
                    + "'DIRECTORY_MATCHING_ORIGIN_LOCATION_READ', "
                    + "'CAPACITY_MATCHING_EVALUATION_READ')",
                Integer.class,
                matchingOwner,
                "matching-owner-reader-correlation"))
        .isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "SELECT actor_kind || ':' || actor_reference || ':' || scope_kind || ':' "
                    + "|| organization_reference || ':' || facility_reference "
                    + "FROM audit.audit_record WHERE action_code = "
                    + "'DIRECTORY_MATCHING_ORIGIN_LOCATION_READ' AND correlation_id = ?",
                String.class,
                "matching-owner-reader-correlation"))
        .isEqualTo(
            "SERVICE:" + matchingOwner + ":FACILITY:" + organizationId + ":" + originFacilityId);

    testPolicies.set(
        activePolicy(
            organizationId, 1, Duration.ofHours(1), MetricKind.STRAIGHT_LINE_DISTANCE, true, 1));
    capacityIsolationProbe.clear();
    directoryIsolationProbe.clear();
    MvcResult createdRecommendation =
        mockMvc
            .perform(
                post("/api/v1/recommendations")
                    .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId)
                    .header("Idempotency-Key", "synthetic-matching-generate")
                    .contentType("application/json")
                    .content("{\"referralId\":\"" + referralId + "\",\"revisionNumber\":1}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.outcome").value("INDETERMINATE"))
            .andExpect(jsonPath("$.candidates.length()").value(2))
            .andReturn();
    assertThat(capacityIsolationProbe.observedIsolationLevels())
        .as("Capacity's batch read transaction runs independently at repeatable read")
        .isNotEmpty()
        .containsOnly("repeatable read");
    assertThat(directoryIsolationProbe.observedIsolationLevels())
        .as("all Directory pages share one owner-managed repeatable-read snapshot")
        .hasSize(2)
        .containsOnly("repeatable read");
    assertThat(directoryIsolationProbe.observedTransactionIds())
        .as("all Directory candidate pages are read in the same database transaction")
        .hasSize(2)
        .containsOnly(directoryIsolationProbe.observedTransactionIds().getFirst());
    assertThat(directoryIsolationProbe.observedActiveConnections())
        .as("Matching does not retain a database connection while Directory reads its snapshot")
        .containsOnly(1);
    assertThat(directoryIsolationProbe.auditActiveConnections())
        .as("Directory's durable matching-read audits are appended after its snapshot transaction")
        .isNotEmpty()
        .containsOnly(0);
    String recommendationId =
        com.jayway.jsonpath.JsonPath.read(
            createdRecommendation.getResponse().getContentAsString(), "$.id");
    String recommendationCorrelation =
        com.jayway.jsonpath.JsonPath.read(
            createdRecommendation.getResponse().getContentAsString(),
            "$.attribution.correlationId");
    assertThat(createdRecommendation.getResponse().getContentAsString())
        .doesNotContain("patientReference", "synthetic-patient-01")
        .contains("\"receivedAt\":");
    String persistedSnapshot =
        jdbc.queryForObject(
            "SELECT snapshot_document::text FROM matching.recommendation_snapshot "
                + "WHERE recommendation_id = ?",
            String.class,
            UUID.fromString(recommendationId));
    Instant authoritativeReceivedAt =
        jdbc.queryForObject(
            "SELECT received_at FROM capacity.capacity_observation "
                + "WHERE source_identifier = ?",
            (result, row) -> result.getTimestamp("received_at").toInstant(),
            "cross-phase-observation-01");
    List<String> apiReceivedAt =
        com.jayway.jsonpath.JsonPath.read(
            createdRecommendation.getResponse().getContentAsString(),
            "$.candidates[*].evidence[*].receivedAt");
    assertThat(apiReceivedAt).contains(authoritativeReceivedAt.toString());
    List<String> persistedReceivedAt =
        com.jayway.jsonpath.JsonPath.read(
            persistedSnapshot, "$.candidates[*].evidence[*].receivedAt");
    assertThat(persistedSnapshot)
        .doesNotContain(
            "patientReference", "PatientReference", "synthetic-patient-01", "encounterReference");
    assertThat(persistedReceivedAt).contains(authoritativeReceivedAt.toString());

    grantFacility(REFERRAL_ORIGINATOR, "RecommendationRead", organizationId, originFacilityId);
    mockMvc
        .perform(
            get("/api/v1/recommendations/{recommendationId}", recommendationId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(recommendationId))
        .andExpect(jsonPath("$.referralRevision").value(1))
        .andExpect(jsonPath("$.outcome").value("INDETERMINATE"));

    mockMvc
        .perform(
            get("/api/v1/referrals/{referralId}/recommendations", referralId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .param("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.referralId").value(referralId))
        .andExpect(jsonPath("$.items.length()", org.hamcrest.Matchers.is(1)))
        .andExpect(jsonPath("$.latestRecommendationId").value(recommendationId));

    mockMvc
        .perform(
            post("/api/v1/recommendations")
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .header("Idempotency-Key", "synthetic-matching-generate")
                .contentType("application/json")
                .content("{\"referralId\":\"" + referralId + "\",\"revisionNumber\":1}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.id").value(recommendationId));

    mockMvc
        .perform(
            post("/api/v1/recommendations")
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .header("Idempotency-Key", "synthetic-matching-generate")
                .contentType("application/json")
                .content("{\"referralId\":\"" + referralId + "\",\"revisionNumber\":2}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

    mockMvc
        .perform(
            post("/api/v1/recommendations")
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .contentType("application/json")
                .content("{\"referralId\":\"" + referralId + "\",\"revisionNumber\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MATCHING_REQUEST_INVALID"));

    mockMvc
        .perform(
            post("/api/v1/recommendations")
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .contentType("application/json")
                .content("{\"referralId\":\"" + referralId + "\",\"revisionNumber\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MATCHING_REQUEST_INVALID"));

    jdbc.execute(
        "ALTER TABLE capacity.capacity_observation "
            + "RENAME TO capacity_observation_temporarily_unavailable");
    try {
      mockMvc
          .perform(
              post("/api/v1/recommendations")
                  .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                  .header("X-AyuVahak-Execution-Scope", "FACILITY")
                  .header("X-AyuVahak-Organization-Id", organizationId)
                  .header("X-AyuVahak-Facility-Id", originFacilityId)
                  .header("Idempotency-Key", "synthetic-matching-capacity-outage")
                  .contentType("application/json")
                  .content("{\"referralId\":\"" + referralId + "\",\"revisionNumber\":1}"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("MATCHING_EVIDENCE_UNAVAILABLE"));
    } finally {
      jdbc.execute(
          "ALTER TABLE capacity.capacity_observation_temporarily_unavailable "
              + "RENAME TO capacity_observation");
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM matching.recommendation_snapshot "
                    + "WHERE recommendation_id = ?",
                Integer.class,
                UUID.fromString(recommendationId)))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE "
                    + "action_code = 'MATCHING_RECOMMENDATION_GENERATE' "
                    + "AND actor_reference = ? AND correlation_id = ?",
                Integer.class,
                REFERRAL_ORIGINATOR,
                recommendationCorrelation))
        .isEqualTo(1);

    jdbc.execute(
        "CREATE FUNCTION matching.reject_recommendation_audit() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN IF NEW.action_code = "
            + "'MATCHING_RECOMMENDATION_GENERATE' THEN RAISE EXCEPTION "
            + "'synthetic audit failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_recommendation_audit BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION matching.reject_recommendation_audit()");
    try {
      mockMvc
          .perform(
              post("/api/v1/recommendations")
                  .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                  .header("X-AyuVahak-Execution-Scope", "FACILITY")
                  .header("X-AyuVahak-Organization-Id", organizationId)
                  .header("X-AyuVahak-Facility-Id", originFacilityId)
                  .header("Idempotency-Key", "synthetic-matching-audit-failure")
                  .contentType("application/json")
                  .content("{\"referralId\":\"" + referralId + "\",\"revisionNumber\":1}"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));
    } finally {
      jdbc.execute("DROP TRIGGER reject_recommendation_audit ON audit.audit_record");
      jdbc.execute("DROP FUNCTION matching.reject_recommendation_audit()");
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM matching.recommendation_snapshot WHERE referral_id = ?",
                Integer.class,
                UUID.fromString(referralId)))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM matching.command_receipt WHERE result_reference IN "
                    + "(SELECT recommendation_id FROM matching.recommendation_snapshot "
                    + "WHERE referral_id = ?)",
                Integer.class,
                UUID.fromString(referralId)))
        .isEqualTo(1);
    mockMvc
        .perform(
            get("/api/v1/recommendations/{recommendationId}", recommendationId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(recommendationId))
        .andExpect(jsonPath("$.outcome").value("INDETERMINATE"));

    jdbc.execute(
        "CREATE FUNCTION matching.cancel_referral_after_owner_read() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN IF NEW.action_code = "
            + "'REFERRAL_MATCHING_REQUIREMENT_READ' THEN UPDATE referral.draft "
            + "SET state = 'CANCELLED', version = version + 1, updated_at = clock_timestamp() "
            + "WHERE referral_id = NEW.target_id AND state = 'SUBMITTED'; END IF; "
            + "RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER cancel_referral_after_owner_read AFTER INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION matching.cancel_referral_after_owner_read()");
    try {
      mockMvc
          .perform(
              post("/api/v1/recommendations")
                  .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                  .header("X-AyuVahak-Execution-Scope", "FACILITY")
                  .header("X-AyuVahak-Organization-Id", organizationId)
                  .header("X-AyuVahak-Facility-Id", originFacilityId)
                  .header("Idempotency-Key", "synthetic-matching-stale-referral")
                  .contentType("application/json")
                  .content("{\"referralId\":\"" + referralId + "\",\"revisionNumber\":1}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("MATCHING_REFERRAL_REVISION_STALE"));
    } finally {
      jdbc.execute("DROP TRIGGER cancel_referral_after_owner_read ON audit.audit_record");
      jdbc.execute("DROP FUNCTION matching.cancel_referral_after_owner_read()");
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM matching.recommendation_snapshot WHERE referral_id = ?",
                Integer.class,
                UUID.fromString(referralId)))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM matching.command_receipt WHERE result_reference IN "
                    + "(SELECT recommendation_id FROM matching.recommendation_snapshot "
                    + "WHERE referral_id = ?)",
                Integer.class,
                UUID.fromString(referralId)))
        .isEqualTo(1);

    jdbc.update(
        "DELETE FROM identity_access.authorization_grant WHERE principal_kind = 'SERVICE' "
            + "AND principal_issuer = ? AND principal_identifier = ? "
            + "AND authority_code = 'CapacityEvidenceReview' AND scope_kind = 'FACILITY' "
            + "AND facility_identifier = ?",
        ISSUER,
        matchingOwner,
        destinationFacilityId);
    ExecutionContext deniedCapacityContext =
        executionContextFactory.forInteractiveRequest(
            ActorReference.human(new IssuerId(ISSUER), REFERRAL_ORIGINATOR),
            RequestedExecutionScope.facility(
                new ExternalReference(
                    new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId),
                new ExternalReference(
                    new IssuerId(DIRECTORY_ISSUER), "Facility", originFacilityId)),
            new CorrelationId("matching-capacity-read-denied"));
    assertThatThrownBy(
            () ->
                matchingOwnerEvidenceReader.evaluateCapacityReadiness(
                    deniedCapacityContext,
                    directoryCandidates,
                    mappedRequirements.uniqueDimensionCodes(),
                    evaluationTime))
        .isInstanceOf(PlatformProblemException.class)
        .satisfies(
            failure ->
                assertThat(((PlatformProblemException) failure).code().name())
                    .isEqualTo("AUTHORIZATION_DENIED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'CAPACITY_MATCHING_EVALUATION_READ' AND reason_code = "
                    + "'MATCHING_EVALUATION' AND correlation_id = ?",
                Integer.class,
                "matching-capacity-read-denied"))
        .isZero();

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
    // Analytics owns reporting aggregates separately from the operational contexts.
    assertThat(
            jdbc.queryForList(
                "SELECT schema_name FROM information_schema.schemata "
                    + "WHERE schema_name IN ('matching', 'transfer', 'transport', 'analytics')",
                String.class))
        .containsExactlyInAnyOrder("matching", "transfer", "transport", "analytics");

    createLocation(destinationFacilityId, "synthetic-cross-phase-destination-location", 73.0, 19.1);
    String destinationAssertionId = createFacilityCapabilityAssertion(destinationFacilityId);
    grantFacility(CAPACITY_REPORTER, "CapacityReporting", organizationId, destinationFacilityId);
    grantService(
        matchingOwner, "CapacityEvidenceReview", "FACILITY", organizationId, destinationFacilityId);
    recordCapacityObservation(
        organizationId,
        destinationFacilityId,
        "synthetic-cross-phase-destination-observation",
        "cross-phase-destination-observation-01",
        4);

    String applicableReferralId =
        createReferral(
            organizationId, originFacilityId, "synthetic-cross-phase-applicable-referral", false);
    mockMvc
        .perform(
            post("/api/v1/referrals/{referralId}/submission", applicableReferralId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .header("Idempotency-Key", "synthetic-cross-phase-applicable-submit")
                .contentType("application/json")
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isOk());

    MvcResult applicableRecommendation =
        mockMvc
            .perform(
                post("/api/v1/recommendations")
                    .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId)
                    .header("Idempotency-Key", "synthetic-cross-phase-applicable-matching")
                    .contentType("application/json")
                    .content(
                        "{\"referralId\":\"" + applicableReferralId + "\",\"revisionNumber\":1}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.outcome").value("COMPLETE"))
            .andExpect(jsonPath("$.candidates[0].status").value("RANKED_ELIGIBLE"))
            .andReturn();
    String applicableRecommendationId =
        com.jayway.jsonpath.JsonPath.read(
            applicableRecommendation.getResponse().getContentAsString(), "$.id");

    String originLocationId =
        jdbc.queryForObject(
            "SELECT location_id FROM directory.location WHERE facility_id = ? AND is_primary",
            String.class,
            UUID.fromString(originFacilityId));
    String destinationLocationId =
        jdbc.queryForObject(
            "SELECT location_id FROM directory.location WHERE facility_id = ? AND is_primary",
            String.class,
            UUID.fromString(destinationFacilityId));
    ExternalReference originLocationReference =
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Location", originLocationId);
    ExternalReference destinationLocationReference =
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Location", destinationLocationId);
    Instant routeObservedAt = Instant.now().minusSeconds(1);
    testTravelTimePort.setEstimates(
        List.of(
            new TravelTimePort.Estimate(
                destinationLocationReference,
                new java.math.BigDecimal("900"),
                "SYNTHETIC_ROUTE_FIXTURE",
                java.util.Optional.of(
                    new ExternalReference(
                        new IssuerId("urn:synthetic:routing"), "System", "local-route-fixture")),
                routeObservedAt,
                routeObservedAt.plusSeconds(300))));
    testPolicies.set(
        activePolicy(organizationId, 2, Duration.ofHours(1), MetricKind.TRAVEL_TIME, false));
    String travelTimeReferralId =
        createReferral(
            organizationId, originFacilityId, "synthetic-cross-phase-travel-time-referral", false);
    mockMvc
        .perform(
            post("/api/v1/referrals/{referralId}/submission", travelTimeReferralId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .header("Idempotency-Key", "synthetic-cross-phase-travel-time-submit")
                .contentType("application/json")
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isOk());
    MvcResult travelTimeRecommendation =
        mockMvc
            .perform(
                post("/api/v1/recommendations")
                    .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId)
                    .header("Idempotency-Key", "synthetic-cross-phase-travel-time-matching")
                    .contentType("application/json")
                    .content(
                        "{\"referralId\":\"" + travelTimeReferralId + "\",\"revisionNumber\":1}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.outcome").value("COMPLETE"))
            .andExpect(jsonPath("$.candidates[0].status").value("RANKED_ELIGIBLE"))
            .andExpect(jsonPath("$.candidates[0].measure.kind").value("TRAVEL_TIME"))
            .andExpect(
                jsonPath("$.candidates[0].measure.sourceCode").value("SYNTHETIC_ROUTE_FIXTURE"))
            .andReturn();
    assertThat(testTravelTimePort.lastRequest().originLocation())
        .isEqualTo(originLocationReference);
    assertThat(testTravelTimePort.lastRequest().destinationLocations())
        .containsExactly(destinationLocationReference);
    assertThat(travelTimeRecommendation.getResponse().getContentAsString())
        .contains("\"receivedAt\":");

    testTravelTimePort.setUnavailable(true);
    testPolicies.set(
        activePolicy(organizationId, 3, Duration.ofHours(1), MetricKind.TRAVEL_TIME, true));
    MvcResult outageFallbackRecommendation =
        mockMvc
            .perform(
                post("/api/v1/recommendations")
                    .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId)
                    .header("Idempotency-Key", "synthetic-cross-phase-travel-time-outage")
                    .contentType("application/json")
                    .content(
                        "{\"referralId\":\"" + travelTimeReferralId + "\",\"revisionNumber\":1}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.outcome").value("COMPLETE"))
            .andExpect(jsonPath("$.candidates[0].status").value("RANKED_ELIGIBLE"))
            .andExpect(jsonPath("$.candidates[0].measure.kind").value("STRAIGHT_LINE_DISTANCE"))
            .andReturn();
    assertThat(outageFallbackRecommendation.getResponse().getContentAsString())
        .contains("DISTANCE_FALLBACK_USED");
    testTravelTimePort.setUnavailable(false);
    testPolicies.set(activePolicy(organizationId));

    mockMvc
        .perform(
            get("/api/v1/referrals/{referralId}/recommendations", applicableReferralId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .header("X-Correlation-ID", "matching-applicability-current")
                .param("candidateFacilityId", destinationFacilityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.latestRecommendationId").value(applicableRecommendationId))
        .andExpect(jsonPath("$.currentApplicability.status").value("APPLICABLE"));
    assertMatchingApplicabilityOwnerAudit(
        "matching-applicability-current",
        matchingOwner,
        organizationId,
        originFacilityId,
        destinationFacilityId,
        applicableReferralId,
        applicableRecommendationId);

    testPolicies.set(activePolicy(organizationId, 1, Duration.ofNanos(1)));
    mockMvc
        .perform(
            get("/api/v1/referrals/{referralId}/recommendations", applicableReferralId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .header("X-Correlation-ID", "matching-applicability-expired")
                .param("candidateFacilityId", destinationFacilityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.latestRecommendationId").value(applicableRecommendationId))
        .andExpect(jsonPath("$.currentApplicability.status").value("NOT_APPLICABLE"))
        .andExpect(
            jsonPath("$.currentApplicability.reasonCodes[0]").value("RECOMMENDATION_EXPIRED"));
    mockMvc
        .perform(
            get("/api/v1/recommendations/{recommendationId}", applicableRecommendationId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(applicableRecommendationId))
        .andExpect(jsonPath("$.matchingPolicy.version").value(1))
        .andExpect(jsonPath("$.candidates[0].status").value("RANKED_ELIGIBLE"));
    testPolicies.set(activePolicy(organizationId));

    String closestIneligibleFacilityId =
        createFacility(
            "synthetic-cross-phase-closest-ineligible",
            organizationId,
            "Synthetic Closest Facility Without Capability");
    createLocation(
        closestIneligibleFacilityId, "synthetic-cross-phase-closest-location", 72.81, 19.1);
    grantFacility(
        CAPACITY_REPORTER, "CapacityReporting", organizationId, closestIneligibleFacilityId);
    grantService(
        matchingOwner,
        "CapacityEvidenceReview",
        "FACILITY",
        organizationId,
        closestIneligibleFacilityId);
    recordCapacityObservation(
        organizationId,
        closestIneligibleFacilityId,
        "synthetic-cross-phase-closest-ready",
        "cross-phase-closest-observation-01",
        4);
    Instant conflictObservedAt = Instant.now().minusSeconds(1);
    recordCapacityObservation(
        organizationId,
        closestIneligibleFacilityId,
        "synthetic-cross-phase-conflict-a",
        "cross-phase-conflict-observation-a",
        3,
        conflictObservedAt);
    recordCapacityObservation(
        organizationId,
        closestIneligibleFacilityId,
        "synthetic-cross-phase-conflict-b",
        "cross-phase-conflict-observation-b",
        5,
        conflictObservedAt);
    assertThat(
            jdbc.queryForObject(
                "SELECT ST_Distance(origin.coordinates, closer.coordinates) "
                    + "< ST_Distance(origin.coordinates, farther.coordinates) "
                    + "FROM directory.location origin "
                    + "JOIN directory.location closer ON closer.facility_id = ? "
                    + "JOIN directory.location farther ON farther.facility_id = ? "
                    + "WHERE origin.facility_id = ? AND origin.is_primary "
                    + "AND closer.is_primary AND farther.is_primary",
                Boolean.class,
                UUID.fromString(closestIneligibleFacilityId),
                UUID.fromString(destinationFacilityId),
                UUID.fromString(originFacilityId)))
        .isTrue();
    String goldenReferralId =
        createReferral(
            organizationId, originFacilityId, "synthetic-cross-phase-golden-referral", false);
    mockMvc
        .perform(
            post("/api/v1/referrals/{referralId}/submission", goldenReferralId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .header("Idempotency-Key", "synthetic-cross-phase-golden-submit")
                .contentType("application/json")
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isOk());
    MvcResult goldenRecommendation =
        mockMvc
            .perform(
                post("/api/v1/recommendations")
                    .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId)
                    .header("Idempotency-Key", "synthetic-cross-phase-golden-matching")
                    .contentType("application/json")
                    .content("{\"referralId\":\"" + goldenReferralId + "\",\"revisionNumber\":1}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.outcome").value("COMPLETE"))
            .andReturn();
    String goldenBody = goldenRecommendation.getResponse().getContentAsString();
    assertThat(
            com.jayway.jsonpath.JsonPath.<List<String>>read(
                goldenBody,
                "$.candidates[?(@.facility.identifier=='"
                    + closestIneligibleFacilityId
                    + "')].status"))
        .containsExactly("INELIGIBLE");
    assertThat(
            com.jayway.jsonpath.JsonPath.<List<String>>read(
                goldenBody,
                "$.candidates[?(@.facility.identifier=='"
                    + closestIneligibleFacilityId
                    + "')].reasons[*].code"))
        .contains("CAPABILITY_NOT_ASSERTED");
    assertThat(
            com.jayway.jsonpath.JsonPath.<List<String>>read(
                goldenBody,
                "$.candidates[?(@.facility.identifier=='"
                    + closestIneligibleFacilityId
                    + "')].reasons[?(@.code=='CAPABILITY_NOT_ASSERTED')].subjectCode"))
        .containsExactly(CAPABILITY);
    assertThat(
            com.jayway.jsonpath.JsonPath.<List<String>>read(
                goldenBody,
                "$.candidates[?(@.facility.identifier=='"
                    + closestIneligibleFacilityId
                    + "')].directoryCapabilityAssessments[?(@.capabilityCode=='"
                    + CAPABILITY
                    + "')].outcomeCode"))
        .containsExactly("NO_ASSERTION");
    assertThat(
            com.jayway.jsonpath.JsonPath.<List<Number>>read(
                goldenBody,
                "$.candidates[?(@.facility.identifier=='"
                    + closestIneligibleFacilityId
                    + "')].directoryCapabilityAssessments[?(@.capabilityCode=='"
                    + CAPABILITY
                    + "')].capabilityVersion"))
        .hasSize(1)
        .allMatch(version -> version != null && version.longValue() > 0);
    String goldenRecommendationId = com.jayway.jsonpath.JsonPath.read(goldenBody, "$.id");
    String persistedGoldenSnapshot =
        jdbc.queryForObject(
            "SELECT snapshot_document::text FROM matching.recommendation_snapshot "
                + "WHERE recommendation_id = ?",
            String.class,
            UUID.fromString(goldenRecommendationId));
    String persistedGoldenRecommendation =
        mockMvc
            .perform(
                get("/api/v1/recommendations/{recommendationId}", goldenRecommendationId)
                    .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String persistedCapacityEvidencePath =
        "$.candidates[?(@.facility.value=='"
            + closestIneligibleFacilityId
            + "')].evidence[?(@.factCode=='CAPACITY_READINESS')].capacityDetails";
    List<Boolean> persistedFallbackUsed =
        com.jayway.jsonpath.JsonPath.read(
            persistedGoldenSnapshot, persistedCapacityEvidencePath + ".fallbackUsed");
    assertThat(persistedFallbackUsed).containsExactly(true);
    List<List<String>> persistedCapacityReasons =
        com.jayway.jsonpath.JsonPath.read(
            persistedGoldenSnapshot, persistedCapacityEvidencePath + ".reasonCodes");
    assertThat(persistedCapacityReasons)
        .singleElement()
        .satisfies(reasons -> assertThat(reasons).contains("EQUAL_AUTHORITY_DISAGREEMENT"));
    assertThat(persistedGoldenSnapshot)
        .contains("cross-phase-conflict-observation-a", "cross-phase-conflict-observation-b");
    List<Boolean> retrievedFallbackUsed =
        com.jayway.jsonpath.JsonPath.read(
            persistedGoldenRecommendation,
            "$.candidates[?(@.facility.identifier=='"
                + closestIneligibleFacilityId
                + "')].evidence[?(@.factCode=='CAPACITY_READINESS')].capacityDetails.fallbackUsed");
    assertThat(retrievedFallbackUsed).containsExactly(true);
    assertThat(persistedGoldenRecommendation)
        .contains(
            "EQUAL_AUTHORITY_DISAGREEMENT",
            "cross-phase-conflict-observation-a",
            "cross-phase-conflict-observation-b");
    assertThat(
            com.jayway.jsonpath.JsonPath.<List<String>>read(
                persistedGoldenRecommendation,
                "$.candidates[?(@.facility.identifier=='"
                    + closestIneligibleFacilityId
                    + "')].reasons[?(@.code=='CAPABILITY_NOT_ASSERTED')].subjectCode"))
        .containsExactly(CAPABILITY);
    assertThat(
            com.jayway.jsonpath.JsonPath.<List<String>>read(
                persistedGoldenRecommendation,
                "$.candidates[?(@.facility.identifier=='"
                    + closestIneligibleFacilityId
                    + "')].directoryCapabilityAssessments[?(@.capabilityCode=='"
                    + CAPABILITY
                    + "')].outcomeCode"))
        .containsExactly("NO_ASSERTION");
    assertThat(
            com.jayway.jsonpath.JsonPath.<List<Integer>>read(
                goldenBody,
                "$.candidates[?(@.facility.identifier=='" + destinationFacilityId + "')].rank"))
        .containsExactly(1);
    String conflictedCapacityEvidencePath =
        "$.candidates[?(@.facility.identifier=='"
            + closestIneligibleFacilityId
            + "')].evidence[?(@.factCode=='CAPACITY_READINESS')].capacityDetails";
    List<Boolean> fallbackUsed =
        com.jayway.jsonpath.JsonPath.read(
            goldenBody, conflictedCapacityEvidencePath + ".fallbackUsed");
    assertThat(fallbackUsed).containsExactly(true);
    List<List<String>> capacityReasons =
        com.jayway.jsonpath.JsonPath.read(
            goldenBody, conflictedCapacityEvidencePath + ".reasonCodes");
    assertThat(capacityReasons)
        .singleElement()
        .satisfies(reasons -> assertThat(reasons).contains("EQUAL_AUTHORITY_DISAGREEMENT"));
    List<List<Map<String, Object>>> sourceEvidence =
        com.jayway.jsonpath.JsonPath.read(
            goldenBody, conflictedCapacityEvidencePath + ".sourceObservations");
    assertThat(sourceEvidence)
        .singleElement()
        .satisfies(
            observations -> {
              assertThat(observations).hasSize(2);
              assertThat(observations)
                  .extracting(observation -> observation.get("sourceCode"))
                  .containsOnly("manual");
              assertThat(observations)
                  .extracting(
                      observation ->
                          String.valueOf(
                              ((Map<?, ?>) observation.get("sourceObservation")).get("identifier")))
                  .containsExactlyInAnyOrder(
                      "cross-phase-conflict-observation-a", "cross-phase-conflict-observation-b");
            });

    jdbc.update(
        "DELETE FROM identity_access.authorization_grant WHERE principal_kind = 'SERVICE' "
            + "AND principal_issuer = ? AND principal_identifier = ? "
            + "AND authority_code = 'CapacityEvidenceReview' AND scope_kind = 'FACILITY' "
            + "AND facility_identifier = ?",
        ISSUER,
        matchingOwner,
        destinationFacilityId);
    mockMvc
        .perform(
            get("/api/v1/referrals/{referralId}/recommendations", applicableReferralId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .header("X-Correlation-ID", "matching-applicability-owner-denied")
                .param("candidateFacilityId", destinationFacilityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentApplicability.status").value("INDETERMINATE"))
        .andExpect(
            jsonPath("$.currentApplicability.reasonCodes[0]")
                .value("CAPACITY_EVIDENCE_UNAVAILABLE"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE actor_kind = 'SERVICE' "
                    + "AND actor_reference = ? AND correlation_id = ? AND "
                    + "action_code = 'CAPACITY_MATCHING_EVALUATION_READ' "
                    + "AND outcome = 'DENIED' AND scope_kind = 'FACILITY' "
                    + "AND organization_reference = ? AND facility_reference = ? "
                    + "AND target_id = ?",
                Integer.class,
                matchingOwner,
                "matching-applicability-owner-denied",
                organizationId,
                destinationFacilityId,
                UUID.fromString(destinationFacilityId)))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE correlation_id = ? "
                    + "AND action_code = 'CAPACITY_MATCHING_EVALUATION_READ' "
                    + "AND outcome = 'SUCCEEDED'",
                Integer.class,
                "matching-applicability-owner-denied"))
        .isZero();
    grantService(
        matchingOwner, "CapacityEvidenceReview", "FACILITY", organizationId, destinationFacilityId);

    testPolicies.set(activePolicy(organizationId, 2));
    mockMvc
        .perform(
            get("/api/v1/referrals/{referralId}/recommendations", applicableReferralId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .param("candidateFacilityId", destinationFacilityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentApplicability.status").value("NOT_APPLICABLE"))
        .andExpect(
            jsonPath("$.currentApplicability.reasonCodes[0]")
                .value("POLICY_VERSION_NOT_PERMITTED"));
    mockMvc
        .perform(
            get("/api/v1/recommendations/{recommendationId}", applicableRecommendationId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matchingPolicy.version").value(1))
        .andExpect(jsonPath("$.mappingPolicy.version").value(1))
        .andExpect(jsonPath("$.candidates[0].status").value("RANKED_ELIGIBLE"));
    testPolicies.set(activePolicy(organizationId));

    recordCapacityObservation(
        organizationId,
        destinationFacilityId,
        "synthetic-cross-phase-destination-not-ready",
        "cross-phase-destination-observation-02",
        0);
    mockMvc
        .perform(
            get("/api/v1/recommendations/{recommendationId}", applicableRecommendationId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(applicableRecommendationId))
        .andExpect(jsonPath("$.outcome").value("COMPLETE"))
        .andExpect(jsonPath("$.candidates[0].status").value("RANKED_ELIGIBLE"));
    mockMvc
        .perform(
            get("/api/v1/referrals/{referralId}/recommendations", applicableReferralId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .header("X-Correlation-ID", "matching-applicability-not-ready")
                .param("candidateFacilityId", destinationFacilityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()", org.hamcrest.Matchers.is(1)))
        .andExpect(jsonPath("$.currentApplicability.status").value("NOT_APPLICABLE"))
        .andExpect(jsonPath("$.currentApplicability.reasonCodes[0]").value("NOT_READY"));
    assertMatchingApplicabilityOwnerAudit(
        "matching-applicability-not-ready",
        matchingOwner,
        organizationId,
        originFacilityId,
        destinationFacilityId,
        applicableReferralId,
        applicableRecommendationId);

    recordCapacityObservation(
        organizationId,
        destinationFacilityId,
        "synthetic-cross-phase-destination-restored",
        "cross-phase-destination-observation-03",
        4);
    correctFacilityCapabilityAssertion(
        destinationAssertionId, 1, "WITHDRAWN", "synthetic-cross-phase-capability-withdrawn");
    mockMvc
        .perform(
            get("/api/v1/referrals/{referralId}/recommendations", applicableReferralId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .param("candidateFacilityId", destinationFacilityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentApplicability.status").value("NOT_APPLICABLE"))
        .andExpect(
            jsonPath("$.currentApplicability.reasonCodes[0]")
                .value("REQUIRED_CAPABILITY_NOT_CURRENT"));
    mockMvc
        .perform(
            get("/api/v1/recommendations/{recommendationId}", applicableRecommendationId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matchingPolicy.version").value(1))
        .andExpect(jsonPath("$.candidates[0].status").value("RANKED_ELIGIBLE"));

    MvcResult laterRecommendation =
        mockMvc
            .perform(
                post("/api/v1/recommendations")
                    .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId)
                    .header("Idempotency-Key", "synthetic-cross-phase-non-positive-matching")
                    .contentType("application/json")
                    .content(
                        "{\"referralId\":\"" + applicableReferralId + "\",\"revisionNumber\":1}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.candidates[0].status").value("INELIGIBLE"))
            .andReturn();
    String laterRecommendationId =
        com.jayway.jsonpath.JsonPath.read(
            laterRecommendation.getResponse().getContentAsString(), "$.id");
    mockMvc
        .perform(
            get("/api/v1/referrals/{referralId}/recommendations", applicableReferralId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .param("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()", org.hamcrest.Matchers.is(1)))
        .andExpect(jsonPath("$.items[0].id").value(laterRecommendationId))
        .andExpect(jsonPath("$.latestRecommendationId").value(laterRecommendationId));
    mockMvc
        .perform(
            get("/api/v1/referrals/{referralId}/recommendations", applicableReferralId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()", org.hamcrest.Matchers.is(2)))
        .andExpect(jsonPath("$.items[0].id").value(laterRecommendationId))
        .andExpect(jsonPath("$.items[1].id").value(applicableRecommendationId))
        .andExpect(jsonPath("$.latestRecommendationId").value(laterRecommendationId));
    mockMvc
        .perform(
            get("/api/v1/referrals/{referralId}/recommendations", applicableReferralId)
                .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .param("limit", "101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MATCHING_REQUEST_INVALID"));
  }

  @Test
  void federatedReceivingCapacityUsesTypedLocalSubjectAndSharesCapacityAccounting()
      throws Exception {
    grantPlatform(CURATOR, "DirectoryCuration");
    grantPlatform(CURATOR, "DirectoryVocabularyAdministration");
    grantPlatform(CAPACITY_ADMIN, "CapacityPolicyAdministration");
    String organizationId = createOrganization("synthetic-federated-capacity-organization");
    String facilityId =
        createFacility(
            "synthetic-federated-capacity-facility", organizationId, "Synthetic Receiver");
    grantFacility(CAPACITY_REPORTER, "CapacityReporting", organizationId, facilityId);
    grantFacility(CAPACITY_REPORTER, "CapacityEvidenceReview", organizationId, facilityId);
    defineReservableCapacityDimension("SYN_FEDERATED_COORDINATION_SLOT");
    configureReservableCapacityPolicy("SYN_FEDERATED_COORDINATION_SLOT");
    recordReservableCapacityObservation(
        organizationId, facilityId, "SYN_FEDERATED_COORDINATION_SLOT", 4);

    String serviceClient = "synthetic-federated-receiving-worker";
    grantService(serviceClient, "CapacityReservation", "FACILITY", organizationId, facilityId);
    var scope =
        RequestedExecutionScope.facility(
            new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId),
            new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facilityId));
    var serviceContext =
        executionContextFactory.forIntegrationRequest(
            ActorReference.service(new IssuerId(ISSUER), serviceClient),
            scope,
            new CorrelationId("synthetic-federated-capacity-correlation"));
    InternalId localReceivingRequest = InternalId.generate();
    InternalId capacityOperation = InternalId.generate();
    Instant intervalStart = Instant.now().plusSeconds(60);
    Instant intervalEnd = intervalStart.plusSeconds(1800);
    var request =
        new FederatedReceivingCapacityReservationRequest(
            capacityOperation,
            localReceivingRequest,
            new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facilityId),
            "SYN_FEDERATED_COORDINATION_SLOT",
            "slot",
            new BigDecimal("4"),
            intervalStart,
            intervalEnd,
            intervalEnd);

    var reserved =
        capacityReservationCommands.reserveFederatedReceiving(
            serviceContext, request, "synthetic-federated-capacity-reserve");
    assertThat(reserved.outcome()).isEqualTo(CapacityReservationResult.Outcome.RESERVED);
    assertThat(reserved.reservationId()).isPresent();
    assertThat(
            jdbc.queryForObject(
                "SELECT request_fingerprint FROM capacity.reservation_operation "
                    + "WHERE operation_id = ?",
                String.class,
                capacityOperation.value()))
        .matches("[0-9a-f]{64}");
    var replayed =
        capacityReservationCommands.reserveFederatedReceiving(
            serviceContext, request, "synthetic-federated-capacity-reserve");
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.reservationId()).isEqualTo(reserved.reservationId());
    assertThat(
            jdbc.queryForMap(
                "SELECT subject_type, subject_id, transfer_id FROM capacity.coordination_reservation "
                    + "WHERE operation_id = ?",
                capacityOperation.value()))
        .containsEntry("subject_type", "FEDERATED_RECEIVING_REQUEST")
        .containsEntry("subject_id", localReceivingRequest.value())
        .containsEntry("transfer_id", null);

    var outcome =
        capacityReservationCommands.getFederatedReceivingReservationOutcome(
            serviceContext, capacityOperation, localReceivingRequest);
    assertThat(outcome.status()).isEqualTo(CapacityReservationOutcome.Status.RESERVED);
    assertThat(outcome.reservationId()).isEqualTo(reserved.reservationId());

    InternalId releaseOperation = InternalId.generate();
    var released =
        capacityReservationCommands.releaseFederatedReceiving(
            serviceContext, releaseOperation, capacityOperation, localReceivingRequest);
    assertThat(released.outcome()).isEqualTo(CapacityReleaseResult.Outcome.RELEASED);
    assertThat(
            capacityReservationCommands
                .getFederatedReceivingReservationOutcome(
                    serviceContext, capacityOperation, localReceivingRequest)
                .status())
        .isEqualTo(CapacityReservationOutcome.Status.RELEASED);

    var changedFederatedRequest =
        new FederatedReceivingCapacityReservationRequest(
            capacityOperation,
            localReceivingRequest,
            request.facility(),
            request.dimensionCode(),
            request.unit(),
            new BigDecimal("3"),
            intervalStart,
            intervalEnd,
            intervalEnd);
    assertThatThrownBy(
            () ->
                capacityReservationCommands.reserveFederatedReceiving(
                    serviceContext,
                    changedFederatedRequest,
                    "synthetic-federated-capacity-changed-input"))
        .isInstanceOf(PlatformProblemException.class)
        .satisfies(
            failure ->
                assertThat(((PlatformProblemException) failure).code().name())
                    .isEqualTo("IDEMPOTENCY_KEY_REUSED"));

    InternalId transferSubject = InternalId.generate();
    InternalId transferOperation = InternalId.generate();
    var transferRequest =
        new CapacityReservationRequest(
            transferOperation,
            transferSubject,
            request.facility(),
            request.dimensionCode(),
            request.unit(),
            BigDecimal.ONE,
            intervalStart,
            intervalEnd,
            intervalEnd);
    var transferReservation =
        capacityReservationCommands.reserve(
            serviceContext, transferRequest, "synthetic-transfer-capacity-reserve");
    assertThat(transferReservation.outcome()).isEqualTo(CapacityReservationResult.Outcome.RESERVED);
    assertThat(
            jdbc.queryForMap(
                "SELECT subject_type, subject_id, transfer_id FROM "
                    + "capacity.coordination_reservation WHERE operation_id = ?",
                transferOperation.value()))
        .containsEntry("subject_type", "TRANSFER")
        .containsEntry("subject_id", transferSubject.value())
        .containsEntry("transfer_id", transferSubject.value());
    assertThat(
            capacityReservationCommands
                .getReservationOutcome(serviceContext, transferOperation, transferSubject)
                .status())
        .isEqualTo(CapacityReservationOutcome.Status.RESERVED);
    var transferReleased =
        capacityReservationCommands.release(
            serviceContext, InternalId.generate(), transferOperation, transferSubject);
    assertThat(transferReleased.outcome()).isEqualTo(CapacityReleaseResult.Outcome.RELEASED);
    assertThat(
            capacityReservationCommands
                .getReservationOutcome(serviceContext, transferOperation, transferSubject)
                .status())
        .isEqualTo(CapacityReservationOutcome.Status.RELEASED);

    assertThat(
            jdbc.queryForList(
                "SELECT subject_type, subject_id, transfer_id FROM "
                    + "capacity.coordination_reservation WHERE subject_id IN (?, ?) "
                    + "ORDER BY subject_type",
                localReceivingRequest.value(),
                transferSubject.value()))
        .hasSize(2);

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity.coordination_reservation WHERE subject_type = ? "
                    + "AND subject_id = ?",
                Integer.class,
                "FEDERATED_RECEIVING_REQUEST",
                localReceivingRequest.value()))
        .isEqualTo(1);
  }

  @Test
  void namedFederatedReceivingHttpOperationsAuthorizePeerAndRecoverStatus() throws Exception {
    grantPlatform(CURATOR, "DirectoryCuration");
    grantPlatform(CURATOR, "DirectoryVocabularyAdministration");
    String organizationId = createOrganization("synthetic-federated-http-organization");
    String facilityId =
        createFacility(
            "synthetic-federated-http-facility", organizationId, "Synthetic HTTP Receiver");

    var receiver =
        new org.ayuvahak.platform.federation.domain.DeploymentId("SYN-DEPLOYMENT-RECEIVER");
    var origin =
        new org.ayuvahak.platform.federation.domain.DeploymentId(
            "SYN-DEPLOYMENT-HTTP-ORIGIN-" + UUID.randomUUID());
    String remoteDirectoryIssuer = "urn:ayuvahak:deployment:" + receiver.value() + ":directory";
    var localOrganization =
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId);
    var localFacility =
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facilityId);
    var remoteOrganization =
        new ExternalReference(new IssuerId(remoteDirectoryIssuer), "Organization", organizationId);
    var remoteFacility =
        new ExternalReference(new IssuerId(remoteDirectoryIssuer), "Facility", facilityId);
    var requestOperation =
        org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation
            .transferRemoteReceivingRequest(
                org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction.INBOUND);
    var statusOperation =
        org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation
            .transferRemoteReservationStatus(
                org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction.INBOUND);
    String serviceClient = "synthetic-integration-service";
    var principal = ActorReference.service(new IssuerId(ISSUER), serviceClient);
    var partnerKey =
        new org.ayuvahak.platform.exchange.domain.PartnerKey(
            "synthetic-http-peer-" + UUID.randomUUID());
    var configuration =
        new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration(
            partnerKey,
            localOrganization,
            "https://synthetic-federation-peer.example.test",
            "/federation",
            new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Authentication(
                org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AuthenticationMethod
                    .MUTUAL_TLS,
                Optional.of("secretref:SYNTHETIC-PEER-CERTIFICATE"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Limits(
                1_000, 2_000, 5_000, 100_000, 200_000, 1, 100, 16, 1),
            org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.RetryClass.NEVER,
            List.of(requestOperation, statusOperation),
            Optional.of(principal),
            Optional.of(
                new org.ayuvahak.platform.exchange.domain.FederationPeerConfiguration(
                    origin,
                    localOrganization,
                    org.ayuvahak.platform.exchange.domain.FederationPeerConfiguration
                        .supportedProtocolProfile(),
                    List.of(localFacility))));
    new TransactionTemplate(transactionManager)
        .execute(
            ignored -> {
              partnerTrustProfileStore.createInitial(configuration, Instant.now());
              partnerTrustProfileStore.activate(partnerKey, 1, Instant.now());
              return null;
            });

    InternalId originJourneyId = InternalId.generate();
    InternalId operationId = InternalId.generate();
    String requestJson =
        "{\"protocolProfile\":\"AyuVahak-Federation-v1\","
            + "\"sourceDeploymentId\":\""
            + origin.value()
            + "\","
            + "\"originContext\":\"transfer\",\"originResourceType\":\"TransferJourney\","
            + "\"originJourneyId\":\""
            + originJourneyId
            + "\","
            + "\"originFacilityId\":\"SYN-FACILITY-ORIGIN\","
            + "\"destinationOrganization\":{\"issuer\":\""
            + remoteDirectoryIssuer
            + "\",\"resourceType\":\"Organization\",\"value\":\""
            + organizationId
            + "\"},\"destinationFacility\":{\"issuer\":\""
            + remoteDirectoryIssuer
            + "\",\"resourceType\":\"Facility\",\"value\":\""
            + facilityId
            + "\"},\"capacityDimensionCode\":\"SYN_FEDERATED_HTTP_SLOT\","
            + "\"capacityUnit\":\"slot\",\"capacityQuantity\":1,"
            + "\"capacityIntervalStart\":\"2026-10-18T12:00:00Z\","
            + "\"capacityIntervalEnd\":\"2026-10-18T12:30:00Z\"}";
    byte[] requestBody = requestJson.getBytes(StandardCharsets.UTF_8);
    var requestFingerprint =
        org.ayuvahak.platform.exchange.application.FederationOperationFingerprint.calculate(
            origin, receiver, remoteFacility, requestOperation, requestBody);

    mockMvc
        .perform(
            post("/api/federation/operations/transfer.remote-receiving-request")
                .header("Authorization", bearer(serviceToken(serviceClient)))
                .header("X-AyuVahak-Federation-Source", origin.value())
                .header("X-AyuVahak-Federation-Peer", receiver.value())
                .header("X-AyuVahak-Federation-Operation", requestOperation.operationCode())
                .header("X-AyuVahak-Operation-Id", operationId)
                .header("X-AyuVahak-Operation-Fingerprint", requestFingerprint.value())
                .contentType("application/json")
                .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.protocolProfile").value("AyuVahak-Federation-v1"))
        .andExpect(jsonPath("$.sourceDeploymentId").value(receiver.value()))
        .andExpect(jsonPath("$.operationId").value(operationId.toString()))
        .andExpect(jsonPath("$.requestFingerprint").value(requestFingerprint.value()))
        .andExpect(jsonPath("$.outcome").value("RECEIVED"));

    var untrustedOrigin =
        new org.ayuvahak.platform.federation.domain.DeploymentId(
            "SYN-DEPLOYMENT-UNTRUSTED-" + UUID.randomUUID());
    byte[] untrustedBody =
        requestJson
            .replace(origin.value(), untrustedOrigin.value())
            .getBytes(StandardCharsets.UTF_8);
    var untrustedFingerprint =
        org.ayuvahak.platform.exchange.application.FederationOperationFingerprint.calculate(
            untrustedOrigin, receiver, remoteFacility, requestOperation, untrustedBody);
    mockMvc
        .perform(
            post("/api/federation/operations/transfer.remote-receiving-request")
                .header("Authorization", bearer(serviceToken(serviceClient)))
                .header("X-AyuVahak-Federation-Source", untrustedOrigin.value())
                .header("X-AyuVahak-Federation-Peer", receiver.value())
                .header("X-AyuVahak-Federation-Operation", requestOperation.operationCode())
                .header("X-AyuVahak-Operation-Id", InternalId.generate())
                .header("X-AyuVahak-Operation-Fingerprint", untrustedFingerprint.value())
                .contentType("application/json")
                .content(untrustedBody))
        .andExpect(status().isForbidden());

    String statusJson =
        "{\"protocolProfile\":\"AyuVahak-Federation-v1\","
            + "\"sourceDeploymentId\":\""
            + origin.value()
            + "\","
            + "\"operationId\":\""
            + operationId
            + "\","
            + "\"requestFingerprint\":\""
            + requestFingerprint.value()
            + "\","
            + "\"destinationOrganization\":{\"issuer\":\""
            + remoteDirectoryIssuer
            + "\",\"resourceType\":\"Organization\",\"value\":\""
            + organizationId
            + "\"},\"destinationFacility\":{\"issuer\":\""
            + remoteDirectoryIssuer
            + "\",\"resourceType\":\"Facility\",\"value\":\""
            + facilityId
            + "\"}}";
    byte[] statusBody = statusJson.getBytes(StandardCharsets.UTF_8);
    var statusFingerprint =
        org.ayuvahak.platform.exchange.application.FederationOperationFingerprint.calculate(
            origin, receiver, remoteFacility, statusOperation, statusBody);
    mockMvc
        .perform(
            post("/api/federation/operations/transfer.remote-reservation-status")
                .header("Authorization", bearer(serviceToken(serviceClient)))
                .header("X-AyuVahak-Federation-Source", origin.value())
                .header("X-AyuVahak-Federation-Peer", receiver.value())
                .header("X-AyuVahak-Federation-Operation", statusOperation.operationCode())
                .header("X-AyuVahak-Operation-Id", operationId)
                .header("X-AyuVahak-Operation-Fingerprint", statusFingerprint.value())
                .contentType("application/json")
                .content(statusBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("RECEIVED"))
        .andExpect(jsonPath("$.receivingVersion").value(1));

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.federated_receiving_request "
                    + "WHERE origin_deployment_id = ? AND request_operation_id = ?",
                Integer.class,
                origin.value(),
                operationId.value()))
        .isEqualTo(1);
  }

  @Test
  void originAttemptRecoversSameReceiverReservationAcrossIndependentDatabases() throws Exception {
    grantPlatform(CURATOR, "DirectoryCuration");
    grantPlatform(CURATOR, "DirectoryVocabularyAdministration");
    String dimension =
        "SYN_FEDERATED_PEER_RECOVERY_"
            + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT)
            + "_SLOT";
    grantPlatform(CAPACITY_ADMIN, "CapacityPolicyAdministration");
    String organizationId = createOrganization("synthetic-peer-recovery-organization");
    String facilityId =
        createFacility(
            "synthetic-peer-recovery-facility", organizationId, "Synthetic Peer Receiver");
    grantFacility(CAPACITY_REPORTER, "CapacityReporting", organizationId, facilityId);
    grantFacility(CAPACITY_REPORTER, "CapacityEvidenceReview", organizationId, facilityId);
    defineReservableCapacityDimension(dimension);
    configureReservableCapacityPolicy(dimension);
    recordReservableCapacityObservation(organizationId, facilityId, dimension, 3);
    grantService(
        "ayuvahak-transfer-reservation-worker",
        "CapacityReservation",
        "FACILITY",
        organizationId,
        facilityId);
    grantService(
        "ayuvahak-transfer-reservation-worker",
        "TransferReservationReconciliation",
        "FACILITY",
        organizationId,
        facilityId);
    grantFacility(
        "synthetic-peer-recovery-clinical",
        "ClinicalSuitabilityAuthority",
        organizationId,
        facilityId);
    grantFacility(
        "synthetic-peer-recovery-operational",
        "OperationalReadinessAuthority",
        organizationId,
        facilityId);
    grantFacility(
        "synthetic-peer-recovery-acceptance", "AcceptanceAuthority", organizationId, facilityId);

    var receiver =
        new org.ayuvahak.platform.federation.domain.DeploymentId("SYN-DEPLOYMENT-RECEIVER");
    var origin =
        new org.ayuvahak.platform.federation.domain.DeploymentId(
            "SYN-DEPLOYMENT-ORIGIN-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    var localOrganization =
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId);
    var localFacility =
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facilityId);
    String remoteDirectoryIssuer = "urn:ayuvahak:deployment:" + receiver.value() + ":directory";
    var remoteOrganization =
        new ExternalReference(new IssuerId(remoteDirectoryIssuer), "Organization", organizationId);
    var remoteFacility =
        new ExternalReference(new IssuerId(remoteDirectoryIssuer), "Facility", facilityId);
    var partnerKey =
        new org.ayuvahak.platform.exchange.domain.PartnerKey(
            "synthetic-origin-peer-" + origin.value().substring(origin.value().length() - 8));
    String serviceClient = "synthetic-federated-origin-peer";
    var inboundPrincipal = ActorReference.service(new IssuerId(ISSUER), serviceClient);
    var inboundRequest =
        org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation
            .transferRemoteReceivingRequest(
                org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction.INBOUND);
    var inboundStatus =
        org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation
            .transferRemoteReservationStatus(
                org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction.INBOUND);
    configureFederationProfile(
        partnerKey,
        origin,
        localOrganization,
        localFacility,
        inboundPrincipal,
        List.of(inboundRequest, inboundStatus));

    String originDatabaseUrl = ORIGIN_POSTGRES.getJdbcUrl();
    assertThat(originDatabaseUrl).isNotEqualTo(POSTGRES.getJdbcUrl());
    try (com.zaxxer.hikari.HikariDataSource originDataSource =
        new com.zaxxer.hikari.HikariDataSource()) {
      originDataSource.setJdbcUrl(originDatabaseUrl);
      originDataSource.setUsername(ORIGIN_POSTGRES.getUsername());
      originDataSource.setPassword(ORIGIN_POSTGRES.getPassword());
      org.flywaydb.core.Flyway.configure()
          .dataSource(originDataSource)
          .locations("classpath:db/migration")
          .load()
          .migrate();
      JdbcTemplate originJdbc = new JdbcTemplate(originDataSource);
      var originTransactionManager =
          new org.springframework.jdbc.datasource.DataSourceTransactionManager(originDataSource);
      Instant now = Instant.now();
      InternalId journeyId = InternalId.generate();
      InternalId referralId = InternalId.generate();
      InternalId originOrganizationId = InternalId.generate();
      InternalId originFacilityId = InternalId.generate();
      ActorReference originActor =
          ActorReference.human(new IssuerId("urn:synthetic:identity"), "SYN-ORIGIN-OPERATOR");
      originJdbc.update(
          "INSERT INTO transfer.journey (journey_id, referral_id, origin_organization_id, origin_facility_id, "
              + "created_by_kind, created_by_issuer, created_by_identifier_type, created_by_identifier, "
              + "created_at, transport_required, version) VALUES (?, ?, ?, ?, 'HUMAN', ?, 'subject', ?, ?, false, 1)",
          journeyId.value(),
          referralId.value(),
          originOrganizationId.value(),
          originFacilityId.value(),
          originActor.identity().issuer().value(),
          originActor.identity().value(),
          java.sql.Timestamp.from(now));
      originJdbc.update(
          "INSERT INTO transfer.request_attempt (request_attempt_id, journey_id, attempt_number, referral_id, "
              + "referral_revision, recommendation_id, destination_facility_id, request_status, requested_by_kind, "
              + "requested_by_issuer, requested_by_identifier_type, requested_by_identifier, requested_at, expires_at, "
              + "terminal_occurred_at, terminal_recorded_at, version) VALUES (?, ?, 1, ?, 1, ?, ?, 'REJECTED', "
              + "'HUMAN', ?, 'subject', ?, ?, ?, NULL, NULL, 2)",
          InternalId.generate().value(),
          journeyId.value(),
          referralId.value(),
          InternalId.generate().value(),
          InternalId.generate().value(),
          originActor.identity().issuer().value(),
          originActor.identity().value(),
          java.sql.Timestamp.from(now.minusSeconds(120)),
          java.sql.Timestamp.from(now.minusSeconds(60)));

      var originReferences =
          new org.ayuvahak.transfer.domain.TransferJourney(
              journeyId,
              referralId,
              new ExternalReference(
                  new IssuerId(DIRECTORY_ISSUER), "Organization", originOrganizationId.toString()),
              Optional.of(
                  new ExternalReference(
                      new IssuerId(DIRECTORY_ISSUER), "Facility", originFacilityId.toString())),
              originActor,
              now,
              false,
              1);
      var idempotencyProperties =
          new org.ayuvahak.platform.idempotency.configuration.CommandIdempotencyProperties();
      var originTransferStore =
          new org.ayuvahak.transfer.adapter.persistence.JdbcTransferStore(
              originJdbc, originDataSource, idempotencyProperties);
      var originAttemptStore =
          new org.ayuvahak.transfer.adapter.persistence.JdbcFederatedOriginReceivingAttemptStore(
              originJdbc);
      org.ayuvahak.matching.application.FederationLocalDeploymentIdentity originIdentity =
          () -> Optional.of(origin);
      var outboundRequest =
          org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation
              .transferRemoteReceivingRequest(
                  org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction
                      .OUTBOUND);
      var outboundStatus =
          org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation
              .transferRemoteReservationStatus(
                  org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction
                      .OUTBOUND);
      var outboundProfile =
          outboundFederationProfile(
              partnerKey,
              receiver,
              remoteOrganization,
              remoteFacility,
              List.of(outboundRequest, outboundStatus));
      var originProfileStore = outboundProfileStore(outboundProfile);
      var originIdentityStore =
          new org.ayuvahak.platform.federation.application.DeploymentIdentityStore() {
            private org.ayuvahak.platform.federation.domain.DeploymentIdentity registered;

            @Override
            public org.ayuvahak.platform.federation.domain.DeploymentIdentity registerOrVerify(
                org.ayuvahak.platform.federation.domain.DeploymentId deploymentId,
                String binding,
                Instant registeredAt) {
              registered =
                  new org.ayuvahak.platform.federation.domain.DeploymentIdentity(
                      deploymentId, 1, registeredAt, registeredAt);
              return registered;
            }

            @Override
            public Optional<org.ayuvahak.platform.federation.domain.DeploymentIdentity>
                findCurrent() {
              return Optional.ofNullable(registered);
            }
          };
      var originRegistration =
          new org.ayuvahak.platform.federation.application.DeploymentIdentityRegistrationService(
              originIdentityStore);
      originRegistration.register(origin, "SYNTHETIC-ORIGIN-INSTALLATION-BINDING-0000001", now);
      var originAuthorization =
          new org.ayuvahak.platform.exchange.application.PartnerAuthorizationPort() {
            @Override
            public Optional<org.ayuvahak.platform.exchange.application.PartnerBearerToken>
                bearerToken(org.ayuvahak.platform.exchange.domain.PartnerProfileVersion ignored) {
              return Optional.empty();
            }

            @Override
            public void invalidate(org.ayuvahak.platform.exchange.domain.PartnerKey ignored) {}
          };
      int[] peerRequests = {0};
      org.ayuvahak.platform.exchange.application.FederationPeerOperationTransportPort
          controlledPeer =
              (profile, peerRequest, token, beforeAttempt) -> {
                beforeAttempt.run();
                var inboundOperation =
                    peerRequest.operation().operationCode().equals(outboundRequest.operationCode())
                        ? inboundRequest
                        : inboundStatus;
                String route =
                    inboundOperation.equals(inboundRequest)
                        ? "/api/federation/operations/transfer.remote-receiving-request"
                        : "/api/federation/operations/transfer.remote-reservation-status";
                try {
                  var response =
                      mockMvc
                          .perform(
                              post(route)
                                  .header("Authorization", bearer(serviceToken(serviceClient)))
                                  .header("X-AyuVahak-Federation-Source", origin.value())
                                  .header("X-AyuVahak-Federation-Peer", receiver.value())
                                  .header(
                                      "X-AyuVahak-Federation-Operation",
                                      inboundOperation.operationCode())
                                  .header(
                                      "X-AyuVahak-Operation-Id",
                                      peerRequest.operationId().toString())
                                  .header(
                                      "X-AyuVahak-Operation-Fingerprint",
                                      peerRequest.fingerprint().value())
                                  .contentType("application/json")
                                  .content(peerRequest.body()))
                          .andReturn();
                  if (response.getResponse().getStatus() != 200) {
                    throw new AssertionError(
                        "local receiving peer returned " + response.getResponse().getStatus());
                  }
                  byte[] responseBody = response.getResponse().getContentAsByteArray();
                  if (inboundOperation.equals(inboundRequest) && peerRequests[0]++ == 0) {
                    var result = new tools.jackson.databind.ObjectMapper().readTree(responseBody);
                    InternalId receivingRequestId =
                        InternalId.parse(result.get("receivingRequestId").asString());
                    acceptFederatedReceivingForPeerRecovery(
                        receivingRequestId,
                        peerRequest.operationId(),
                        localOrganization,
                        localFacility,
                        dimension,
                        now,
                        java.util.UUID.fromString(organizationId),
                        java.util.UUID.fromString(facilityId));
                    throw new org.ayuvahak.platform.exchange.application.PartnerTransportException(
                        org.ayuvahak.platform.exchange.application.PartnerTransportFailure
                            .READ_TIMEOUT);
                  }
                  return new org.ayuvahak.platform.exchange.application
                      .PartnerOutboundOperationResponse(
                      response.getResponse().getStatus(),
                      Optional.of("application/json"),
                      responseBody,
                      profile.version());
                } catch (
                    org.ayuvahak.platform.exchange.application.PartnerTransportException failure) {
                  throw failure;
                } catch (Exception failure) {
                  throw new AssertionError("controlled local peer exchange failed", failure);
                }
              };
      var operationService =
          new org.ayuvahak.platform.exchange.application.FederationPeerOperationService(
              originProfileStore, originRegistration, originAuthorization, controlledPeer);
      var originExchange =
          new org.ayuvahak.transfer.adapter.federation.Phase9FederatedReceivingOriginExchange(
              operationService, originIdentity, new tools.jackson.databind.ObjectMapper());
      var applicability =
          (org.ayuvahak.transfer.application.MatchingApplicabilityPort)
              (context, referral, recommendation, candidate, revision) ->
                  new org.ayuvahak.matching.application.RecommendationApplicabilityResult(
                      recommendation,
                      candidate,
                      org.ayuvahak.matching.application.RecommendationApplicabilityResult.Status
                          .APPLICABLE,
                      List.of(),
                      Instant.now());
      var peerPolicy =
          (org.ayuvahak.matching.application.FederatedCapacityPeerPolicy)
              (facility, ignoredDimension) ->
                  Optional.of(
                      new org.ayuvahak.matching.application.RemoteCapacityPeerTarget(
                          partnerKey,
                          receiver,
                          outboundProfile.version(),
                          new org.ayuvahak.matching.domain.RemoteCapacityEvidenceCompatibility(
                              "SYNTHETIC-V1", 1, 1)));
      var authorityCatalog =
          new org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog(
              List.of(org.ayuvahak.transfer.domain.TransferAuthorities.request()));
      var originAuthorizationService =
          new org.ayuvahak.platform.authorization.application.AuthorizationService(
              (actor, authority, scope) -> true, authorityCatalog);
      var originAudit =
          new org.ayuvahak.platform.audit.application.AuditWriter(
              new org.ayuvahak.platform.audit.adapter.persistence.JdbcAuditRecordAppender(
                  originJdbc, originTransactionManager),
              java.time.Clock.systemUTC());
      var originOutboxStore =
          new org.ayuvahak.platform.eventing.adapter.persistence.PostgresOutboxStore(
              originJdbc,
              new org.ayuvahak.platform.event.EventEnvelopeJsonCodec(
                  new tools.jackson.databind.ObjectMapper()),
              java.time.Clock.systemUTC(),
              originTransactionManager);
      var originOutbox =
          new org.ayuvahak.platform.eventing.application.OutboxWriter(originOutboxStore);
      var coordinator =
          new org.ayuvahak.transfer.application.FederatedReceivingOriginOperationCoordinator(
              originAttemptStore,
              originTransferStore,
              originExchange,
              originAudit,
              originOutbox,
              new tools.jackson.databind.ObjectMapper(),
              originTransactionManager,
              java.time.Clock.systemUTC());
      var originCommand =
          new org.ayuvahak.transfer.application.FederatedReceivingOriginCommands(
              originTransferStore,
              originAttemptStore,
              applicability,
              peerPolicy,
              originIdentity,
              originAuthorizationService,
              originAudit,
              originOutbox,
              coordinator,
              new tools.jackson.databind.ObjectMapper(),
              originTransactionManager,
              java.time.Clock.systemUTC());
      var originContext =
          new ExecutionContextFactory((actor, scope) -> true)
              .forInteractiveRequest(
                  originActor,
                  RequestedExecutionScope.facility(
                      originReferences.originOrganization(),
                      originReferences.originFacility().orElseThrow()),
                  CorrelationId.generate());
      var originCommandInput =
          new org.ayuvahak.transfer.application.FederatedReceivingOriginCommand(
              1,
              1,
              InternalId.generate(),
              remoteOrganization,
              new org.ayuvahak.matching.domain.RemoteFacilityReference(
                  receiver, "directory", InternalId.parse(facilityId)),
              dimension,
              "slot",
              BigDecimal.ONE,
              now.plusSeconds(300),
              now.plusSeconds(2100));

      var first =
          originCommand.request(
              originContext, journeyId, originCommandInput, "synthetic-peer-recovery-key");
      assertThat(first.attempt().state())
          .isEqualTo(
              org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt.State.OUTCOME_UNKNOWN);
      var recovered =
          originCommand.request(
              originContext, journeyId, originCommandInput, "synthetic-peer-recovery-key");
      assertThat(recovered.replayed()).isTrue();
      assertThat(recovered.attempt().state())
          .isEqualTo(org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt.State.RESOLVED);
      assertThat(recovered.attempt().operationId()).isEqualTo(first.attempt().operationId());
      assertThat(recovered.attempt().remoteCapacityReservationId()).isPresent();
      java.util.UUID committedReservationId =
          jdbc.queryForObject(
              "SELECT reservation_id FROM capacity.coordination_reservation "
                  + "WHERE subject_type = 'FEDERATED_RECEIVING_REQUEST' AND subject_id = ?",
              java.util.UUID.class,
              receivingRequestIdForOperation(origin, first.attempt().operationId()).value());
      assertThat(recovered.attempt().remoteCapacityReservationId().orElseThrow().value())
          .isEqualTo(committedReservationId);
      assertThat(
              originJdbc.queryForObject(
                  "SELECT COUNT(*) FROM transfer.journey WHERE journey_id = ?",
                  Integer.class,
                  journeyId.value()))
          .isEqualTo(1);
      assertThat(
              originJdbc.queryForObject(
                  "SELECT COUNT(*) FROM transfer.federated_origin_receiving_attempt WHERE journey_id = ?",
                  Integer.class,
                  journeyId.value()))
          .isEqualTo(1);
      assertThat(
              originJdbc.queryForObject(
                  "SELECT COUNT(*) FROM transfer.federated_origin_receiving_receipt",
                  Integer.class))
          .isEqualTo(1);
      Integer receiverRequestCount =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM transfer.federated_receiving_request "
                  + "WHERE origin_deployment_id = ? AND request_operation_id = ?",
              Integer.class,
              new Object[] {origin.value(), first.attempt().operationId().value()});
      Integer reservationCount =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM capacity.coordination_reservation "
                  + "WHERE subject_type = 'FEDERATED_RECEIVING_REQUEST' AND subject_id = ?",
              Integer.class,
              new Object[] {
                receivingRequestIdForOperation(origin, first.attempt().operationId()).value()
              });
      assertThat(receiverRequestCount).isEqualTo(1);
      assertThat(reservationCount).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM transfer.journey WHERE journey_id = ?",
                  Integer.class,
                  journeyId.value()))
          .isZero();
    }
  }

  private void configureFederationProfile(
      org.ayuvahak.platform.exchange.domain.PartnerKey partnerKey,
      org.ayuvahak.platform.federation.domain.DeploymentId peer,
      ExternalReference organization,
      ExternalReference facility,
      ActorReference inboundPrincipal,
      List<org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation>
          operations) {
    var configuration =
        new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration(
            partnerKey,
            organization,
            "https://synthetic-federation-peer.example.test",
            "/federation",
            new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Authentication(
                org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AuthenticationMethod
                    .MUTUAL_TLS,
                Optional.of("secretref:SYNTHETIC-PEER-CERTIFICATE"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Limits(
                1_000, 2_000, 5_000, 100_000, 200_000, 1, 100, 16, 1),
            org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.RetryClass.NEVER,
            operations,
            Optional.of(inboundPrincipal),
            Optional.of(
                new org.ayuvahak.platform.exchange.domain.FederationPeerConfiguration(
                    peer,
                    organization,
                    org.ayuvahak.platform.exchange.domain.FederationPeerConfiguration
                        .supportedProtocolProfile(),
                    List.of(facility))));
    new TransactionTemplate(transactionManager)
        .execute(
            ignored -> {
              partnerTrustProfileStore.createInitial(configuration, Instant.now());
              partnerTrustProfileStore.activate(partnerKey, 1, Instant.now());
              return null;
            });
  }

  private static org.ayuvahak.platform.exchange.domain.PartnerProfileVersion
      outboundFederationProfile(
          org.ayuvahak.platform.exchange.domain.PartnerKey partnerKey,
          org.ayuvahak.platform.federation.domain.DeploymentId peer,
          ExternalReference organization,
          ExternalReference facility,
          List<org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation>
              operations) {
    Instant now = Instant.now();
    var configuration =
        new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration(
            partnerKey,
            organization,
            "https://synthetic-federation-peer.example.test",
            "/federation",
            new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Authentication(
                org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AuthenticationMethod
                    .MUTUAL_TLS,
                Optional.of("secret-ref:SYN-PEER-CERT"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Limits(
                1_000, 2_000, 5_000, 100_000, 200_000, 1, 100, 16, 1),
            org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.RetryClass.NEVER,
            operations,
            Optional.empty(),
            Optional.of(
                new org.ayuvahak.platform.exchange.domain.FederationPeerConfiguration(
                    peer,
                    organization,
                    org.ayuvahak.platform.exchange.domain.FederationPeerConfiguration
                        .supportedProtocolProfile(),
                    List.of(facility))));
    return new org.ayuvahak.platform.exchange.domain.PartnerProfileVersion(
        InternalId.generate(),
        1,
        org.ayuvahak.platform.exchange.domain.PartnerProfileState.ACTIVE,
        configuration,
        now,
        now);
  }

  private static org.ayuvahak.platform.exchange.application.PartnerTrustProfileStore
      outboundProfileStore(
          org.ayuvahak.platform.exchange.domain.PartnerProfileVersion activeProfile) {
    return (org.ayuvahak.platform.exchange.application.PartnerTrustProfileStore)
        Proxy.newProxyInstance(
            org.ayuvahak.platform.exchange.application.PartnerTrustProfileStore.class
                .getClassLoader(),
            new Class<?>[] {
              org.ayuvahak.platform.exchange.application.PartnerTrustProfileStore.class
            },
            (proxy, method, arguments) -> {
              if (method.getName().equals("findActivePeer")) {
                return Optional.of(activeProfile)
                    .filter(
                        profile ->
                            profile.configuration().partnerKey().equals(arguments[0])
                                && profile
                                    .configuration()
                                    .allowedOperations()
                                    .contains(arguments[2])
                                && profile.configuration().federationPeer().stream()
                                    .anyMatch(
                                        peer ->
                                            peer.deploymentId().equals(arguments[1])
                                                && peer.permits(
                                                    profile.configuration().organization(),
                                                    (ExternalReference) arguments[3])));
              }
              if (method.getName().equals("toString")) {
                return "SyntheticOutboundFederationProfileStore";
              }
              throw new UnsupportedOperationException(
                  "unexpected profile-store operation in local peer test: " + method.getName());
            });
  }

  private void acceptFederatedReceivingForPeerRecovery(
      InternalId receivingRequestId,
      InternalId originOperationId,
      ExternalReference organization,
      ExternalReference facility,
      String dimension,
      Instant requestTime,
      java.util.UUID organizationId,
      java.util.UUID facilityId) {
    var clinicalContext =
        executionContextFactory.forInteractiveRequest(
            ActorReference.human(new IssuerId(ISSUER), "synthetic-peer-recovery-clinical"),
            RequestedExecutionScope.facility(organization, facility),
            CorrelationId.generate());
    var operationalContext =
        executionContextFactory.forInteractiveRequest(
            ActorReference.human(new IssuerId(ISSUER), "synthetic-peer-recovery-operational"),
            RequestedExecutionScope.facility(organization, facility),
            CorrelationId.generate());
    var acceptanceContext =
        executionContextFactory.forInteractiveRequest(
            ActorReference.human(new IssuerId(ISSUER), "synthetic-peer-recovery-acceptance"),
            RequestedExecutionScope.facility(organization, facility),
            CorrelationId.generate());
    var clinical =
        federatedReceivingCommands.recordAssessment(
            clinicalContext,
            receivingRequestId,
            org.ayuvahak.transfer.domain.FederatedReceivingRequest.AssessmentKind.CLINICAL,
            org.ayuvahak.transfer.domain.FederatedReceivingRequest.Outcome.SUPPORTS,
            "SYNTHETIC_CLINICAL_REVIEW",
            requestTime,
            1);
    var operational =
        federatedReceivingCommands.recordAssessment(
            operationalContext,
            receivingRequestId,
            org.ayuvahak.transfer.domain.FederatedReceivingRequest.AssessmentKind.OPERATIONAL,
            org.ayuvahak.transfer.domain.FederatedReceivingRequest.Outcome.SUPPORTS,
            "SYNTHETIC_OPERATIONAL_REVIEW",
            requestTime,
            clinical.version());
    federatedReceivingCapacityFailureProbe.loseNextResponseAfterReservationCommit();
    var accepted =
        federatedReceivingCommands.accept(
            acceptanceContext, receivingRequestId, requestTime, operational.version());
    assertThat(accepted.status())
        .isEqualTo(
            org.ayuvahak.transfer.domain.FederatedReceivingRequest.Status.ACCEPTED_UNRESERVED);
    assertThat(accepted.reservationStatus())
        .isEqualTo(
            org.ayuvahak.transfer.domain.FederatedReceivingRequest.ReservationStatus
                .OUTCOME_UNKNOWN);
    assertThat(accepted.capacityOperationId()).get().isNotEqualTo(originOperationId);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity.coordination_reservation WHERE subject_type = "
                    + "'FEDERATED_RECEIVING_REQUEST' AND subject_id = ?",
                Integer.class,
                receivingRequestId.value()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity.coordination_reservation WHERE facility_identifier = ? "
                    + "AND dimension_code = ?",
                Integer.class,
                facilityId,
                dimension))
        .isEqualTo(1);
  }

  private InternalId receivingRequestIdForOperation(
      org.ayuvahak.platform.federation.domain.DeploymentId origin, InternalId operationId) {
    java.util.UUID value =
        jdbc.queryForObject(
            "SELECT receiving_request_id FROM transfer.federated_receiving_request "
                + "WHERE origin_deployment_id = ? AND request_operation_id = ?",
            java.util.UUID.class,
            origin.value(),
            operationId.value());
    return new InternalId(value);
  }

  @Test
  void federatedReceivingRequestReceiptIsPeerScopedAndReplaysWithoutCreatingLocalTransfer()
      throws Exception {
    grantPlatform(CURATOR, "DirectoryCuration");
    grantPlatform(CURATOR, "DirectoryVocabularyAdministration");
    String organizationId = createOrganization("synthetic-federated-receiving-organization");
    String facilityId =
        createFacility(
            "synthetic-federated-receiving-facility", organizationId, "Synthetic Receiver");
    InternalId remoteTransferId = InternalId.generate();
    InternalId requestOperationId = InternalId.generate();
    var destinationOrganization =
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId);
    var destinationFacility =
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facilityId);
    var operation =
        org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation
            .transferRemoteReceivingRequest(
                org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction.INBOUND);
    var peer =
        new org.ayuvahak.platform.federation.domain.DeploymentId(
            "SYN-DEPLOYMENT-ORIGIN-" + UUID.randomUUID());
    var authorization =
        configureAndAuthorizeSyntheticFederationPeer(
            "synthetic-origin-receipt-" + UUID.randomUUID(),
            peer,
            destinationOrganization,
            destinationFacility,
            operation);
    assertThat(authorization.profileVersion()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                federationPeerInboundAuthorizationService.authorize(
                    authorization.authenticatedPeerPrincipal(),
                    peer,
                    operation,
                    new ExternalReference(
                        new IssuerId(DIRECTORY_ISSUER), "Facility", UUID.randomUUID().toString())))
        .isInstanceOf(
            org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorizationException
                .class);
    assertThatThrownBy(
            () ->
                federationPeerInboundAuthorizationService.authorize(
                    authorization.authenticatedPeerPrincipal(),
                    peer,
                    org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation
                        .transferMovementFact(
                            org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration
                                .Direction.INBOUND),
                    destinationFacility))
        .isInstanceOf(
            org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorizationException
                .class);
    byte[] body = "synthetic-receiving-request-v1".getBytes(StandardCharsets.UTF_8);
    var fingerprint =
        org.ayuvahak.platform.exchange.application.FederationOperationFingerprint.calculate(
            peer,
            new org.ayuvahak.platform.federation.domain.DeploymentId("SYN-DEPLOYMENT-RECEIVER"),
            destinationFacility,
            operation,
            body);
    var request =
        new org.ayuvahak.transfer.application.FederatedReceivingRequestInput(
            remoteTransferId,
            "SYN-FACILITY-ORIGIN",
            destinationOrganization,
            destinationFacility,
            "SYN_FEDERATED_COORDINATION_SLOT",
            "slot",
            BigDecimal.ONE,
            Instant.parse("2026-10-18T12:00:00Z"),
            Instant.parse("2026-10-18T12:30:00Z"));

    var created =
        federatedReceivingCommands.receive(authorization, requestOperationId, fingerprint, request);
    var replayed =
        federatedReceivingCommands.receive(authorization, requestOperationId, fingerprint, request);

    assertThat(created.request().id()).isEqualTo(replayed.request().id());
    assertThat(created.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(created.request().originDeployment()).isEqualTo(peer);
    assertThat(created.request().originJourneyId()).isEqualTo(remoteTransferId);
    assertThat(created.request().status().name()).isEqualTo("RECEIVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.transfer_record WHERE transfer_id = ?",
                Integer.class,
                remoteTransferId.value()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.federated_receiving_request "
                    + "WHERE origin_deployment_id = ? AND request_operation_id = ?",
                Integer.class,
                peer.value(),
                requestOperationId.value()))
        .isEqualTo(1);

    byte[] changedBody = "changed-synthetic-receiving-request-v1".getBytes(StandardCharsets.UTF_8);
    var changedFingerprint =
        org.ayuvahak.platform.exchange.application.FederationOperationFingerprint.calculate(
            peer,
            new org.ayuvahak.platform.federation.domain.DeploymentId("SYN-DEPLOYMENT-RECEIVER"),
            destinationFacility,
            operation,
            changedBody);
    assertThatThrownBy(
            () ->
                federatedReceivingCommands.receive(
                    authorization, requestOperationId, changedFingerprint, request))
        .isInstanceOf(PlatformProblemException.class)
        .satisfies(
            failure ->
                assertThat(((PlatformProblemException) failure).code().name())
                    .isEqualTo("EXCHANGE_REPLAY_CONFLICT"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = ? "
                    + "AND target_id = ? AND outcome = 'DENIED' "
                    + "AND reason_code = 'OPERATION_FINGERPRINT_CONFLICT'",
                Integer.class,
                "TRANSFER_FEDERATED_RECEIVING_REPLAY_CONFLICT",
                created.request().id().value()))
        .as("changed operation content must leave durable security audit evidence")
        .isEqualTo(1);
  }

  @Test
  void federatedCapacityEvidenceUsesTrustedPeerAndReturnsVersionedOwnerEvidence() throws Exception {
    grantPlatform(CURATOR, "DirectoryCuration");
    grantPlatform(CURATOR, "DirectoryVocabularyAdministration");
    grantPlatform(CAPACITY_ADMIN, "CapacityPolicyAdministration");
    String organizationId =
        createOrganization("synthetic-federated-capacity-evidence-organization");
    String facilityId =
        createFacility(
            "synthetic-federated-capacity-evidence-facility", organizationId, "Synthetic Capacity");
    String dimension = "SYN_FEDERATED_CAPACITY_EVIDENCE";
    defineCapacityDimension(
        dimension,
        "synthetic-federated-capacity-dimension-" + UUID.randomUUID(),
        CAPACITY_ADMIN,
        false,
        "unit");
    configureCapacityPolicy(
        dimension, "synthetic-federated-capacity-policy-" + UUID.randomUUID(), CAPACITY_ADMIN, 600);
    grantService(
        "synthetic-federated-capacity-peer",
        "CapacityEvidenceReview",
        "FACILITY",
        organizationId,
        facilityId);
    grantFacility(CAPACITY_REPORTER, "CapacityReporting", organizationId, facilityId);
    Instant sourceObservedAt =
        Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    Instant sourceValidUntil = sourceObservedAt.plusSeconds(4 * 60 * 60);
    recordCapacityObservation(
        CAPACITY_REPORTER,
        organizationId,
        facilityId,
        dimension,
        "synthetic-federated-capacity-observation-" + UUID.randomUUID(),
        7,
        sourceObservedAt,
        sourceValidUntil);

    var localDeployment =
        new org.ayuvahak.platform.federation.domain.DeploymentId("SYN-DEPLOYMENT-RECEIVER");
    var sourceDeployment =
        new org.ayuvahak.platform.federation.domain.DeploymentId("SYN-DEPLOYMENT-CAPACITY-ORIGIN");
    String peerDirectoryIssuer =
        "urn:ayuvahak:deployment:" + localDeployment.value() + ":directory";
    var peerOrganization =
        new ExternalReference(new IssuerId(peerDirectoryIssuer), "Organization", organizationId);
    var peerFacility =
        new ExternalReference(new IssuerId(peerDirectoryIssuer), "Facility", facilityId);
    var operation =
        org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation
            .capacityRemoteEvidence(
                org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction.INBOUND);
    var partnerKey =
        new org.ayuvahak.platform.exchange.domain.PartnerKey(
            "synthetic-capacity-peer-" + UUID.randomUUID());
    var principal =
        ActorReference.service(new IssuerId(ISSUER), "synthetic-federated-capacity-peer");
    var configuration =
        new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration(
            partnerKey,
            peerOrganization,
            "https://synthetic-federation-peer.example.test",
            "/federation",
            new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Authentication(
                org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AuthenticationMethod
                    .MUTUAL_TLS,
                Optional.of("secretref:SYNTHETIC-CAPACITY-PEER-CERTIFICATE"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Limits(
                1_000, 2_000, 5_000, 100_000, 200_000, 1, 100, 16, 1),
            org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.RetryClass.NEVER,
            List.of(operation),
            Optional.of(principal),
            Optional.of(
                new org.ayuvahak.platform.exchange.domain.FederationPeerConfiguration(
                    sourceDeployment,
                    peerOrganization,
                    org.ayuvahak.platform.exchange.domain.FederationPeerConfiguration
                        .supportedProtocolProfile(),
                    List.of(peerFacility))));
    new TransactionTemplate(transactionManager)
        .execute(
            ignored -> {
              partnerTrustProfileStore.createInitial(configuration, Instant.now());
              partnerTrustProfileStore.activate(partnerKey, 1, Instant.now());
              return null;
            });

    byte[] requestBody =
        ("{\"contractVersion\":\"capacity-remote-evidence-v1\","
                + "\"deploymentId\":\"SYN-DEPLOYMENT-RECEIVER\","
                + "\"context\":\"directory\",\"resourceId\":\""
                + facilityId
                + "\",\"dimensionCode\":\""
                + dimension
                + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    InternalId operationId = InternalId.generate();
    String fingerprint =
        org.ayuvahak.platform.exchange.application.FederationOperationFingerprint.calculate(
                sourceDeployment, localDeployment, peerFacility, operation, requestBody)
            .value();
    mockMvc
        .perform(
            post("/api/federation/operations/capacity.remote-evidence")
                .header("Authorization", bearer(serviceToken("synthetic-federated-capacity-peer")))
                .header("X-AyuVahak-Federation-Source", sourceDeployment.value())
                .header("X-AyuVahak-Federation-Peer", localDeployment.value())
                .header("X-AyuVahak-Federation-Operation", operation.operationCode())
                .header("X-AyuVahak-Operation-Id", operationId.toString())
                .header("X-AyuVahak-Operation-Fingerprint", fingerprint)
                .contentType("application/json")
                .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contractVersion").value("capacity-remote-evidence-v1"))
        .andExpect(jsonPath("$.deploymentId").value(localDeployment.value()))
        .andExpect(jsonPath("$.resourceId").value(facilityId))
        .andExpect(jsonPath("$.dimensionCode").value(dimension))
        .andExpect(jsonPath("$.sourceSequence").isNumber())
        .andExpect(jsonPath("$.dimensionVersion").value(1))
        .andExpect(jsonPath("$.policyVersion").value(1))
        .andExpect(jsonPath("$.outcome").value("READY"))
        .andExpect(jsonPath("$.freshness").value("CURRENT"))
        .andExpect(jsonPath("$.complete").value(true))
        .andExpect(jsonPath("$.quantity").value(7))
        .andExpect(jsonPath("$.validUntil").value(sourceObservedAt.plusSeconds(600).toString()));

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE actor_kind = 'SERVICE' "
                    + "AND actor_reference = ? AND action_code = 'CAPACITY_FEDERATED_EVIDENCE_READ' "
                    + "AND target_type = 'Facility' AND target_id = ? AND outcome = 'SUCCEEDED'",
                Integer.class,
                "synthetic-federated-capacity-peer",
                UUID.fromString(facilityId)))
        .isEqualTo(1);
  }

  @Test
  void federatedDirectoryDiscoveryServesOnlyAuthorizedOwnerFacts() throws Exception {
    grantPlatform(CURATOR, "DirectoryCuration");
    grantPlatform(CURATOR, "DirectoryVocabularyAdministration");
    String organizationId = createOrganization("synthetic-federated-directory-organization");
    String facilityId =
        createFacility(
            "synthetic-federated-directory-facility", organizationId, "Synthetic Remote Facility");
    String peerClient = "synthetic-federated-directory-peer";
    grantService(peerClient, "DirectoryRead", "ORGANIZATION", organizationId, null);

    var localDeployment =
        new org.ayuvahak.platform.federation.domain.DeploymentId("SYN-DEPLOYMENT-RECEIVER");
    var sourceDeployment =
        new org.ayuvahak.platform.federation.domain.DeploymentId("SYN-DEPLOYMENT-DIRECTORY-ORIGIN");
    String localIssuer = "urn:ayuvahak:deployment:" + localDeployment.value() + ":directory";
    var peerOrganization =
        new ExternalReference(new IssuerId(localIssuer), "Organization", organizationId);
    var operation =
        org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation
            .directoryRemoteDiscovery(
                org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction.INBOUND);
    var partnerKey =
        new org.ayuvahak.platform.exchange.domain.PartnerKey(
            "synthetic-directory-peer-" + UUID.randomUUID());
    var principal = ActorReference.service(new IssuerId(ISSUER), peerClient);
    var configuration =
        new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration(
            partnerKey,
            peerOrganization,
            "https://synthetic-directory-peer.example.test",
            "/federation",
            new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Authentication(
                org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AuthenticationMethod
                    .MUTUAL_TLS,
                Optional.of("secretref:SYNTHETIC-DIRECTORY-PEER-CERTIFICATE"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Limits(
                1_000, 2_000, 5_000, 100_000, 200_000, 1, 100, 16, 1),
            org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.RetryClass.NEVER,
            List.of(operation),
            Optional.of(principal),
            Optional.of(
                new org.ayuvahak.platform.exchange.domain.FederationPeerConfiguration(
                    sourceDeployment,
                    peerOrganization,
                    org.ayuvahak.platform.exchange.domain.FederationPeerConfiguration
                        .supportedProtocolProfile(),
                    List.of())));
    new TransactionTemplate(transactionManager)
        .execute(
            ignored -> {
              partnerTrustProfileStore.createInitial(configuration, Instant.now());
              partnerTrustProfileStore.activate(partnerKey, 1, Instant.now());
              return null;
            });

    var operationScope =
        new ExternalReference(new IssuerId(localIssuer), "Organization", organizationId);
    byte[] requestBody =
        ("{\"protocolProfile\":\"AyuVahak-Federation-v1\","
                + "\"sourceDeploymentId\":\""
                + sourceDeployment.value()
                + "\",\"requestedScope\":{\"issuer\":\""
                + localIssuer
                + "\",\"resourceType\":\"Organization\",\"value\":\""
                + organizationId
                + "\"},\"resourceTypes\":[\"Organization\",\"Facility\"],"
                + "\"maximumFacts\":10}")
            .getBytes(StandardCharsets.UTF_8);
    InternalId operationId = InternalId.generate();
    String fingerprint =
        org.ayuvahak.platform.exchange.application.FederationOperationFingerprint.calculate(
                sourceDeployment, localDeployment, operationScope, operation, requestBody)
            .value();

    mockMvc
        .perform(
            post("/api/federation/operations/directory.remote-discovery")
                .header("Authorization", bearer(serviceToken(peerClient)))
                .header("X-AyuVahak-Federation-Source", sourceDeployment.value())
                .header("X-AyuVahak-Federation-Peer", localDeployment.value())
                .header("X-AyuVahak-Federation-Operation", operation.operationCode())
                .header("X-AyuVahak-Operation-Id", operationId.toString())
                .header("X-AyuVahak-Operation-Fingerprint", fingerprint)
                .contentType("application/json")
                .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceDeploymentId").value(localDeployment.value()))
        .andExpect(jsonPath("$.completeness").value("COMPLETE"))
        .andExpect(jsonPath("$.facts.length()").value(2))
        .andExpect(jsonPath("$.facts[0].resourceType").value("Organization"))
        .andExpect(jsonPath("$.facts[0].resourceId").value(organizationId))
        .andExpect(jsonPath("$.facts[1].resourceType").value("Facility"))
        .andExpect(jsonPath("$.facts[1].resourceId").value(facilityId))
        .andExpect(jsonPath("$.facts[1].organizationResourceId").value(organizationId));
  }

  @Test
  void federatedReceivingAcceptancePersistsDistinctCapacityOperationAndReservesLocally()
      throws Exception {
    String dimension = "SYN_FEDERATED_ACCEPTANCE_SLOT";
    grantPlatform(CURATOR, "DirectoryCuration");
    grantPlatform(CURATOR, "DirectoryVocabularyAdministration");
    grantPlatform(CAPACITY_ADMIN, "CapacityPolicyAdministration");
    String organizationId = createOrganization("synthetic-federated-acceptance-organization");
    String facilityId =
        createFacility(
            "synthetic-federated-acceptance-facility", organizationId, "Synthetic Receiver");
    grantFacility(CAPACITY_REPORTER, "CapacityReporting", organizationId, facilityId);
    grantFacility(CAPACITY_REPORTER, "CapacityEvidenceReview", organizationId, facilityId);
    defineReservableCapacityDimension(dimension);
    configureReservableCapacityPolicy(dimension);
    recordReservableCapacityObservation(organizationId, facilityId, dimension, 4);
    String worker = "ayuvahak-transfer-reservation-worker";
    grantService(worker, "CapacityReservation", "FACILITY", organizationId, facilityId);
    grantService(
        worker, "TransferReservationReconciliation", "FACILITY", organizationId, facilityId);

    grantFacility(
        "synthetic-receiver-clinical", "ClinicalSuitabilityAuthority", organizationId, facilityId);
    grantFacility(
        "synthetic-receiver-operational",
        "OperationalReadinessAuthority",
        organizationId,
        facilityId);
    grantFacility(
        "synthetic-receiver-acceptance", "AcceptanceAuthority", organizationId, facilityId);
    ExternalReference destinationOrganization =
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId);
    ExternalReference destinationFacility =
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facilityId);
    var peer =
        new org.ayuvahak.platform.federation.domain.DeploymentId(
            "SYN-DEPLOYMENT-ORIGIN-" + UUID.randomUUID());
    InternalId originTransferId = InternalId.generate();
    InternalId operationId = InternalId.generate();
    var inboundOperation =
        org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation
            .transferRemoteReceivingRequest(
                org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction.INBOUND);
    var peerAuthorization =
        configureAndAuthorizeSyntheticFederationPeer(
            "synthetic-origin-accept-" + UUID.randomUUID(),
            peer,
            destinationOrganization,
            destinationFacility,
            inboundOperation);
    Instant intervalStart = Instant.now().plusSeconds(60);
    Instant intervalEnd = intervalStart.plusSeconds(1800);
    var request =
        new org.ayuvahak.transfer.application.FederatedReceivingRequestInput(
            originTransferId,
            "SYN-FACILITY-ORIGIN",
            destinationOrganization,
            destinationFacility,
            dimension,
            "slot",
            BigDecimal.ONE,
            intervalStart,
            intervalEnd);
    byte[] body = "synthetic-receiver-acceptance-v1".getBytes(StandardCharsets.UTF_8);
    var fingerprint =
        org.ayuvahak.platform.exchange.application.FederationOperationFingerprint.calculate(
            peer,
            new org.ayuvahak.platform.federation.domain.DeploymentId("SYN-DEPLOYMENT-RECEIVER"),
            destinationFacility,
            inboundOperation,
            body);
    var received =
        federatedReceivingCommands.receive(peerAuthorization, operationId, fingerprint, request);

    var clinicalContext =
        executionContextFactory.forInteractiveRequest(
            ActorReference.human(new IssuerId(ISSUER), "synthetic-receiver-clinical"),
            RequestedExecutionScope.facility(destinationOrganization, destinationFacility),
            new CorrelationId("synthetic-receiver-clinical-assessment"));
    var operationalContext =
        executionContextFactory.forInteractiveRequest(
            ActorReference.human(new IssuerId(ISSUER), "synthetic-receiver-operational"),
            RequestedExecutionScope.facility(destinationOrganization, destinationFacility),
            new CorrelationId("synthetic-receiver-operational-assessment"));
    var acceptanceContext =
        executionContextFactory.forInteractiveRequest(
            ActorReference.human(new IssuerId(ISSUER), "synthetic-receiver-acceptance"),
            RequestedExecutionScope.facility(destinationOrganization, destinationFacility),
            new CorrelationId("synthetic-receiver-acceptance"));
    jdbc.execute(
        "CREATE FUNCTION public.p11_test_fail_federated_receiving_event() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type = "
            + "'transfer.federated-receiving-request-changed.v1' THEN "
            + "RAISE EXCEPTION 'synthetic outbox failure'; END IF; RETURN NEW; END; $$");
    jdbc.execute(
        "CREATE TRIGGER p11_test_fail_federated_receiving_event BEFORE INSERT ON "
            + "platform_eventing.internal_event FOR EACH ROW EXECUTE FUNCTION "
            + "public.p11_test_fail_federated_receiving_event()");
    try {
      assertThatThrownBy(
              () ->
                  federatedReceivingCommands.recordAssessment(
                      clinicalContext,
                      received.request().id(),
                      org.ayuvahak.transfer.domain.FederatedReceivingRequest.AssessmentKind
                          .CLINICAL,
                      org.ayuvahak.transfer.domain.FederatedReceivingRequest.Outcome.SUPPORTS,
                      "SYNTHETIC_CLINICAL_REVIEW",
                      Instant.now(),
                      received.request().version()))
          .isInstanceOf(PlatformProblemException.class)
          .satisfies(
              failure ->
                  assertThat(((PlatformProblemException) failure).code().name())
                      .isEqualTo("TRANSFER_UNAVAILABLE"));
    } finally {
      jdbc.execute(
          "DROP TRIGGER p11_test_fail_federated_receiving_event ON "
              + "platform_eventing.internal_event");
      jdbc.execute("DROP FUNCTION public.p11_test_fail_federated_receiving_event()");
    }
    assertThat(
            jdbc.queryForMap(
                "SELECT request_state, request_version, clinical_outcome FROM "
                    + "transfer.federated_receiving_request WHERE receiving_request_id = ?",
                received.request().id().value()))
        .containsEntry("request_state", "RECEIVED")
        .containsEntry("request_version", 1L)
        .containsEntry("clinical_outcome", null);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.federated_receiving_assessment "
                    + "WHERE receiving_request_id = ?",
                Integer.class,
                received.request().id().value()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE target_type = "
                    + "'FederatedReceivingRequest' AND target_id = ? AND action_code = "
                    + "'TRANSFER_FEDERATED_CLINICAL_ASSESSMENT'",
                Integer.class,
                received.request().id().value()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_eventing.internal_event WHERE event_type = "
                    + "'transfer.federated-receiving-request-changed.v1' AND aggregate_ref = ?",
                Integer.class,
                "federated-receiving-request/" + received.request().id()))
        .isEqualTo(1);

    var clinical =
        federatedReceivingCommands.recordAssessment(
            clinicalContext,
            received.request().id(),
            org.ayuvahak.transfer.domain.FederatedReceivingRequest.AssessmentKind.CLINICAL,
            org.ayuvahak.transfer.domain.FederatedReceivingRequest.Outcome.SUPPORTS,
            "SYNTHETIC_CLINICAL_REVIEW",
            Instant.now(),
            received.request().version());
    var operational =
        federatedReceivingCommands.recordAssessment(
            operationalContext,
            received.request().id(),
            org.ayuvahak.transfer.domain.FederatedReceivingRequest.AssessmentKind.OPERATIONAL,
            org.ayuvahak.transfer.domain.FederatedReceivingRequest.Outcome.SUPPORTS,
            "SYNTHETIC_OPERATIONAL_REVIEW",
            Instant.now(),
            clinical.version());
    Instant reportedAcceptedAt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
    assertThatThrownBy(
            () ->
                federatedReceivingCommands.accept(
                    acceptanceContext,
                    received.request().id(),
                    Instant.now().plusSeconds(3600),
                    operational.version()))
        .isInstanceOf(PlatformProblemException.class)
        .satisfies(
            failure ->
                assertThat(((PlatformProblemException) failure).status())
                    .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));

    federatedReceivingCapacityFailureProbe.loseNextResponseAfterReservationCommit();
    var accepted =
        federatedReceivingCommands.accept(
            acceptanceContext, received.request().id(), reportedAcceptedAt, operational.version());

    assertThat(accepted.acceptedAt()).contains(reportedAcceptedAt);
    assertThat(accepted.updatedAt()).isAfter(reportedAcceptedAt);

    assertThat(accepted.status())
        .isEqualTo(
            org.ayuvahak.transfer.domain.FederatedReceivingRequest.Status.ACCEPTED_UNRESERVED);
    assertThat(accepted.reservationStatus())
        .isEqualTo(
            org.ayuvahak.transfer.domain.FederatedReceivingRequest.ReservationStatus
                .OUTCOME_UNKNOWN);
    InternalId capacityOperationId = accepted.capacityOperationId().orElseThrow();
    assertThat(capacityOperationId).isNotEqualTo(operationId).isNotEqualTo(originTransferId);
    assertThat(
            jdbc.queryForMap(
                "SELECT request_state, capacity_operation_id, capacity_reservation_status "
                    + "FROM transfer.federated_receiving_request WHERE receiving_request_id = ?",
                received.request().id().value()))
        .containsEntry("request_state", "ACCEPTED_UNRESERVED")
        .containsEntry("capacity_operation_id", capacityOperationId.value())
        .containsEntry("capacity_reservation_status", "OUTCOME_UNKNOWN");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.federated_receiving_assessment "
                    + "WHERE receiving_request_id = ?",
                Integer.class,
                received.request().id().value()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE target_type = "
                    + "'FederatedReceivingRequest' AND target_id = ? AND action_code IN ("
                    + "'TRANSFER_FEDERATED_RECEIVING_REQUEST_RECEIVE', "
                    + "'TRANSFER_FEDERATED_CLINICAL_ASSESSMENT', "
                    + "'TRANSFER_FEDERATED_OPERATIONAL_ASSESSMENT', "
                    + "'TRANSFER_FEDERATED_RECEIVING_ACCEPT') AND outcome = 'SUCCEEDED' "
                    + "AND authorization_outcome = 'ALLOWED'",
                Integer.class,
                received.request().id().value()))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE target_type = "
                    + "'FederatedReceivingRequest' AND target_id = ? AND action_code = "
                    + "'TRANSFER_FEDERATED_CAPACITY_RECONCILE' AND outcome = 'FAILED' "
                    + "AND reason_code = 'CAPACITY_OUTCOME_UNCONFIRMED'",
                Integer.class,
                received.request().id().value()))
        .isEqualTo(1);
    assertThat(federatedReceivingCapacityFailureProbe.reservationOperationIds())
        .containsExactly(capacityOperationId);
    assertThat(
            jdbc.queryForMap(
                "SELECT subject_type, subject_id, transfer_id FROM capacity.coordination_reservation "
                    + "WHERE operation_id = ?",
                capacityOperationId.value()))
        .containsEntry("subject_type", "FEDERATED_RECEIVING_REQUEST")
        .containsEntry("subject_id", received.request().id().value())
        .containsEntry("transfer_id", null);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity.coordination_reservation WHERE subject_type = ? "
                    + "AND subject_id = ?",
                Integer.class,
                "FEDERATED_RECEIVING_REQUEST",
                received.request().id().value()))
        .isEqualTo(1);

    var statusOperation =
        org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation
            .transferRemoteReservationStatus(
                org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction.INBOUND);
    var statusAuthorization =
        federationPeerInboundAuthorizationService.authorize(
            peerAuthorization.authenticatedPeerPrincipal(),
            peer,
            statusOperation,
            destinationFacility);
    var wrongScopeFacility =
        new ExternalReference(
            new IssuerId(DIRECTORY_ISSUER), "Facility", InternalId.generate().toString());
    assertThatThrownBy(
            () ->
                federatedReceivingCommands.reservationStatus(
                    statusAuthorization,
                    operationId,
                    new org.ayuvahak.platform.exchange.application.FederationOperationFingerprint(
                        received.request().requestFingerprint()),
                    destinationOrganization,
                    wrongScopeFacility))
        .isInstanceOf(PlatformProblemException.class)
        .satisfies(
            failure ->
                assertThat(((PlatformProblemException) failure).status())
                    .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE target_type = "
                    + "'FederatedReceivingRequest' AND target_id = ? AND action_code = "
                    + "'TRANSFER_FEDERATED_RESERVATION_STATUS_SCOPE_DENIED' "
                    + "AND outcome = 'DENIED' AND reason_code = 'PEER_RECEIVING_SCOPE_MISMATCH'",
                Integer.class,
                received.request().id().value()))
        .isEqualTo(1);
    var recovered =
        federatedReceivingCommands
            .reservationStatus(
                statusAuthorization,
                operationId,
                new org.ayuvahak.platform.exchange.application.FederationOperationFingerprint(
                    received.request().requestFingerprint()),
                destinationOrganization,
                destinationFacility)
            .request();
    assertThat(recovered.status())
        .isEqualTo(org.ayuvahak.transfer.domain.FederatedReceivingRequest.Status.ACCEPTED_RESERVED);
    assertThat(recovered.reservationStatus())
        .isEqualTo(
            org.ayuvahak.transfer.domain.FederatedReceivingRequest.ReservationStatus.RESERVED);
    assertThat(recovered.capacityOperationId()).contains(capacityOperationId);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE target_type = "
                    + "'FederatedReceivingRequest' AND target_id = ? AND action_code = "
                    + "'TRANSFER_FEDERATED_CAPACITY_RECONCILE' AND outcome = 'SUCCEEDED'",
                Integer.class,
                received.request().id().value()))
        .isEqualTo(1);
    assertThat(federatedReceivingCapacityFailureProbe.reservationOperationIds())
        .containsExactly(capacityOperationId);
    assertThat(federatedReceivingCapacityFailureProbe.outcomeOperationIds())
        .containsExactly(capacityOperationId, capacityOperationId);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity.coordination_reservation WHERE subject_type = ? "
                    + "AND subject_id = ?",
                Integer.class,
                "FEDERATED_RECEIVING_REQUEST",
                received.request().id().value()))
        .isEqualTo(1);
    String receivingAggregateRef = "federated-receiving-request/" + received.request().id();
    assertThat(
            jdbc.queryForList(
                "SELECT aggregate_sequence FROM platform_eventing.internal_event "
                    + "WHERE event_type = 'transfer.federated-receiving-request-changed.v1' "
                    + "AND aggregate_ref = ? ORDER BY aggregate_sequence",
                Long.class,
                receivingAggregateRef))
        .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_eventing.internal_event WHERE event_type = "
                    + "'transfer.federated-receiving-request-changed.v1' AND aggregate_ref = ? "
                    + "AND (SELECT COUNT(*) FROM jsonb_object_keys(envelope -> 'payload')) = 4 "
                    + "AND jsonb_exists(envelope -> 'payload', 'receivingRequestReference') "
                    + "AND jsonb_exists(envelope -> 'payload', 'requestVersion') "
                    + "AND jsonb_exists(envelope -> 'payload', 'requestState') "
                    + "AND jsonb_exists(envelope -> 'payload', 'capacityReservationStatus') "
                    + "AND NOT jsonb_exists(envelope -> 'payload', 'clinicalOutcome') "
                    + "AND NOT jsonb_exists(envelope -> 'payload', 'operationalOutcome') "
                    + "AND NOT jsonb_exists(envelope -> 'payload', 'reasonCode') "
                    + "AND envelope::text NOT LIKE '%SYN-PATIENT%'",
                Integer.class, receivingAggregateRef))
        .isEqualTo(6);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.transfer_record WHERE transfer_id = ?",
                Integer.class,
                originTransferId.value()))
        .isZero();
    new TransactionTemplate(transactionManager)
        .execute(
            ignored -> {
              partnerTrustProfileStore.revoke(peerAuthorization.partnerKey(), Instant.now());
              return null;
            });
    assertThatThrownBy(
            () ->
                federationPeerInboundAuthorizationService.authorize(
                    peerAuthorization.authenticatedPeerPrincipal(),
                    peer,
                    statusOperation,
                    destinationFacility))
        .isInstanceOf(
            org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorizationException
                .class);
  }

  private org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorization
      configureAndAuthorizeSyntheticFederationPeer(
          String partnerName,
          org.ayuvahak.platform.federation.domain.DeploymentId peer,
          ExternalReference organization,
          ExternalReference facility,
          org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation
              requestOperation) {
    var partnerKey = new org.ayuvahak.platform.exchange.domain.PartnerKey(partnerName);
    var principal =
        ActorReference.service(new IssuerId(ISSUER), "synthetic-origin-peer-" + UUID.randomUUID());
    var statusOperation =
        org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation
            .transferRemoteReservationStatus(
                org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction.INBOUND);
    var configuration =
        new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration(
            partnerKey,
            organization,
            "https://synthetic-federation-peer.example.test",
            "/federation",
            new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Authentication(
                org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AuthenticationMethod
                    .MUTUAL_TLS,
                Optional.of("secretref:SYNTHETIC-PEER-CERTIFICATE"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            new org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Limits(
                1_000, 2_000, 5_000, 100_000, 200_000, 1, 100, 16, 1),
            org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.RetryClass.NEVER,
            List.of(requestOperation, statusOperation),
            Optional.of(principal),
            Optional.of(
                new org.ayuvahak.platform.exchange.domain.FederationPeerConfiguration(
                    peer,
                    organization,
                    org.ayuvahak.platform.exchange.domain.FederationPeerConfiguration
                        .supportedProtocolProfile(),
                    List.of(facility))));
    new TransactionTemplate(transactionManager)
        .execute(
            ignored -> {
              partnerTrustProfileStore.createInitial(configuration, Instant.now());
              partnerTrustProfileStore.activate(partnerKey, 1, Instant.now());
              return null;
            });
    return federationPeerInboundAuthorizationService.authorize(
        principal, peer, requestOperation, facility);
  }

  private void assertMatchingApplicabilityOwnerAudit(
      String correlationId,
      String matchingOwner,
      String organizationId,
      String originFacilityId,
      String candidateFacilityId,
      String referralId,
      String recommendationId) {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE actor_kind = 'SERVICE' "
                    + "AND actor_reference = ? AND correlation_id = ? AND action_code = "
                    + "'REFERRAL_MATCHING_REQUIREMENT_READ' AND scope_kind = 'FACILITY' "
                    + "AND organization_reference = ? AND facility_reference = ? "
                    + "AND target_type = 'Referral' AND target_id = ?",
                Integer.class,
                matchingOwner,
                correlationId,
                organizationId,
                originFacilityId,
                UUID.fromString(referralId)))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE actor_kind = 'SERVICE' "
                    + "AND actor_reference = ? AND correlation_id = ? AND action_code = "
                    + "'DIRECTORY_MATCHING_CANDIDATE_READ' AND scope_kind = 'ORGANIZATION' "
                    + "AND organization_reference = ? AND facility_reference IS NULL "
                    + "AND target_type = 'Facility' AND target_id = ?",
                Integer.class,
                matchingOwner,
                correlationId,
                organizationId,
                UUID.fromString(candidateFacilityId)))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE actor_kind = 'SERVICE' "
                    + "AND actor_reference = ? AND correlation_id = ? AND action_code = "
                    + "'CAPACITY_MATCHING_EVALUATION_READ' AND scope_kind = 'FACILITY' "
                    + "AND organization_reference = ? AND facility_reference = ? "
                    + "AND target_type = 'Facility' AND target_id = ?",
                Integer.class,
                matchingOwner,
                correlationId,
                organizationId,
                candidateFacilityId,
                UUID.fromString(candidateFacilityId)))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE actor_kind = 'HUMAN' "
                    + "AND actor_reference = ? AND correlation_id = ? AND action_code = "
                    + "'MATCHING_RECOMMENDATION_APPLICABILITY_CHECK' "
                    + "AND facility_reference = ? AND target_type = 'Recommendation' "
                    + "AND target_id = ?",
                Integer.class,
                REFERRAL_ORIGINATOR,
                correlationId,
                originFacilityId,
                UUID.fromString(recommendationId)))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE correlation_id = ? "
                    + "AND action_code IN ('REFERRAL_MATCHING_REQUIREMENT_READ', "
                    + "'DIRECTORY_MATCHING_CANDIDATE_READ', "
                    + "'CAPACITY_MATCHING_EVALUATION_READ') AND "
                    + "(actor_kind <> 'SERVICE' OR actor_reference <> ?)",
                Integer.class,
                correlationId,
                matchingOwner))
        .isZero();
  }

  @Test
  void volumeTwoHappyPathRunsThroughDirectoryCapacityReferralMatchingAndTransfer()
      throws Exception {
    String prefix = "synthetic-volume2-e2e-" + UUID.randomUUID();
    PROVIDER_REQUESTS.set(0);
    PROVIDER_REQUEST_TARGET.set(null);
    String curator = prefix + "-curator";
    String capacityAdmin = prefix + "-capacity-admin";
    String reporter = prefix + "-reporter";
    String originator = prefix + "-originator";
    String mcsdWorker = "synthetic-mcsd-worker";
    String exchangeAdministrator = prefix + "-exchange-admin";
    String matchingOwner = "ayuvahak-matching-owner-read";
    String referralOutcomeConsumer = "ayuvahak-transfer-outcome-consumer";
    String reservationWorker = "ayuvahak-transfer-reservation-worker";
    grantPlatform(curator, "DirectoryCuration");
    grantPlatform(curator, "DirectoryVocabularyAdministration");
    grantPlatform(capacityAdmin, "CapacityPolicyAdministration");
    grantPlatform(exchangeAdministrator, "ExchangeTrustAdministration");
    grantService(mcsdWorker, "DirectoryCuration", "PLATFORM", null, null);
    configureDirectoryAndIpsPartner(exchangeAdministrator, prefix);
    String originOrganizationId = createOrganization(prefix + "-origin-org", curator);
    String destinationOrganizationId = importMcsdDestinationOrganization(mcsdWorker, prefix);
    String originFacilityId =
        createFacility(
            prefix + "-origin-facility", originOrganizationId, "Synthetic origin", curator);
    String facilityA =
        createFacility(
            prefix + "-facility-a", destinationOrganizationId, "Synthetic destination A", curator);
    String facilityB =
        createFacility(
            prefix + "-facility-b", destinationOrganizationId, "Synthetic destination B", curator);
    String facilityC =
        createFacility(
            prefix + "-facility-c", destinationOrganizationId, "Synthetic destination C", curator);
    String facilityD =
        createFacility(
            prefix + "-facility-d", destinationOrganizationId, "Synthetic destination D", curator);

    createLocation(originFacilityId, prefix + "-origin-location", 72.800, 19.100, curator);
    createLocation(facilityA, prefix + "-location-a", 72.810, 19.100, curator);
    createLocation(facilityB, prefix + "-location-b", 72.820, 19.100, curator);
    createLocation(facilityC, prefix + "-location-c", 72.830, 19.100, curator);
    createLocation(facilityD, prefix + "-location-d", 72.850, 19.100, curator);

    String icu = "SYN-ICU-" + prefix.substring(prefix.length() - 8).toUpperCase();
    String ct = "SYN-CT-" + prefix.substring(prefix.length() - 8).toUpperCase();
    String neuro = "SYN-NEURO-" + prefix.substring(prefix.length() - 8).toUpperCase();
    createCareCapability(icu, prefix + "-cap-icu", curator);
    createCareCapability(ct, prefix + "-cap-ct", curator);
    createCareCapability(neuro, prefix + "-cap-neuro", curator);
    createCapabilityAssertion(facilityA, icu, prefix + "-a-icu", curator);
    createCapabilityAssertion(facilityA, ct, prefix + "-a-ct", curator);
    for (String capability : List.of(icu, ct, neuro)) {
      createCapabilityAssertion(facilityB, capability, prefix + "-b-" + capability, curator);
      createCapabilityAssertion(facilityC, capability, prefix + "-c-" + capability, curator);
      createCapabilityAssertion(facilityD, capability, prefix + "-d-" + capability, curator);
    }

    String dimIcu = "SYN_ICU_CAPACITY_" + prefix.substring(prefix.length() - 8).toUpperCase();
    String dimCt = "SYN_CT_CAPACITY_" + prefix.substring(prefix.length() - 8).toUpperCase();
    String dimNeuro = "SYN_NEURO_CAPACITY_" + prefix.substring(prefix.length() - 8).toUpperCase();
    String reservationDimension = "SYN_TRANSFER_SLOT";
    for (String dimension : List.of(dimIcu, dimCt, dimNeuro)) {
      defineCapacityDimension(
          dimension, prefix + "-dimension-" + dimension, capacityAdmin, false, "unit");
      configureCapacityPolicy(dimension, prefix + "-policy-" + dimension, capacityAdmin, 600);
    }
    defineCapacityDimension(
        reservationDimension, prefix + "-reservation-dimension", capacityAdmin, true, "slot");
    configureCapacityPolicy(
        reservationDimension, prefix + "-reservation-policy", capacityAdmin, 600);

    List<String> candidateFacilities = List.of(facilityA, facilityB, facilityC, facilityD);
    for (String facility : candidateFacilities) {
      grantFacility(reporter, "CapacityReporting", destinationOrganizationId, facility);
      grantFacility(reporter, "CapacityEvidenceReview", destinationOrganizationId, facility);
    }
    Instant currentObservation = Instant.now().minusSeconds(2);
    for (String dimension : List.of(dimIcu, dimCt, dimNeuro)) {
      recordCapacityObservation(
          reporter,
          destinationOrganizationId,
          facilityA,
          dimension,
          prefix + "-a-" + dimension,
          2,
          currentObservation);
      recordCapacityObservation(
          reporter,
          destinationOrganizationId,
          facilityB,
          dimension,
          prefix + "-b-" + dimension,
          2,
          currentObservation);
      recordCapacityObservation(
          reporter,
          destinationOrganizationId,
          facilityC,
          dimension,
          prefix + "-c-" + dimension,
          2,
          Instant.now().minus(Duration.ofHours(2)));
      recordCapacityObservation(
          reporter,
          destinationOrganizationId,
          facilityD,
          dimension,
          prefix + "-d-" + dimension,
          2,
          currentObservation);
    }
    recordCapacityObservation(
        reporter,
        destinationOrganizationId,
        facilityB,
        reservationDimension,
        prefix + "-b-reservation",
        1,
        currentObservation);
    recordCapacityObservation(
        reporter,
        destinationOrganizationId,
        facilityD,
        reservationDimension,
        prefix + "-d-reservation",
        1,
        currentObservation);

    grantFacility(originator, "ReferralCreate", originOrganizationId, originFacilityId);
    grantFacility(originator, "ReferralSubmit", originOrganizationId, originFacilityId);
    grantFacility(originator, "ReferralRead", originOrganizationId, originFacilityId);
    grantFacility(originator, "RecommendationRequest", originOrganizationId, originFacilityId);
    grantFacility(originator, "RecommendationRead", originOrganizationId, originFacilityId);
    grantService(
        referralOutcomeConsumer,
        "ReferralResolve",
        "FACILITY",
        originOrganizationId,
        originFacilityId);
    String patientReference = prefix + "-opaque-patient-reference";
    String referralId =
        createVolumeTwoReferral(
            originator,
            originOrganizationId,
            originFacilityId,
            destinationOrganizationId,
            List.of(icu, ct, neuro),
            patientReference,
            prefix);
    mockMvc
        .perform(
            post("/api/v1/referrals/{referralId}/submission", referralId)
                .header("Authorization", bearer(token(originator)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", originOrganizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .header("Idempotency-Key", prefix + "-submit")
                .contentType("application/json")
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.submittedRevision.revisionNumber").value(1));

    grantService(matchingOwner, "ReferralRead", "FACILITY", originOrganizationId, originFacilityId);
    grantService(matchingOwner, "DirectoryRead", "ORGANIZATION", destinationOrganizationId, null);
    grantService(
        matchingOwner, "DirectoryRead", "FACILITY", originOrganizationId, originFacilityId);
    for (String facility : candidateFacilities) {
      grantService(
          matchingOwner, "CapacityEvidenceReview", "FACILITY", destinationOrganizationId, facility);
    }
    Map<String, Set<String>> requirementMapping =
        Map.of(icu, Set.of(dimIcu), ct, Set.of(dimCt), neuro, Set.of(dimNeuro));
    testPolicies.set(volumeTwoPolicy(destinationOrganizationId, requirementMapping));

    String recommendationBody =
        mockMvc
            .perform(
                post("/api/v1/recommendations")
                    .header("Authorization", bearer(token(originator)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", originOrganizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId)
                    .header("Idempotency-Key", prefix + "-recommendation")
                    .contentType("application/json")
                    .content("{\"referralId\":\"" + referralId + "\",\"revisionNumber\":1}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.outcome").value("COMPLETE"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(recommendationBody).doesNotContain(patientReference).contains("\"receivedAt\"");
    assertCandidateOutcome(recommendationBody, facilityA, "INELIGIBLE");
    assertCandidateOutcome(recommendationBody, facilityB, "RANKED_ELIGIBLE");
    assertCandidateOutcome(recommendationBody, facilityC, "INDETERMINATE");
    assertCandidateReason(recommendationBody, facilityA, "CAPABILITY_NOT_ASSERTED");
    assertCandidateReason(recommendationBody, facilityC, "CAPACITY_EVIDENCE_STALE");
    assertThat(readCapacityOutcomeCodes(recommendationBody, facilityB)).contains("READY_CURRENT");
    assertThat(readCapacityOutcomeCodes(recommendationBody, facilityC)).contains("STALE_EXPIRED");
    assertThat(readCandidateRank(recommendationBody, facilityB)).isEqualTo(1);
    String recommendationId = com.jayway.jsonpath.JsonPath.read(recommendationBody, "$.id");
    String matchingSnapshot =
        jdbc.queryForObject(
            "SELECT snapshot_document::text FROM matching.recommendation_snapshot "
                + "WHERE recommendation_id = ?",
            String.class,
            UUID.fromString(recommendationId));
    assertThat(matchingSnapshot).doesNotContain(patientReference, "PatientReference");

    grantFacility(originator, "TransferRequest", originOrganizationId, originFacilityId);
    String transferJourneyBody =
        mockMvc
            .perform(
                post("/api/v1/transfer-journeys")
                    .header("Authorization", bearer(token(originator)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", originOrganizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId)
                    .header("Idempotency-Key", prefix + "-transfer-request")
                    .contentType("application/json")
                    .content(
                        "{\"referralId\":\""
                            + referralId
                            + "\",\"referralRevision\":1,\"recommendationId\":\""
                            + recommendationId
                            + "\",\"candidateFacilityId\":\""
                            + facilityB
                            + "\",\"transportRequired\":true}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String requestAttemptId =
        com.jayway.jsonpath.JsonPath.read(transferJourneyBody, "$.requestAttemptId");

    grantFacility(
        prefix + "-clinical-authority",
        "ClinicalSuitabilityAuthority",
        destinationOrganizationId,
        facilityB);
    grantFacility(
        prefix + "-readiness-authority",
        "OperationalReadinessAuthority",
        destinationOrganizationId,
        facilityB);
    grantFacility(
        prefix + "-acceptance-authority",
        "AcceptanceAuthority",
        destinationOrganizationId,
        facilityB);
    mockMvc
        .perform(
            post(
                    "/api/v1/transfer-request-attempts/{attemptId}/clinical-suitability-assessments",
                    requestAttemptId)
                .header("Authorization", bearer(token(prefix + "-clinical-authority")))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", destinationOrganizationId)
                .header("X-AyuVahak-Facility-Id", facilityB)
                .header("Idempotency-Key", prefix + "-clinical-assessment")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":1,\"outcome\":\"SUPPORTS\","
                        + "\"reasonCode\":\"SYNTHETIC_REVIEW_COMPLETE\",\"occurredAt\":\""
                        + Instant.now()
                        + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.assessmentKind").value("CLINICAL_SUITABILITY"));
    mockMvc
        .perform(
            post(
                    "/api/v1/transfer-request-attempts/{attemptId}/operational-readiness-assessments",
                    requestAttemptId)
                .header("Authorization", bearer(token(prefix + "-readiness-authority")))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", destinationOrganizationId)
                .header("X-AyuVahak-Facility-Id", facilityB)
                .header("Idempotency-Key", prefix + "-readiness-assessment")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":2,\"outcome\":\"SUPPORTS\","
                        + "\"reasonCode\":\"SYNTHETIC_READINESS_CONFIRMED\",\"occurredAt\":\""
                        + Instant.now()
                        + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.assessmentKind").value("OPERATIONAL_READINESS"));

    grantService(
        reservationWorker, "CapacityReservation", "FACILITY", destinationOrganizationId, facilityB);
    grantService(
        reservationWorker,
        "TransferReservationReconciliation",
        "FACILITY",
        destinationOrganizationId,
        facilityB);
    MvcResult acceptance =
        mockMvc
            .perform(
                post("/api/v1/transfer-request-attempts/{attemptId}/acceptance", requestAttemptId)
                    .header("Authorization", bearer(token(prefix + "-acceptance-authority")))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", destinationOrganizationId)
                    .header("X-AyuVahak-Facility-Id", facilityB)
                    .header("Idempotency-Key", prefix + "-acceptance")
                    .contentType("application/json")
                    .content("{\"expectedVersion\":3,\"occurredAt\":\"" + Instant.now() + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(status().isCreated())
            .andReturn();
    String transferId =
        com.jayway.jsonpath.JsonPath.read(
            acceptance.getResponse().getContentAsString(), "$.transferId");
    var reservationDebug =
        jdbc.queryForMap(
            "SELECT operation_id, outcome, reason_code, attempt_count FROM "
                + "transfer.capacity_reservation_request WHERE transfer_id = ?",
            UUID.fromString(transferId));
    var capacityDebug =
        jdbc.queryForList(
            "SELECT operation_id, transfer_id, subject_type, subject_id, outcome, reason_code "
                + "FROM capacity.reservation_operation WHERE operation_id = ?",
            UUID.fromString(transferId));
    assertThat(reservationDebug.get("outcome"))
        .as(
            "transfer reservation intent %s; Capacity operations %s",
            reservationDebug, capacityDebug)
        .isEqualTo("RESERVED");
    String acceptanceStatus =
        com.jayway.jsonpath.JsonPath.read(
            acceptance.getResponse().getContentAsString(), "$.status");
    assertThat(acceptanceStatus).isEqualTo("READY");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation "
                    + "WHERE transfer_id = ?",
                String.class,
                UUID.fromString(transferId)))
        .isEqualTo("ACTIVE");

    String patientContextMarker = prefix + "-opaque-patient-reference";
    String receivingParticipant = prefix + "-receiving-participant";
    grantFacility(
        receivingParticipant,
        "TransferPatientContextParticipation",
        destinationOrganizationId,
        facilityB);
    grantFacility(
        receivingParticipant,
        "ReferralPatientContextDisclosure",
        destinationOrganizationId,
        facilityB);
    PARTNER_RESPONSE.set(syntheticIpsDocument(patientContextMarker));
    String patientContextBody =
        mockMvc
            .perform(
                get("/api/v1/referrals/{referralId}/patient-context", referralId)
                    .header("Authorization", bearer(token(receivingParticipant)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", destinationOrganizationId)
                    .header("X-AyuVahak-Facility-Id", facilityB)
                    .param("transferId", transferId)
                    .param("requestAttemptId", requestAttemptId)
                    .param("purposeCode", "CARE_COORDINATION"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.sections[0].category").value("PROBLEMS"))
            .andExpect(jsonPath("$.sections[0].codes[0].code").value("SYN-CODED-PROBLEM-001"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(patientContextBody).doesNotContain(patientContextMarker, "SYN-PRIVATE-NARRATIVE");
    assertThat(PROVIDER_REQUESTS).hasValue(0);

    // Continue the same synthetic Directory → Capacity → Referral → Matching → Transfer
    // scenario through Referral's authorized IPS disclosure and the Transport owner contract.
    grantService(
        "ayuvahak-transport-worker",
        "TransportRequestAuthority",
        "FACILITY",
        originOrganizationId,
        originFacilityId);
    grantService(
        "ayuvahak-transport-worker",
        "TransferTransportRequestEligibility",
        "FACILITY",
        originOrganizationId,
        originFacilityId);
    grantService(
        "ayuvahak-transfer-owner-event-consumer",
        "TransferProgressAuthority",
        "FACILITY",
        originOrganizationId,
        originFacilityId);
    activateSyntheticTransportProvider(exchangeAdministrator, prefix);
    grantService(
        "synthetic-transport-provider",
        "TransportStatusAuthority",
        "FACILITY",
        originOrganizationId,
        originFacilityId);
    String transportOperator = prefix + "-transport-operator";
    grantFacility(
        transportOperator, "TransportStatusAuthority", originOrganizationId, originFacilityId);
    grantFacility(
        transportOperator,
        "TransportDispatchCoordinationAuthority",
        originOrganizationId,
        originFacilityId);
    String transportRequestBody =
        mockMvc
            .perform(
                post("/api/v1/transfers/{transferId}/transport-request", transferId)
                    .header("Authorization", bearer(token(originator)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", originOrganizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId)
                    .header("Idempotency-Key", prefix + "-transport-request")
                    .contentType("application/json")
                    .content("{}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String transportRequestId =
        com.jayway.jsonpath.JsonPath.read(transportRequestBody, "$.transportRequestId");
    mockMvc
        .perform(
            post("/api/v1/transport-requests/{requestId}/provider-dispatch", transportRequestId)
                .header("Authorization", bearer(token(transportOperator)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", originOrganizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.state").value("ACKNOWLEDGED"));
    assertThat(PROVIDER_REQUEST_TARGET.get())
        .isEqualTo("POST /api/transport-requests/" + transportRequestId + "/dispatches");
    assertThat(PROVIDER_REQUESTS).hasValue(1);
    Instant transportBase = Instant.now().minusSeconds(30);
    recordIntegratedTransportStatus(
        transportRequestId,
        transportOperator,
        originOrganizationId,
        originFacilityId,
        prefix + "-transport-awaiting-assignment",
        "SYN-INTEGRATED-001",
        "AWAITING_ASSIGNMENT",
        transportBase);
    mockMvc
        .perform(
            post("/api/v1/transport-requests/{requestId}/assignments", transportRequestId)
                .header("Authorization", bearer(token(transportOperator)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", originOrganizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .header("Idempotency-Key", prefix + "-transport-assignment")
                .contentType("application/json")
                .content(
                    "{\"sourceNamespace\":\"urn:ayuvahak:synthetic-transport\","
                        + "\"sourceObservationId\":\"SYN-INTEGRATED-ASSIGNMENT\","
                        + "\"assignmentReference\":\"SYN-ASSIGNMENT-001\","
                        + "\"occurredAt\":\""
                        + transportBase.plusSeconds(1)
                        + "\"}"))
        .andExpect(status().isCreated());
    recordIntegratedTransportStatus(
        transportRequestId,
        transportOperator,
        originOrganizationId,
        originFacilityId,
        prefix + "-transport-enroute-origin",
        "SYN-INTEGRATED-002",
        "EN_ROUTE_TO_ORIGIN",
        transportBase.plusSeconds(2));
    recordIntegratedTransportStatus(
        transportRequestId,
        transportOperator,
        originOrganizationId,
        originFacilityId,
        prefix + "-transport-at-origin",
        "SYN-INTEGRATED-003",
        "AT_ORIGIN",
        transportBase.plusSeconds(3));
    recordIntegratedTransportStatus(
        transportRequestId,
        transportOperator,
        originOrganizationId,
        originFacilityId,
        prefix + "-transport-patient-onboard",
        "SYN-INTEGRATED-004",
        "PATIENT_ONBOARD",
        transportBase.plusSeconds(4));
    String providerDepartureBody =
        "{\"observationId\":\"SYN-PROVIDER-DEPARTURE-001\",\"sourceSequence\":1,"
            + "\"status\":\"EN_ROUTE_TO_DESTINATION\",\"occurredAt\":\""
            + transportBase.plusSeconds(5)
            + "\"}";
    var providerDeparture =
        mockMvc
            .perform(
                post(
                        "/api/v1/transport-provider/evidence/{transportRequestId}/status",
                        transportRequestId)
                    .header("Authorization", bearer(serviceToken("synthetic-transport-provider")))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", originOrganizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId)
                    .header("X-External-Message-ID", prefix + "-provider-departure-message")
                    .contentType("application/json")
                    .content(providerDepartureBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.requestStatus").value("EN_ROUTE_TO_DESTINATION"))
            .andReturn();
    assertThat(providerDeparture.getResponse().getHeader("X-Correlation-ID")).isNotBlank();
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                UUID.fromString(transferId)))
        .as("Transfer remains READY until provider movement evidence is dispatched")
        .isEqualTo("READY");
    var providerDepartureReplay =
        mockMvc
            .perform(
                post(
                        "/api/v1/transport-provider/evidence/{transportRequestId}/status",
                        transportRequestId)
                    .header("Authorization", bearer(serviceToken("synthetic-transport-provider")))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", originOrganizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId)
                    .header("X-External-Message-ID", prefix + "-provider-departure-message")
                    .contentType("application/json")
                    .content(providerDepartureBody))
            .andExpect(status().isCreated())
            .andReturn();
    assertThat(providerDepartureReplay.getResponse().getHeader("Idempotency-Replayed"))
        .isEqualTo("true");
    outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                UUID.fromString(transferId)))
        .isEqualTo("IN_TRANSIT");
    assertThat(
            jdbc.queryForList(
                "SELECT d.state FROM platform_eventing.internal_event e "
                    + "JOIN platform_eventing.outbox_delivery d USING (event_id) "
                    + "WHERE e.event_type = 'transport.departure-confirmed.v1' "
                    + "AND e.envelope -> 'payload' ->> 'transportRequestReference' = ?",
                transportRequestId))
        .containsExactly(Map.of("state", "DELIVERED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.consumer_event_receipt r "
                    + "JOIN platform_eventing.internal_event e USING (event_id) "
                    + "WHERE e.event_type = 'transport.departure-confirmed.v1' "
                    + "AND e.envelope -> 'payload' ->> 'transportRequestReference' = ? "
                    + "AND r.event_state = 'COMPLETED'",
                Integer.class,
                transportRequestId))
        .isEqualTo(1);
    recordIntegratedTransportStatus(
        transportRequestId,
        transportOperator,
        originOrganizationId,
        originFacilityId,
        prefix + "-transport-arrival",
        "SYN-INTEGRATED-006",
        "ARRIVED_DESTINATION",
        transportBase.plusSeconds(6));
    outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                UUID.fromString(transferId)))
        .isEqualTo("ARRIVED");

    grantFacility(
        prefix + "-receiver", "TransferProgressAuthority", destinationOrganizationId, facilityB);
    grantFacility(
        prefix + "-receiver", "TransferHandoverAuthority", destinationOrganizationId, facilityB);
    grantFacility(
        prefix + "-receiver", "TransferCompletionAuthority", destinationOrganizationId, facilityB);
    recordTransferProgress(
        transferId,
        "handover",
        prefix + "-receiver",
        destinationOrganizationId,
        facilityB,
        prefix + "-handover",
        4,
        "");
    String completion =
        recordTransferProgress(
            transferId,
            "completion",
            prefix + "-receiver",
            destinationOrganizationId,
            facilityB,
            prefix + "-completion",
            5,
            "\"reasonCode\":\"SYNTHETIC_COORDINATION_COMPLETE\"");
    assertThat(completion).contains("\"status\":\"COMPLETED\"");

    for (int attempt = 0; attempt < 5; attempt++) {
      outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    }
    List<Map<String, Object>> completionDelivery =
        jdbc.queryForList(
            "SELECT d.state, d.last_failure_category, d.attempt_count "
                + "FROM platform_eventing.internal_event e "
                + "JOIN platform_eventing.outbox_delivery d USING (event_id) "
                + "WHERE e.event_type = 'transfer.completed.v1' "
                + "AND e.envelope -> 'payload' ->> 'referralReference' = ?",
            referralId);
    assertThat(completionDelivery)
        .as(
            "Transfer completion must reach the Referral consumer; delivery evidence: %s",
            completionDelivery)
        .singleElement()
        .satisfies(delivery -> assertThat(delivery).containsEntry("state", "DELIVERED"));
    mockMvc
        .perform(
            get("/api/v1/referrals/{referralId}", referralId)
                .header("Authorization", bearer(token(originator)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", originOrganizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RESOLVED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM referral.resolution WHERE referral_id = ?",
                String.class,
                UUID.fromString(referralId)))
        .isEqualTo("FULFILLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM referral.consumer_event_receipt WHERE event_id = "
                    + "(SELECT event_id FROM platform_eventing.internal_event WHERE "
                    + "event_type = 'transfer.completed.v1' AND envelope -> 'payload' ->> "
                    + "'referralReference' = ?)",
                Integer.class,
                referralId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_eventing.internal_event WHERE "
                    + "event_type = 'transfer.completed.v1' AND envelope::text LIKE ?",
                Integer.class,
                "%" + patientReference + "%"))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.transfer_record t JOIN transfer.journey j "
                    + "ON j.journey_id = t.journey_id WHERE t.transfer_id = ? "
                    + "AND t.transfer_status = 'COMPLETED' AND j.referral_id = ?",
                Integer.class,
                UUID.fromString(transferId),
                UUID.fromString(referralId)))
        .isEqualTo(1);

    // A second, independent Referral exercises rejection, a real competing Capacity hold,
    // cancellation reconciliation, ordered rerouting, and final completion through the same
    // owner APIs. The blocking reservation is synthetic and exists only in this disposable DB.
    String reroutePatientReference = prefix + "-reroute-opaque-patient-reference";
    String rerouteReferralId =
        createVolumeTwoReferral(
            originator,
            originOrganizationId,
            originFacilityId,
            destinationOrganizationId,
            List.of(icu, ct, neuro),
            reroutePatientReference,
            prefix + "-reroute-referral");
    mockMvc
        .perform(
            post("/api/v1/referrals/{referralId}/submission", rerouteReferralId)
                .header("Authorization", bearer(token(originator)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", originOrganizationId)
                .header("X-AyuVahak-Facility-Id", originFacilityId)
                .header("Idempotency-Key", prefix + "-reroute-submit")
                .contentType("application/json")
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isOk());

    // Refresh C's previously stale readiness evidence. Its capacity slot will be fully held by
    // another synthetic Transfer before this Referral's receiving authority accepts.
    Instant rerouteObservation = Instant.now().minusSeconds(1);
    for (String dimension : List.of(dimIcu, dimCt, dimNeuro)) {
      recordCapacityObservation(
          reporter,
          destinationOrganizationId,
          facilityC,
          dimension,
          prefix + "-reroute-c-" + dimension,
          2,
          rerouteObservation);
    }
    recordCapacityObservation(
        reporter,
        destinationOrganizationId,
        facilityC,
        reservationDimension,
        prefix + "-reroute-c-reservation",
        1,
        rerouteObservation);
    grantService(
        reservationWorker, "CapacityReservation", "FACILITY", destinationOrganizationId, facilityC);
    grantService(
        reservationWorker,
        "TransferReservationReconciliation",
        "FACILITY",
        destinationOrganizationId,
        facilityC);
    grantService(
        reservationWorker, "CapacityReservation", "FACILITY", destinationOrganizationId, facilityD);
    grantService(
        reservationWorker,
        "TransferReservationReconciliation",
        "FACILITY",
        destinationOrganizationId,
        facilityD);

    Instant competingStart = Instant.now().plus(Duration.ofMinutes(5));
    Instant competingEnd = competingStart.plus(Duration.ofHours(2));
    InternalId competingTransferId = InternalId.generate();
    var competingFacility =
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facilityC);
    var competingContext =
        executionContextFactory.forBackgroundWork(
            ActorReference.service(new IssuerId(ISSUER), reservationWorker),
            RequestedExecutionScope.facility(
                new ExternalReference(
                    new IssuerId(DIRECTORY_ISSUER), "Organization", destinationOrganizationId),
                competingFacility),
            CorrelationId.generate(),
            Optional.empty());
    var competingReservation =
        capacityReservationCommands.reserve(
            competingContext,
            new CapacityReservationRequest(
                competingTransferId,
                competingTransferId,
                competingFacility,
                reservationDimension,
                "slot",
                BigDecimal.ONE,
                competingStart,
                competingEnd,
                competingEnd),
            prefix + "-c-competing-reservation");
    assertThat(competingReservation.outcome().name()).isEqualTo("RESERVED");

    String firstRerouteRecommendation =
        mockMvc
            .perform(
                post("/api/v1/recommendations")
                    .header("Authorization", bearer(token(originator)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", originOrganizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId)
                    .header("Idempotency-Key", prefix + "-reroute-recommendation-b")
                    .contentType("application/json")
                    .content("{\"referralId\":\"" + rerouteReferralId + "\",\"revisionNumber\":1}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.outcome").value("COMPLETE"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertCandidateOutcome(firstRerouteRecommendation, facilityB, "RANKED_ELIGIBLE");
    assertCandidateOutcome(firstRerouteRecommendation, facilityC, "RANKED_ELIGIBLE");
    assertCandidateOutcome(firstRerouteRecommendation, facilityD, "RANKED_ELIGIBLE");
    String firstRerouteRecommendationId =
        com.jayway.jsonpath.JsonPath.read(firstRerouteRecommendation, "$.id");

    grantFacility(
        prefix + "-reroute-b-acceptance-authority",
        "AcceptanceAuthority",
        destinationOrganizationId,
        facilityB);
    String firstRerouteJourneyBody =
        mockMvc
            .perform(
                post("/api/v1/transfer-journeys")
                    .header("Authorization", bearer(token(originator)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", originOrganizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId)
                    .header("Idempotency-Key", prefix + "-reroute-first-request")
                    .contentType("application/json")
                    .content(
                        "{\"referralId\":\""
                            + rerouteReferralId
                            + "\",\"referralRevision\":1,\"recommendationId\":\""
                            + firstRerouteRecommendationId
                            + "\",\"candidateFacilityId\":\""
                            + facilityB
                            + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String rerouteJourneyId =
        com.jayway.jsonpath.JsonPath.read(firstRerouteJourneyBody, "$.journeyId");
    String bAttemptId =
        com.jayway.jsonpath.JsonPath.read(firstRerouteJourneyBody, "$.requestAttemptId");
    mockMvc
        .perform(
            post("/api/v1/transfer-request-attempts/{attemptId}/rejection", bAttemptId)
                .header("Authorization", bearer(token(prefix + "-reroute-b-acceptance-authority")))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", destinationOrganizationId)
                .header("X-AyuVahak-Facility-Id", facilityB)
                .header("Idempotency-Key", prefix + "-reroute-b-rejection")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":1,\"reasonCode\":\"SYNTHETIC_DESTINATION_REJECTED\","
                        + "\"occurredAt\":\""
                        + Instant.now()
                        + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("REJECTED"));

    String secondRerouteRecommendation =
        mockMvc
            .perform(
                post("/api/v1/recommendations")
                    .header("Authorization", bearer(token(originator)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", originOrganizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId)
                    .header("Idempotency-Key", prefix + "-reroute-recommendation-c")
                    .contentType("application/json")
                    .content("{\"referralId\":\"" + rerouteReferralId + "\",\"revisionNumber\":1}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String secondRerouteRecommendationId =
        com.jayway.jsonpath.JsonPath.read(secondRerouteRecommendation, "$.id");
    String cJourneyBody =
        mockMvc
            .perform(
                post("/api/v1/transfer-journeys/{journeyId}/request-attempts", rerouteJourneyId)
                    .header("Authorization", bearer(token(originator)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", originOrganizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId)
                    .header("Idempotency-Key", prefix + "-reroute-c-request")
                    .contentType("application/json")
                    .content(
                        "{\"expectedVersion\":1,\"referralRevision\":1,\"recommendationId\":\""
                            + secondRerouteRecommendationId
                            + "\",\"candidateFacilityId\":\""
                            + facilityC
                            + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.attemptNumber").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String cAttemptId = com.jayway.jsonpath.JsonPath.read(cJourneyBody, "$.requestAttemptId");
    String cClinicalActor = prefix + "-c-clinical-authority";
    String cReadinessActor = prefix + "-c-readiness-authority";
    String cAcceptanceActor = prefix + "-c-acceptance-authority";
    grantFacility(
        cClinicalActor, "ClinicalSuitabilityAuthority", destinationOrganizationId, facilityC);
    grantFacility(
        cReadinessActor, "OperationalReadinessAuthority", destinationOrganizationId, facilityC);
    grantFacility(cAcceptanceActor, "AcceptanceAuthority", destinationOrganizationId, facilityC);
    mockMvc
        .perform(
            post(
                    "/api/v1/transfer-request-attempts/{attemptId}/clinical-suitability-assessments",
                    cAttemptId)
                .header("Authorization", bearer(token(cClinicalActor)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", destinationOrganizationId)
                .header("X-AyuVahak-Facility-Id", facilityC)
                .header("Idempotency-Key", prefix + "-c-clinical")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":1,\"outcome\":\"SUPPORTS\","
                        + "\"reasonCode\":\"SYNTHETIC_REVIEW_COMPLETE\",\"occurredAt\":\""
                        + Instant.now()
                        + "\"}"))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            post(
                    "/api/v1/transfer-request-attempts/{attemptId}/operational-readiness-assessments",
                    cAttemptId)
                .header("Authorization", bearer(token(cReadinessActor)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", destinationOrganizationId)
                .header("X-AyuVahak-Facility-Id", facilityC)
                .header("Idempotency-Key", prefix + "-c-readiness")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":2,\"outcome\":\"SUPPORTS\","
                        + "\"reasonCode\":\"SYNTHETIC_READINESS_CONFIRMED\",\"occurredAt\":\""
                        + Instant.now()
                        + "\"}"))
        .andExpect(status().isCreated());
    String cAcceptance =
        mockMvc
            .perform(
                post("/api/v1/transfer-request-attempts/{attemptId}/acceptance", cAttemptId)
                    .header("Authorization", bearer(token(cAcceptanceActor)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", destinationOrganizationId)
                    .header("X-AyuVahak-Facility-Id", facilityC)
                    .header("Idempotency-Key", prefix + "-c-acceptance")
                    .contentType("application/json")
                    .content("{\"expectedVersion\":3,\"occurredAt\":\"" + Instant.now() + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("AWAITING_RESERVATION"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String cTransferId = com.jayway.jsonpath.JsonPath.read(cAcceptance, "$.transferId");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM transfer.capacity_reservation_request WHERE transfer_id = ?",
                String.class,
                UUID.fromString(cTransferId)))
        .isEqualTo("CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                UUID.fromString(cTransferId)))
        .isEqualTo("AWAITING_RESERVATION");

    String cProgressActor = prefix + "-c-progress-authority";
    grantFacility(
        cProgressActor, "TransferProgressAuthority", destinationOrganizationId, facilityC);
    long cTransferVersion =
        jdbc.queryForObject(
            "SELECT version FROM transfer.transfer_record WHERE transfer_id = ?",
            Long.class,
            UUID.fromString(cTransferId));
    mockMvc
        .perform(
            post("/api/v1/transfers/{transferId}/cancellation", cTransferId)
                .header("Authorization", bearer(token(cProgressActor)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", destinationOrganizationId)
                .header("X-AyuVahak-Facility-Id", facilityC)
                .header("Idempotency-Key", prefix + "-c-cancel-unreserved")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":"
                        + cTransferVersion
                        + ",\"occurredAt\":\""
                        + Instant.now()
                        + "\",\"reasonCode\":\"RESERVATION_CONFLICT_REROUTE\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation WHERE transfer_id = ?",
                String.class,
                competingTransferId.value()))
        .isEqualTo("ACTIVE");

    long journeyVersion =
        jdbc.queryForObject(
            "SELECT version FROM transfer.journey WHERE journey_id = ?",
            Long.class,
            UUID.fromString(rerouteJourneyId));
    String thirdRerouteRecommendation =
        mockMvc
            .perform(
                post("/api/v1/recommendations")
                    .header("Authorization", bearer(token(originator)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", originOrganizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId)
                    .header("Idempotency-Key", prefix + "-reroute-recommendation-d")
                    .contentType("application/json")
                    .content("{\"referralId\":\"" + rerouteReferralId + "\",\"revisionNumber\":1}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String thirdRerouteRecommendationId =
        com.jayway.jsonpath.JsonPath.read(thirdRerouteRecommendation, "$.id");
    String dJourneyBody =
        mockMvc
            .perform(
                post("/api/v1/transfer-journeys/{journeyId}/request-attempts", rerouteJourneyId)
                    .header("Authorization", bearer(token(originator)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", originOrganizationId)
                    .header("X-AyuVahak-Facility-Id", originFacilityId)
                    .header("Idempotency-Key", prefix + "-reroute-d-request")
                    .contentType("application/json")
                    .content(
                        "{\"expectedVersion\":"
                            + journeyVersion
                            + ",\"referralRevision\":1,\"recommendationId\":\""
                            + thirdRerouteRecommendationId
                            + "\",\"candidateFacilityId\":\""
                            + facilityD
                            + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.attemptNumber").value(3))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String dAttemptId = com.jayway.jsonpath.JsonPath.read(dJourneyBody, "$.requestAttemptId");
    String dClinicalActor = prefix + "-d-clinical-authority";
    String dReadinessActor = prefix + "-d-readiness-authority";
    String dAcceptanceActor = prefix + "-d-acceptance-authority";
    grantFacility(
        dClinicalActor, "ClinicalSuitabilityAuthority", destinationOrganizationId, facilityD);
    grantFacility(
        dReadinessActor, "OperationalReadinessAuthority", destinationOrganizationId, facilityD);
    grantFacility(dAcceptanceActor, "AcceptanceAuthority", destinationOrganizationId, facilityD);
    mockMvc
        .perform(
            post(
                    "/api/v1/transfer-request-attempts/{attemptId}/clinical-suitability-assessments",
                    dAttemptId)
                .header("Authorization", bearer(token(dClinicalActor)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", destinationOrganizationId)
                .header("X-AyuVahak-Facility-Id", facilityD)
                .header("Idempotency-Key", prefix + "-d-clinical")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":1,\"outcome\":\"SUPPORTS\","
                        + "\"reasonCode\":\"SYNTHETIC_REVIEW_COMPLETE\",\"occurredAt\":\""
                        + Instant.now()
                        + "\"}"))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            post(
                    "/api/v1/transfer-request-attempts/{attemptId}/operational-readiness-assessments",
                    dAttemptId)
                .header("Authorization", bearer(token(dReadinessActor)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", destinationOrganizationId)
                .header("X-AyuVahak-Facility-Id", facilityD)
                .header("Idempotency-Key", prefix + "-d-readiness")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":2,\"outcome\":\"SUPPORTS\","
                        + "\"reasonCode\":\"SYNTHETIC_READINESS_CONFIRMED\",\"occurredAt\":\""
                        + Instant.now()
                        + "\"}"))
        .andExpect(status().isCreated());
    String dAcceptance =
        mockMvc
            .perform(
                post("/api/v1/transfer-request-attempts/{attemptId}/acceptance", dAttemptId)
                    .header("Authorization", bearer(token(dAcceptanceActor)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", destinationOrganizationId)
                    .header("X-AyuVahak-Facility-Id", facilityD)
                    .header("Idempotency-Key", prefix + "-d-acceptance")
                    .contentType("application/json")
                    .content("{\"expectedVersion\":3,\"occurredAt\":\"" + Instant.now() + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("READY"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String dTransferId = com.jayway.jsonpath.JsonPath.read(dAcceptance, "$.transferId");
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM capacity.coordination_reservation WHERE transfer_id = ?",
                String.class,
                UUID.fromString(dTransferId)))
        .isEqualTo("ACTIVE");

    String dProgressActor = prefix + "-d-progress-authority";
    String dReceiver = prefix + "-d-receiver-authority";
    grantFacility(
        dProgressActor, "TransferProgressAuthority", destinationOrganizationId, facilityD);
    grantFacility(dReceiver, "TransferProgressAuthority", destinationOrganizationId, facilityD);
    grantFacility(dReceiver, "TransferHandoverAuthority", destinationOrganizationId, facilityD);
    grantFacility(dReceiver, "TransferCompletionAuthority", destinationOrganizationId, facilityD);
    recordTransferProgress(
        dTransferId,
        "departure",
        dProgressActor,
        destinationOrganizationId,
        facilityD,
        prefix + "-d-departure",
        2,
        "\"sourceReference\":\"synthetic-reroute-departure\"");
    recordTransferProgress(
        dTransferId,
        "arrival",
        dProgressActor,
        destinationOrganizationId,
        facilityD,
        prefix + "-d-arrival",
        3,
        "");
    recordTransferProgress(
        dTransferId,
        "handover",
        dReceiver,
        destinationOrganizationId,
        facilityD,
        prefix + "-d-handover",
        4,
        "");
    assertThat(
            recordTransferProgress(
                    dTransferId,
                    "completion",
                    dReceiver,
                    destinationOrganizationId,
                    facilityD,
                    prefix + "-d-completion",
                    5,
                    "\"reasonCode\":\"SYNTHETIC_REROUTED_COORDINATION_COMPLETE\"")
                .contains("\"status\":\"COMPLETED\""))
        .isTrue();
    for (int attempt = 0; attempt < 5; attempt++) {
      outboxDispatcher.dispatchAvailable(Instant.now().plusSeconds(1));
    }
    assertThat(
            jdbc.queryForList(
                "SELECT attempt_number, request_status FROM transfer.request_attempt "
                    + "WHERE journey_id = ? ORDER BY attempt_number",
                UUID.fromString(rerouteJourneyId)))
        .extracting(row -> row.get("request_status"))
        .containsExactly("REJECTED", "ACCEPTED", "ACCEPTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                UUID.fromString(cTransferId)))
        .isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                UUID.fromString(dTransferId)))
        .isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM referral.resolution WHERE referral_id = ?",
                String.class,
                UUID.fromString(rerouteReferralId)))
        .isEqualTo("FULFILLED");
    assertThat(secondRerouteRecommendation).doesNotContain(reroutePatientReference);
    assertThat(thirdRerouteRecommendation).doesNotContain(reroutePatientReference);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_eventing.internal_event WHERE "
                    + "event_type = 'transfer.completed.v1' AND envelope::text LIKE ?",
                Integer.class,
                "%" + reroutePatientReference + "%"))
        .isZero();
  }

  private void configureDirectoryAndIpsPartner(String administrator, String prefix)
      throws Exception {
    String port = Integer.toString(partnerServer.getAddress().getPort());
    String configuration =
        "{\"organization\":{\"issuer\":\"urn:synthetic:volume-three-partner\","
            + "\"identifier\":\"SYN-PARTNER-V3-001\"},"
            + "\"endpointOrigin\":\"https://partner.test:"
            + port
            + "\",\"basePath\":\"/fhir\","
            + "\"authentication\":{\"method\":\"NONE\"},\"tlsPolicy\":\"SYSTEM_CA\","
            + "\"limits\":{\"connectTimeoutMillis\":1000,\"responseTimeoutMillis\":2000,"
            + "\"operationTimeoutMillis\":5000,\"maxResponseBytes\":1000000,"
            + "\"maxDecompressedBytes\":2000000,\"maxPages\":10,\"maxResources\":500,"
            + "\"maxNestingDepth\":32,\"maxAttempts\":3},"
            + "\"retryClass\":\"IDEMPOTENT_READ\",\"allowedOperations\":["
            + "{\"ownerContext\":\"DIRECTORY\",\"operationCode\":\"mcsd.iti90.search\","
            + "\"direction\":\"OUTBOUND\",\"protocol\":\"FHIR_R4_JSON\",\"profile\":\"IHE-mCSD\"},"
            + "{\"ownerContext\":\"REFERRAL\",\"operationCode\":\"ips.patient-context.read\","
            + "\"direction\":\"OUTBOUND\",\"protocol\":\"FHIR_R4_JSON\",\"profile\":\"IPS-2.0.1\"}]}";
    String create =
        "{\"partnerKey\":\"synthetic-directory-feed\","
            + configuration.substring(1, configuration.length() - 1)
            + ",\"reasonCode\":\"PARTNER_SETUP\"}";
    mockMvc
        .perform(
            post("/api/v1/platform/exchange-partners")
                .header("Authorization", bearer(token(administrator)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", prefix + "-directory-partner-create")
                .contentType("application/json")
                .content(create))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            post("/api/v1/platform/exchange-partners/synthetic-directory-feed/versions/1/activate")
                .header("Authorization", bearer(token(administrator)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", prefix + "-directory-partner-activate")
                .contentType("application/json")
                .content("{\"reasonCode\":\"PARTNER_ACTIVATION\"}"))
        .andExpect(status().isOk());
  }

  private String importMcsdDestinationOrganization(String serviceClientId, String prefix) {
    String sourceId = "SYN-ORG-MCSD-" + prefix.substring(prefix.length() - 8);
    PARTNER_RESPONSE.set(mcsdOrganizationSearchBundle(sourceId));
    ExecutionContext context =
        executionContextFactory.forIntegrationRequest(
            ActorReference.service(new IssuerId(ISSUER), serviceClientId),
            RequestedExecutionScope.platform(),
            new CorrelationId(prefix + "-mcsd-discovery"));
    var result = directoryMcsdSynchronization.discover(context, new IssuerId(MCSD_NAMESPACE), 1);
    assertThat(result.status())
        .isEqualTo(
            org.ayuvahak.directory.application.DirectoryMcsdSynchronizationResult.Status.COMPLETE);
    assertThat(result.factsImported()).isEqualTo(1);
    DirectoryExternalIdentifier identifier =
        directoryStore
            .findActiveExternalIdentifier(
                new ExternalReference(new IssuerId(MCSD_NAMESPACE), "Organization", sourceId))
            .orElseThrow();
    assertThat(identifier.conceptType())
        .isEqualTo(org.ayuvahak.directory.domain.DirectoryConceptType.ORGANIZATION);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'DIRECTORY_MCSD_SOURCE_IMPORT' AND target_id = ? AND outcome = 'SUCCEEDED'",
                Integer.class,
                identifier.conceptId().value()))
        .isEqualTo(1);
    return identifier.conceptId().toString();
  }

  private static byte[] mcsdOrganizationSearchBundle(String sourceId) {
    String updatedAt = Instant.now().toString();
    String json =
        "{\"resourceType\":\"Bundle\",\"type\":\"searchset\","
            + "\"timestamp\":\""
            + updatedAt
            + "\",\"entry\":[{\"fullUrl\":\"https://partner.test/fhir/Organization/"
            + sourceId
            + "\",\"resource\":{\"resourceType\":\"Organization\",\"id\":\""
            + sourceId
            + "\",\"meta\":{\"profile\":[\""
            + MCSD_PROFILE
            + "\"],\"versionId\":\"1\",\"lastUpdated\":\""
            + updatedAt
            + "\"},\"active\":true,\"type\":[{\"coding\":[{"
            + "\"system\":\"http://terminology.hl7.org/CodeSystem/organization-type\","
            + "\"code\":\"dept\"}]}],\"name\":\"Synthetic mCSD Destination Authority\"}}]}";
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] syntheticIpsDocument(String patientIdentifier) {
    String patientUrl = "urn:uuid:00000000-0000-4000-8000-000000000002";
    String compositionUrl = "urn:uuid:00000000-0000-4000-8000-000000000001";
    String conditionUrl = "urn:uuid:00000000-0000-4000-8000-000000000003";
    Instant timestamp = Instant.parse("2026-10-15T08:00:00Z");

    var bundle = new org.hl7.fhir.r4.model.Bundle();
    bundle.getMeta().addProfile(IPS_BUNDLE_PROFILE);
    bundle.setIdentifier(
        new org.hl7.fhir.r4.model.Identifier()
            .setSystem("urn:synthetic:ips-documents")
            .setValue("SYN-IPS-001"));
    bundle.setType(org.hl7.fhir.r4.model.Bundle.BundleType.DOCUMENT);
    bundle.setTimestamp(Date.from(timestamp));

    var patient = new org.hl7.fhir.r4.model.Patient();
    patient.setId("00000000-0000-4000-8000-000000000002");
    patient.getMeta().addProfile(IPS_PATIENT_PROFILE);
    patient.addIdentifier(
        new org.hl7.fhir.r4.model.Identifier()
            .setSystem("urn:synthetic:source-ehr")
            .setValue(patientIdentifier));
    patient.addName().setText("SYNTHETIC PERSON NOT REAL");
    patient.setBirthDate(Date.from(Instant.parse("2000-01-01T00:00:00Z")));

    var condition = new org.hl7.fhir.r4.model.Condition();
    condition.setId("00000000-0000-4000-8000-000000000003");
    condition.setSubject(new org.hl7.fhir.r4.model.Reference(patientUrl));
    condition.setCode(
        new org.hl7.fhir.r4.model.CodeableConcept()
            .addCoding(
                new org.hl7.fhir.r4.model.Coding(
                    "urn:synthetic:clinical", "SYN-CODED-PROBLEM-001", "Synthetic problem")));

    var composition = new org.hl7.fhir.r4.model.Composition();
    composition.getMeta().addProfile(IPS_COMPOSITION_PROFILE);
    composition.setStatus(org.hl7.fhir.r4.model.Composition.CompositionStatus.FINAL);
    composition.setType(
        new org.hl7.fhir.r4.model.CodeableConcept()
            .addCoding(
                new org.hl7.fhir.r4.model.Coding(
                    "http://loinc.org", "60591-5", "Patient summary")));
    composition.setSubject(new org.hl7.fhir.r4.model.Reference(patientUrl));
    composition.setDate(Date.from(timestamp));
    composition
        .addEvent()
        .addCode(
            new org.hl7.fhir.r4.model.CodeableConcept()
                .addCoding(
                    new org.hl7.fhir.r4.model.Coding(
                        "http://terminology.hl7.org/CodeSystem/v3-ActClass",
                        "PCPR",
                        "care provision")));
    composition.addAuthor().setDisplay("Synthetic IPS author");
    composition.setTitle("Synthetic patient summary");
    var narrative = new org.hl7.fhir.r4.model.Narrative();
    narrative.setStatus(org.hl7.fhir.r4.model.Narrative.NarrativeStatus.GENERATED);
    narrative.setDivAsString(
        "<div xmlns=\"http://www.w3.org/1999/xhtml\">SYN-PRIVATE-NARRATIVE-CONTENT</div>");
    composition
        .addSection()
        .setTitle("Problems")
        .setCode(
            new org.hl7.fhir.r4.model.CodeableConcept()
                .addCoding(
                    new org.hl7.fhir.r4.model.Coding(
                        "http://loinc.org", "11450-4", "Problem list")))
        .setText(narrative)
        .addEntry(new org.hl7.fhir.r4.model.Reference(conditionUrl));
    composition
        .addSection()
        .setTitle("Allergies and intolerances")
        .setCode(
            new org.hl7.fhir.r4.model.CodeableConcept()
                .addCoding(
                    new org.hl7.fhir.r4.model.Coding("http://loinc.org", "48765-2", "Allergies")))
        .setText(syntheticNarrative("No synthetic allergy records."))
        .setEmptyReason(
            new org.hl7.fhir.r4.model.CodeableConcept()
                .addCoding(
                    new org.hl7.fhir.r4.model.Coding(
                        "http://terminology.hl7.org/CodeSystem/list-empty-reason",
                        "unavailable",
                        "Unavailable")));
    composition
        .addSection()
        .setTitle("Medication summary")
        .setCode(
            new org.hl7.fhir.r4.model.CodeableConcept()
                .addCoding(
                    new org.hl7.fhir.r4.model.Coding(
                        "http://loinc.org", "10160-0", "Medication summary")))
        .setText(syntheticNarrative("No synthetic medication records."))
        .setEmptyReason(
            new org.hl7.fhir.r4.model.CodeableConcept()
                .addCoding(
                    new org.hl7.fhir.r4.model.Coding(
                        "http://terminology.hl7.org/CodeSystem/list-empty-reason",
                        "unavailable",
                        "Unavailable")));

    bundle.addEntry().setFullUrl(compositionUrl).setResource(composition);
    bundle.addEntry().setFullUrl(patientUrl).setResource(patient);
    bundle.addEntry().setFullUrl(conditionUrl).setResource(condition);
    return ca.uhn.fhir.context.FhirContext.forR4Cached()
        .newJsonParser()
        .encodeResourceToString(bundle)
        .getBytes(StandardCharsets.UTF_8);
  }

  private static org.hl7.fhir.r4.model.Narrative syntheticNarrative(String content) {
    var narrative = new org.hl7.fhir.r4.model.Narrative();
    narrative.setStatus(org.hl7.fhir.r4.model.Narrative.NarrativeStatus.GENERATED);
    narrative.setDivAsString("<div xmlns=\"http://www.w3.org/1999/xhtml\">" + content + "</div>");
    return narrative;
  }

  private String createCareCapability(String code, String key, String actor) throws Exception {
    MvcResult response =
        mockMvc
            .perform(
                post("/api/v1/directory/capabilities")
                    .header("Authorization", bearer(token(actor)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(
                        "{\"code\":\""
                            + code
                            + "\",\"displayName\":\"Synthetic "
                            + code
                            + "\",\"parentCapabilityId\":null,\"source\":{"
                            + "\"issuer\":\"urn:synthetic:directory\","
                            + "\"resourceType\":\"CareCapability\",\"reference\":\""
                            + key
                            + "\"},\"provenance\":{\"method\":\"MANUAL_REVIEW\","
                            + "\"observedAt\":\""
                            + Instant.now()
                            + "\",\"reasonCode\":\"SYNTHETIC_SETUP\"}}"))
            .andExpect(status().isCreated())
            .andReturn();
    return com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.id");
  }

  private String createCapabilityAssertion(
      String facilityId, String capabilityCode, String key, String actor) throws Exception {
    MvcResult response =
        mockMvc
            .perform(
                post("/api/v1/directory/facilities/{facilityId}/capability-assertions", facilityId)
                    .header("Authorization", bearer(token(actor)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(
                        "{\"capabilityCode\":\""
                            + capabilityCode
                            + "\",\"healthcareServiceId\":null,"
                            + "\"verificationStatus\":\"VERIFIED\",\"source\":{"
                            + "\"issuer\":\"urn:synthetic:directory\","
                            + "\"resourceType\":\"FacilityCapabilityAssertion\","
                            + "\"reference\":\""
                            + key
                            + "\"},\"provenance\":{\"method\":\"MANUAL_REVIEW\","
                            + "\"observedAt\":\""
                            + Instant.now()
                            + "\",\"reasonCode\":\"SYNTHETIC_SETUP\"},"
                            + "\"effectiveFrom\":\"2026-01-01T00:00:00Z\","
                            + "\"effectiveUntil\":null}"))
            .andExpect(status().isCreated())
            .andReturn();
    return com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.id");
  }

  private void defineCapacityDimension(
      String dimension, String key, String actor, boolean reservable, String unit)
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/capacity/policies/dimensions")
                .header("Authorization", bearer(token(actor)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(
                    "{\"code\":\""
                        + dimension
                        + "\",\"expectedVersion\":0,\"displayName\":\"Synthetic "
                        + dimension
                        + "\",\"meaning\":\"Synthetic coordination evidence\","
                        + "\"valueKind\":\"QUANTITY\",\"unit\":\""
                        + unit
                        + "\",\"minimumValue\":0,\"maximumValue\":500,"
                        + "\"allowedStates\":[],\"usedForReadiness\":true,"
                        + "\"reservable\":"
                        + reservable
                        + ",\"reasonCode\":\"SYNTHETIC_SETUP\"}"))
        .andExpect(status().isCreated());
  }

  private void defineReservableCapacityDimension(String dimension) throws Exception {
    defineCapacityDimension(
        dimension,
        "synthetic-federated-dimension-" + UUID.randomUUID(),
        CAPACITY_ADMIN,
        true,
        "slot");
  }

  private void configureReservableCapacityPolicy(String dimension) throws Exception {
    configureCapacityPolicy(
        dimension, "synthetic-federated-policy-" + UUID.randomUUID(), CAPACITY_ADMIN, 600);
  }

  private void recordReservableCapacityObservation(
      String organizationId, String facilityId, String dimension, int quantity) throws Exception {
    recordCapacityObservation(
        CAPACITY_REPORTER,
        organizationId,
        facilityId,
        dimension,
        "synthetic-federated-observation-" + UUID.randomUUID(),
        quantity,
        Instant.now().minusSeconds(1));
  }

  private void configureCapacityPolicy(String dimension, String key, String actor, int maximumAge)
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/capacity/policies/dimensions/{dimension}/policy", dimension)
                .header("Authorization", bearer(token(actor)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":0,\"dimensionVersion\":1,\"sources\":["
                        + "{\"sourceCode\":\"manual\",\"priority\":0,"
                        + "\"fallbackWhenHigherPriorityAbsent\":false,"
                        + "\"fallbackWhenHigherPriorityStale\":false}],"
                        + "\"maximumObservationAgeSeconds\":"
                        + maximumAge
                        + ",\"defaultValiditySeconds\":300,"
                        + "\"futureSkewToleranceSeconds\":30,"
                        + "\"minimumPassingQuantity\":1,\"passingStates\":[],"
                        + "\"reasonCode\":\"SYNTHETIC_SETUP\"}"))
        .andExpect(status().isCreated());
  }

  private void recordCapacityObservation(
      String actor,
      String organizationId,
      String facilityId,
      String dimension,
      String key,
      int quantity,
      Instant observedAt)
      throws Exception {
    recordCapacityObservation(
        actor, organizationId, facilityId, dimension, key, quantity, observedAt, null);
  }

  private void recordCapacityObservation(
      String actor,
      String organizationId,
      String facilityId,
      String dimension,
      String key,
      int quantity,
      Instant observedAt,
      Instant validUntil)
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/capacity/observations")
                .header("Authorization", bearer(token(actor)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(
                    "{\"dimensionCode\":\""
                        + dimension
                        + "\",\"sourceCode\":\"manual\","
                        + "\"sourceIssuer\":\"urn:synthetic:capacity\","
                        + "\"sourceObservationId\":\""
                        + key
                        + "\",\"observationKind\":\"VALUE\","
                        + "\"valueKind\":\"QUANTITY\",\"quantity\":"
                        + quantity
                        + ",\"state\":null,\"unit\":\""
                        + (dimension.endsWith("_SLOT") ? "slot" : "unit")
                        + "\",\"observedAt\":\""
                        + observedAt
                        + "\",\"validUntil\":"
                        + (validUntil == null ? "null" : "\"" + validUntil + "\"")
                        + ",\"reportingMethod\":\"MANUAL_ENTRY\","
                        + "\"reasonCode\":\"CAPACITY_REPORT\"}"))
        .andExpect(status().isCreated());
  }

  private String createVolumeTwoReferral(
      String actor,
      String organizationId,
      String facilityId,
      String destinationOrganizationId,
      List<String> requiredCapabilities,
      String patientReference,
      String key)
      throws Exception {
    Instant requestedStart = Instant.now().plus(Duration.ofHours(1));
    Instant requestedEnd = requestedStart.plus(Duration.ofHours(2));
    String capabilities =
        requiredCapabilities.stream()
            .map(code -> "\"" + code + "\"")
            .collect(java.util.stream.Collectors.joining(","));
    MvcResult response =
        mockMvc
            .perform(
                post("/api/v1/referrals")
                    .header("Authorization", bearer(token(actor)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", facilityId)
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(
                        "{\"patientReference\":{\"issuer\":\"urn:synthetic:source-ehr\","
                            + "\"resourceType\":\"Patient\",\"identifier\":\""
                            + patientReference
                            + "\",\"encounterReference\":null},\"careRequirement\":{"
                            + "\"requiredCareCapabilityCodes\":["
                            + capabilities
                            + "],\"requestedStart\":\""
                            + requestedStart
                            + "\",\"requestedEnd\":\""
                            + requestedEnd
                            + "\",\"permittedGeographyCodes\":[],"
                            + "\"selectedDestinationOrganizationId\":\""
                            + destinationOrganizationId
                            + "\",\"priorityCode\":\"EMERGENCY\"}}"))
            .andExpect(status().isCreated())
            .andReturn();
    return com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.id");
  }

  private static MatchingRecommendationPolicy volumeTwoPolicy(
      String organizationId, Map<String, Set<String>> mappings) {
    IssuerId issuer = new IssuerId("urn:ayuvahak:matching");
    String code = "SYNTHETIC_VOLUME_TWO_E2E";
    CapabilityOperationalMapping mapping = new CapabilityOperationalMapping(code, 1, mappings);
    ExternalReference organization =
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId);
    return new MatchingRecommendationPolicy(
        new PolicyVersion(new ExternalReference(issuer, "MatchingPolicy", code), 1),
        new PolicyVersion(
            new ExternalReference(issuer, "CapabilityOperationalRequirementPolicy", code), 1),
        mapping,
        Set.of(organization),
        new Policy(MetricKind.STRAIGHT_LINE_DISTANCE, true, Map.of()),
        Duration.ofHours(1),
        100,
        true);
  }

  private static void assertCandidateOutcome(String body, String facilityId, String expected) {
    List<String> outcomes =
        com.jayway.jsonpath.JsonPath.read(
            body, "$.candidates[?(@.facility.identifier == '" + facilityId + "')].status");
    assertThat(outcomes).containsExactly(expected);
  }

  private static int readCandidateRank(String body, String facilityId) {
    List<Integer> ranks =
        com.jayway.jsonpath.JsonPath.read(
            body, "$.candidates[?(@.facility.identifier == '" + facilityId + "')].rank");
    assertThat(ranks).hasSize(1);
    return ranks.getFirst();
  }

  private static void assertCandidateReason(String body, String facilityId, String expectedReason) {
    List<?> reasons = (List<?>) candidateRepresentation(body, facilityId).get("reasons");
    List<String> codes =
        reasons.stream().map(reason -> (String) ((Map<?, ?>) reason).get("code")).toList();
    assertThat(codes).contains(expectedReason);
  }

  private static List<String> readCapacityOutcomeCodes(String body, String facilityId) {
    List<?> evidence = (List<?>) candidateRepresentation(body, facilityId).get("evidence");
    return evidence.stream()
        .map(Map.class::cast)
        .filter(fact -> "CAPACITY_READINESS".equals(fact.get("factCode")))
        .map(fact -> (String) fact.get("outcomeCode"))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> candidateRepresentation(String body, String facilityId) {
    List<?> candidates = com.jayway.jsonpath.JsonPath.read(body, "$.candidates");
    for (Object value : candidates) {
      if (value instanceof Map<?, ?> candidate
          && candidate.get("facility") instanceof Map<?, ?> facility
          && facilityId.equals(facility.get("identifier"))) {
        return (Map<String, Object>) candidate;
      }
    }
    throw new AssertionError("Recommendation omitted candidate Facility " + facilityId);
  }

  private String recordTransferProgress(
      String transferId,
      String command,
      String actor,
      String organizationId,
      String facilityId,
      String key,
      long expectedVersion,
      String extraFields)
      throws Exception {
    String additions = extraFields == null || extraFields.isBlank() ? "" : "," + extraFields;
    MvcResult response =
        mockMvc
            .perform(
                post("/api/v1/transfers/{transferId}/{command}", transferId, command)
                    .header("Authorization", bearer(token(actor)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", facilityId)
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(
                        "{\"expectedVersion\":"
                            + expectedVersion
                            + ",\"occurredAt\":\""
                            + Instant.now()
                            + "\""
                            + additions
                            + "}"))
            .andExpect(status().isCreated())
            .andReturn();
    return response.getResponse().getContentAsString();
  }

  private void recordIntegratedTransportStatus(
      String transportRequestId,
      String actor,
      String organizationId,
      String facilityId,
      String idempotencyKey,
      String observationId,
      String transportStatus,
      Instant occurredAt)
      throws Exception {
    mockMvc
        .perform(
            post(
                    "/api/v1/transport-requests/{transportRequestId}/status-observations",
                    transportRequestId)
                .header("Authorization", bearer(token(actor)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content(
                    "{\"observationId\":\""
                        + observationId
                        + "\",\"sourceNamespace\":\"urn:ayuvahak:synthetic-transport\","
                        + "\"sourceSequence\":"
                        + Integer.parseInt(observationId.substring(observationId.length() - 3))
                        + ",\"status\":\""
                        + transportStatus
                        + "\",\"occurredAt\":\""
                        + occurredAt
                        + "\"}"))
        .andExpect(status().isCreated());
  }

  private static MatchingRecommendationPolicy activePolicy(String organizationId) {
    return activePolicy(organizationId, 1);
  }

  private static MatchingRecommendationPolicy activePolicy(String organizationId, long version) {
    return activePolicy(organizationId, version, Duration.ofHours(1));
  }

  private static MatchingRecommendationPolicy activePolicy(
      String organizationId, long version, Duration maximumAge) {
    return activePolicy(
        organizationId, version, maximumAge, MetricKind.STRAIGHT_LINE_DISTANCE, true);
  }

  private static MatchingRecommendationPolicy activePolicy(
      String organizationId,
      long version,
      Duration maximumAge,
      MetricKind primaryMetric,
      boolean allowDistanceFallback) {
    return activePolicy(
        organizationId, version, maximumAge, primaryMetric, allowDistanceFallback, 100);
  }

  private static MatchingRecommendationPolicy activePolicy(
      String organizationId,
      long version,
      Duration maximumAge,
      MetricKind primaryMetric,
      boolean allowDistanceFallback,
      int directoryPageSize) {
    IssuerId matchingIssuer = new IssuerId("urn:ayuvahak:matching");
    ExternalReference candidateOrganization =
        new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Organization", organizationId);
    String policyCode = "SYNTHETIC_CROSS_PHASE";
    CapabilityOperationalMapping mapping =
        new CapabilityOperationalMapping(
            policyCode, version, Map.of(CAPABILITY, Set.of(DIMENSION)));
    return new MatchingRecommendationPolicy(
        new PolicyVersion(
            new ExternalReference(matchingIssuer, "MatchingPolicy", policyCode), version),
        new PolicyVersion(
            new ExternalReference(
                matchingIssuer, "CapabilityOperationalRequirementPolicy", policyCode),
            version),
        mapping,
        Set.of(candidateOrganization),
        new Policy(primaryMetric, allowDistanceFallback, Map.of()),
        maximumAge,
        directoryPageSize,
        true);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class MatchingTestConfiguration {

    @Bean
    FederatedReceivingCapacityFailureProbe federatedReceivingCapacityFailureProbe() {
      return new FederatedReceivingCapacityFailureProbe();
    }

    @Bean
    @Primary
    org.ayuvahak.transfer.application.FederatedReceivingCapacityPort
        federatedReceivingCapacityFailurePort(
            ObjectProvider<CapacityReservationCommands> commands,
            FederatedReceivingCapacityFailureProbe probe) {
      return new org.ayuvahak.transfer.application.FederatedReceivingCapacityPort() {
        @Override
        public CapacityReservationResult reserve(
            ExecutionContext context,
            FederatedReceivingCapacityReservationRequest request,
            String idempotencyKey) {
          CapacityReservationResult result =
              commands.getObject().reserveFederatedReceiving(context, request, idempotencyKey);
          probe.recordReservation(request.operationId());
          if (probe.consumeLostResponse()) {
            throw new IllegalStateException("synthetic lost response after local Capacity commit");
          }
          return result;
        }

        @Override
        public FederatedReceivingCapacityReservationOutcome currentOutcome(
            ExecutionContext context, InternalId operationId, InternalId receivingRequestId) {
          probe.recordOutcomeLookup(operationId);
          return commands
              .getObject()
              .getFederatedReceivingReservationOutcome(context, operationId, receivingRequestId);
        }
      };
    }

    @Bean
    DirectoryIsolationProbe directoryIsolationProbe() {
      return new DirectoryIsolationProbe();
    }

    @Bean
    @Primary
    DirectoryStore directoryStoreIsolationProbe(
        @Qualifier("jdbcDirectoryStore") DirectoryStore delegate,
        JdbcTemplate jdbc,
        HikariDataSource dataSource,
        DirectoryIsolationProbe probe) {
      return (DirectoryStore)
          Proxy.newProxyInstance(
              DirectoryStore.class.getClassLoader(),
              new Class<?>[] {DirectoryStore.class},
              (ignored, method, arguments) -> {
                if (method.getName().equals("findMatchingCandidateFacilities")) {
                  probe.record(
                      jdbc.queryForObject(
                          "SELECT current_setting('transaction_isolation')", String.class),
                      jdbc.queryForObject("SELECT txid_current()", Long.class),
                      dataSource.getHikariPoolMXBean().getActiveConnections());
                }
                try {
                  return method.invoke(delegate, arguments);
                } catch (InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
    }

    @Bean
    @Primary
    AuditRecordAppender matchingAuditConnectionProbe(
        @Qualifier("jdbcAuditRecordAppender") AuditRecordAppender delegate,
        HikariDataSource dataSource,
        DirectoryIsolationProbe probe) {
      return new AuditRecordAppender() {
        @Override
        public void appendInOwnerTransaction(
            InternalId id,
            org.ayuvahak.platform.audit.domain.AuditEvidence evidence,
            Instant recordedAt) {
          delegate.appendInOwnerTransaction(id, evidence, recordedAt);
        }

        @Override
        public void appendDurably(
            InternalId id,
            org.ayuvahak.platform.audit.domain.AuditEvidence evidence,
            Instant recordedAt) {
          if (evidence.actionCode().equals("DIRECTORY_MATCHING_CANDIDATE_SEARCH")
              || evidence.actionCode().equals("DIRECTORY_MATCHING_EVALUATION_READ")) {
            probe.recordAuditConnectionCount(
                dataSource.getHikariPoolMXBean().getActiveConnections());
          }
          delegate.appendDurably(id, evidence, recordedAt);
        }
      };
    }

    @Bean
    CapacityIsolationProbe capacityIsolationProbe() {
      return new CapacityIsolationProbe();
    }

    @Bean
    @Primary
    CapacityEvidenceStore capacityEvidenceIsolationProbe(
        @Qualifier("jdbcCapacityEvidenceStore") CapacityEvidenceStore delegate,
        JdbcTemplate jdbc,
        CapacityIsolationProbe probe) {
      return new CapacityEvidenceStore() {
        @Override
        public long currentSourceSequence(ExternalReference facility, String dimensionCode) {
          return delegate.currentSourceSequence(facility, dimensionCode);
        }

        @Override
        public List<CapacityObservationFact> findForProjection(
            ExternalReference facility, String dimensionCode) {
          probe.record(
              jdbc.queryForObject("SELECT current_setting('transaction_isolation')", String.class));
          return delegate.findForProjection(facility, dimensionCode);
        }

        @Override
        public List<CapacityObservationFact> findHistory(
            ExternalReference facility,
            java.util.Optional<String> dimensionCode,
            java.util.Optional<String> sourceCode,
            java.util.Optional<Instant> observedFromInclusive,
            java.util.Optional<Instant> observedUntilExclusive,
            int limit,
            long offset) {
          return delegate.findHistory(
              facility,
              dimensionCode,
              sourceCode,
              observedFromInclusive,
              observedUntilExclusive,
              limit,
              offset);
        }
      };
    }

    @Bean
    @Primary
    MutableMatchingPolicyCatalog matchingTestPolicyCatalog() {
      return new MutableMatchingPolicyCatalog();
    }

    @Bean
    MutableTravelTimePort matchingTestTravelTimePort() {
      return new MutableTravelTimePort();
    }
  }

  static final class FederatedReceivingCapacityFailureProbe {

    private final java.util.concurrent.atomic.AtomicBoolean loseNextResponse =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.CopyOnWriteArrayList<InternalId> reservationOperationIds =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<InternalId> outcomeOperationIds =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    void loseNextResponseAfterReservationCommit() {
      loseNextResponse.set(true);
    }

    void reset() {
      loseNextResponse.set(false);
      reservationOperationIds.clear();
      outcomeOperationIds.clear();
    }

    boolean consumeLostResponse() {
      return loseNextResponse.compareAndSet(true, false);
    }

    void recordReservation(InternalId operationId) {
      reservationOperationIds.add(operationId);
    }

    void recordOutcomeLookup(InternalId operationId) {
      outcomeOperationIds.add(operationId);
    }

    List<InternalId> reservationOperationIds() {
      return List.copyOf(reservationOperationIds);
    }

    List<InternalId> outcomeOperationIds() {
      return List.copyOf(outcomeOperationIds);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class PatientContextTestConfiguration {

    @Bean
    PatientContextDisclosurePolicy syntheticPatientContextDisclosurePolicy() {
      return request ->
          "CARE_COORDINATION".equals(request.purposeCode())
              ? new PatientContextDisclosureDecision(
                  PatientContextDisclosureDecision.Outcome.ALLOWED,
                  Set.of(PatientContextCategory.PROBLEMS))
              : new PatientContextDisclosureDecision(
                  PatientContextDisclosureDecision.Outcome.DENIED, Set.of());
    }
  }

  static final class DirectoryIsolationProbe {

    private final java.util.concurrent.CopyOnWriteArrayList<String> isolationLevels =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Long> transactionIds =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Integer> activeConnections =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Integer> auditActiveConnections =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    void record(String isolationLevel, long transactionId, int activeConnectionCount) {
      isolationLevels.add(isolationLevel);
      transactionIds.add(transactionId);
      activeConnections.add(activeConnectionCount);
    }

    List<String> observedIsolationLevels() {
      return List.copyOf(isolationLevels);
    }

    List<Long> observedTransactionIds() {
      return List.copyOf(transactionIds);
    }

    List<Integer> observedActiveConnections() {
      return List.copyOf(activeConnections);
    }

    void recordAuditConnectionCount(int activeConnectionCount) {
      auditActiveConnections.add(activeConnectionCount);
    }

    List<Integer> auditActiveConnections() {
      return List.copyOf(auditActiveConnections);
    }

    void clear() {
      isolationLevels.clear();
      transactionIds.clear();
      activeConnections.clear();
      auditActiveConnections.clear();
    }
  }

  static final class CapacityIsolationProbe {

    private final java.util.concurrent.CopyOnWriteArrayList<String> isolationLevels =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    void record(String isolationLevel) {
      isolationLevels.add(isolationLevel);
    }

    List<String> observedIsolationLevels() {
      return List.copyOf(isolationLevels);
    }

    void clear() {
      isolationLevels.clear();
    }
  }

  static final class MutableTravelTimePort implements TravelTimePort {

    private volatile List<Estimate> estimates = List.of();
    private volatile TravelTimeRequest lastRequest;
    private volatile boolean unavailable;

    void setEstimates(List<Estimate> estimates) {
      this.estimates = List.copyOf(estimates);
      this.lastRequest = null;
      this.unavailable = false;
    }

    void setUnavailable(boolean unavailable) {
      this.unavailable = unavailable;
      this.lastRequest = null;
    }

    TravelTimeRequest lastRequest() {
      return lastRequest;
    }

    @Override
    public List<Estimate> estimate(TravelTimeRequest request) {
      lastRequest = request;
      if (unavailable) {
        throw new TravelTimePort.UnavailableException("synthetic routing outage");
      }
      return estimates;
    }
  }

  static final class MutableMatchingPolicyCatalog implements MatchingPolicyCatalog {

    private volatile MatchingRecommendationPolicy activePolicy;

    void set(MatchingRecommendationPolicy policy) {
      activePolicy = policy;
    }

    @Override
    public java.util.Optional<MatchingRecommendationPolicy> activePolicy() {
      return java.util.Optional.ofNullable(activePolicy);
    }
  }

  private String createOrganization(String key) throws Exception {
    return createOrganization(key, CURATOR);
  }

  private String createOrganization(String key, String actor) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/directory/organizations")
                    .header("Authorization", bearer(token(actor)))
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
    return createFacility(key, organizationId, name, CURATOR);
  }

  private String createFacility(String key, String organizationId, String name, String actor)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/directory/facilities")
                    .header("Authorization", bearer(token(actor)))
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

  private void createLocation(String facilityId) throws Exception {
    createLocation(facilityId, "synthetic-cross-phase-origin-location");
  }

  private void createLocation(String facilityId, String idempotencyKey) throws Exception {
    createLocation(facilityId, idempotencyKey, 72.8, 19.1);
  }

  private void createLocation(
      String facilityId, String idempotencyKey, double longitude, double latitude)
      throws Exception {
    createLocation(facilityId, idempotencyKey, longitude, latitude, CURATOR);
  }

  private void createLocation(
      String facilityId, String idempotencyKey, double longitude, double latitude, String actor)
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/directory/locations")
                .header("Authorization", bearer(token(actor)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content(
                    "{\"facilityId\":\""
                        + facilityId
                        + "\",\"name\":\"Synthetic location\",\"longitude\":"
                        + longitude
                        + ",\"latitude\":"
                        + latitude
                        + ",\"primary\":true,\"source\":{"
                        + "\"issuer\":\"urn:synthetic:directory\",\"resourceType\":\"Location\","
                        + "\"reference\":\"origin-location-"
                        + UUID.randomUUID()
                        + "\"},\"provenance\":{\"method\":\"MANUAL_REVIEW\","
                        + "\"observedAt\":\"2026-10-09T09:00:00Z\","
                        + "\"reasonCode\":\"SYNTHETIC_SETUP\"}}"))
        .andExpect(status().isCreated());
  }

  private String createFacilityCapabilityAssertion(String facilityId) throws Exception {
    MvcResult response =
        mockMvc
            .perform(
                post("/api/v1/directory/facilities/{facilityId}/capability-assertions", facilityId)
                    .header("Authorization", bearer(token(CURATOR)))
                    .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                    .header("Idempotency-Key", "synthetic-cross-phase-destination-capability")
                    .contentType("application/json")
                    .content(
                        "{\"capabilityCode\":\""
                            + CAPABILITY
                            + "\",\"healthcareServiceId\":null,\"verificationStatus\":"
                            + "\"VERIFIED\",\"source\":{\"issuer\":"
                            + "\"urn:synthetic:directory\",\"resourceType\":"
                            + "\"FacilityCapabilityAssertion\",\"reference\":"
                            + "\"destination-capability-assertion\"},\"provenance\":{"
                            + "\"method\":\"MANUAL_REVIEW\",\"observedAt\":"
                            + "\"2026-10-09T09:00:00Z\",\"reasonCode\":"
                            + "\"SYNTHETIC_SETUP\"},\"effectiveFrom\":"
                            + "\"2026-01-01T00:00:00Z\",\"effectiveUntil\":null}"))
            .andExpect(status().isCreated())
            .andReturn();
    return com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.id");
  }

  private void correctFacilityCapabilityAssertion(
      String assertionId, long expectedVersion, String verificationStatus, String idempotencyKey)
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/directory/capability-assertions/{assertionId}/corrections", assertionId)
                .header("Authorization", bearer(token(CURATOR)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":"
                        + expectedVersion
                        + ",\"verificationStatus\":\""
                        + verificationStatus
                        + "\",\"source\":{\"issuer\":"
                        + "\"urn:synthetic:directory\",\"resourceType\":"
                        + "\"FacilityCapabilityAssertion\",\"reference\":\""
                        + idempotencyKey
                        + "\"},\"provenance\":{\"method\":\"MANUAL_REVIEW\","
                        + "\"observedAt\":\"2026-10-09T09:00:00Z\","
                        + "\"reasonCode\":\"SYNTHETIC_ASSERTION_CORRECTION\"},"
                        + "\"effectiveFrom\":\"2026-01-01T00:00:00Z\","
                        + "\"effectiveUntil\":null}"))
        .andExpect(status().isNoContent());
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
                        + "{\"sourceCode\":\"primary\",\"priority\":0,"
                        + "\"fallbackWhenHigherPriorityAbsent\":false,"
                        + "\"fallbackWhenHigherPriorityStale\":false},"
                        + "{\"sourceCode\":\"manual\",\"priority\":1,"
                        + "\"fallbackWhenHigherPriorityAbsent\":true,"
                        + "\"fallbackWhenHigherPriorityStale\":false}],"
                        + "\"maximumObservationAgeSeconds\":600,\"defaultValiditySeconds\":300,"
                        + "\"futureSkewToleranceSeconds\":30,\"minimumPassingQuantity\":1,"
                        + "\"passingStates\":[],\"reasonCode\":\"SYNTHETIC_SETUP\"}"))
        .andExpect(status().isCreated());
  }

  private void recordCapacityObservation(String organizationId, String facilityId)
      throws Exception {
    recordCapacityObservation(
        organizationId,
        facilityId,
        "synthetic-cross-phase-observation",
        "cross-phase-observation-01",
        4);
  }

  private void recordCapacityObservation(
      String organizationId,
      String facilityId,
      String idempotencyKey,
      String sourceObservationId,
      int quantity)
      throws Exception {
    recordCapacityObservation(
        organizationId,
        facilityId,
        idempotencyKey,
        sourceObservationId,
        quantity,
        Instant.now().minusSeconds(2));
  }

  private void recordCapacityObservation(
      String organizationId,
      String facilityId,
      String idempotencyKey,
      String sourceObservationId,
      int quantity,
      Instant observedAt)
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/capacity/observations")
                .header("Authorization", bearer(token(CAPACITY_REPORTER)))
                .header("X-AyuVahak-Execution-Scope", "FACILITY")
                .header("X-AyuVahak-Organization-Id", organizationId)
                .header("X-AyuVahak-Facility-Id", facilityId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content(
                    "{\"dimensionCode\":\""
                        + DIMENSION
                        + "\",\"sourceCode\":\"manual\","
                        + "\"sourceIssuer\":\"urn:synthetic:capacity-source\","
                        + "\"sourceObservationId\":\""
                        + sourceObservationId
                        + "\","
                        + "\"observationKind\":\"VALUE\",\"valueKind\":\"QUANTITY\","
                        + "\"quantity\":"
                        + quantity
                        + ",\"state\":null,\"unit\":\"bed\","
                        + "\"observedAt\":\""
                        + observedAt
                        + "\",\"validUntil\":null,\"reportingMethod\":\"MANUAL_ENTRY\","
                        + "\"reasonCode\":\"CAPACITY_REPORT\"}"))
        .andExpect(status().isCreated());
  }

  private String createReferral(String organizationId, String facilityId) throws Exception {
    return createReferral(organizationId, facilityId, "synthetic-cross-phase-referral", true);
  }

  private String createReferral(
      String organizationId, String facilityId, String idempotencyKey, boolean includeGeography)
      throws Exception {
    Instant start = Instant.now().plusSeconds(3600);
    Instant end = start.plusSeconds(3600);
    String geography = includeGeography ? "[\"SYNTHETIC_DISTRICT_01\"]" : "[]";
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
            + "\",\"permittedGeographyCodes\":"
            + geography
            + ","
            + "\"selectedDestinationOrganizationId\":null,\"priorityCode\":\"ROUTINE\"}}";
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/referrals")
                    .header("Authorization", bearer(token(REFERRAL_ORIGINATOR)))
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", facilityId)
                    .header("Idempotency-Key", idempotencyKey)
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
            + "'subject', ?, ?, 'PLATFORM', 'ACTIVE', CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING",
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

  private void grantService(
      String clientId, String authority, String scope, String organizationId, String facilityId) {
    if ("PLATFORM".equals(scope)) {
      jdbc.update(
          "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
              + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
              + "scope_kind, grant_status, created_at) VALUES (gen_random_uuid(), 'SERVICE', ?, "
              + "'client_id', ?, ?, 'PLATFORM', 'ACTIVE', CURRENT_TIMESTAMP)",
          ISSUER,
          clientId,
          authority);
      return;
    }
    if ("ORGANIZATION".equals(scope)) {
      jdbc.update(
          "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
              + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
              + "scope_kind, organization_issuer, organization_identifier_type, "
              + "organization_identifier, grant_status, created_at) VALUES (gen_random_uuid(), "
              + "'SERVICE', ?, 'client_id', ?, ?, 'ORGANIZATION', ?, 'Organization', ?, "
              + "'ACTIVE', CURRENT_TIMESTAMP)",
          ISSUER,
          clientId,
          authority,
          DIRECTORY_ISSUER,
          organizationId);
      return;
    }
    if (!"FACILITY".equals(scope) || facilityId == null) {
      throw new IllegalArgumentException("service grant scope is invalid");
    }
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, organization_issuer, organization_identifier_type, "
            + "organization_identifier, facility_issuer, facility_identifier_type, "
            + "facility_identifier, grant_status, created_at) VALUES (gen_random_uuid(), "
            + "'SERVICE', ?, 'client_id', ?, ?, 'FACILITY', ?, 'Organization', ?, ?, "
            + "'Facility', ?, 'ACTIVE', CURRENT_TIMESTAMP)",
        ISSUER,
        clientId,
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

  private String serviceToken(String clientId) throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(SIGNING_KEY.getKeyID())
                .build(),
            new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(clientId)
                .audience(AUDIENCE)
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(1)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("typ", "Bearer")
                .claim("client_id", clientId)
                .build());
    jwt.sign(new RSASSASigner(SIGNING_KEY));
    return jwt.serialize();
  }

  private void activateSyntheticTransportProvider(String administrator, String prefix)
      throws Exception {
    String create =
        "{\"partnerKey\":\"synthetic-transport-provider\","
            + "\"organization\":{\"issuer\":\"urn:synthetic:transport-provider\","
            + "\"identifier\":\"SYN-PROVIDER-001\"},"
            + "\"endpointOrigin\":\"https://partner.test:"
            + partnerServer.getAddress().getPort()
            + "\","
            + "\"basePath\":\"/api\","
            + "\"authentication\":{\"method\":\"NONE\"},"
            + "\"tlsPolicy\":\"SYSTEM_CA\",\"limits\":{"
            + "\"connectTimeoutMillis\":1000,\"responseTimeoutMillis\":2000,"
            + "\"operationTimeoutMillis\":5000,\"maxResponseBytes\":262144,"
            + "\"maxDecompressedBytes\":524288,\"maxPages\":1,\"maxResources\":20,"
            + "\"maxNestingDepth\":16,\"maxAttempts\":1},\"retryClass\":\"NEVER\","
            + "\"allowedOperations\":[{\"ownerContext\":\"TRANSPORT\","
            + "\"operationCode\":\"transport.dispatch\",\"direction\":\"OUTBOUND\","
            + "\"protocol\":\"JSON\",\"profile\":\"AyuVahakTransportProvider-v1\"},{"
            + "\"ownerContext\":\"TRANSPORT\","
            + "\"operationCode\":\"transport.provider.evidence\","
            + "\"direction\":\"INBOUND\",\"protocol\":\"JSON\","
            + "\"profile\":\"AyuVahakTransportProvider-v1\"}],"
            + "\"inboundPrincipal\":{\"issuer\":\""
            + ISSUER
            + "\",\"clientId\":\"synthetic-transport-provider\"},"
            + "\"reasonCode\":\"PARTNER_SETUP\"}";
    mockMvc
        .perform(
            post("/api/v1/platform/exchange-partners")
                .header("Authorization", bearer(token(administrator)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", prefix + "-provider-profile-create")
                .contentType("application/json")
                .content(create))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            post("/api/v1/platform/exchange-partners/synthetic-transport-provider/versions/1/activate")
                .header("Authorization", bearer(token(administrator)))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM")
                .header("Idempotency-Key", prefix + "-provider-profile-activate")
                .contentType("application/json")
                .content("{\"reasonCode\":\"PARTNER_ACTIVATION\"}"))
        .andExpect(status().isOk());
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
