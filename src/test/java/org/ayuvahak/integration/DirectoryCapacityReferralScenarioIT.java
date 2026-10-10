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
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ayuvahak.capacity.application.CapacityEvidenceStore;
import org.ayuvahak.capacity.domain.CapacityObservationFact;
import org.ayuvahak.capacity.domain.CapacityReadinessOutcome;
import org.ayuvahak.directory.application.DirectoryStore;
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
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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

/**
 * Synthetic cross-context scenario for the integrated Directory, Capacity, and Referral workflow.
 */
@SpringBootTest(
    classes = AyuVahakApplication.class,
    properties = {
      "management.tracing.sampling.probability=0.0",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-integration-service",
      "ayuvahak.authentication.allow-http=true"
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(DirectoryCapacityReferralScenarioIT.MatchingTestConfiguration.class)
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
  @Autowired private ExecutionContextFactory executionContextFactory;
  @Autowired private MatchingOwnerEvidenceReader matchingOwnerEvidenceReader;
  @Autowired private MatchingEligibilityApplicationService matchingEligibility;
  @Autowired private MutableMatchingPolicyCatalog testPolicies;
  @Autowired private CapacityIsolationProbe capacityIsolationProbe;
  @Autowired private DirectoryIsolationProbe directoryIsolationProbe;
  @Autowired private MutableTravelTimePort testTravelTimePort;

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

  @Test
  void directoryFactsCapacityEvidenceAndReferralRequirementComposeWithoutCrossOwnership()
      throws Exception {
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
                "SELECT COUNT(*) FROM matching.recommendation_snapshot", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM matching.command_receipt", Integer.class))
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
                "SELECT COUNT(*) FROM matching.recommendation_snapshot", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM matching.command_receipt", Integer.class))
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
    assertThat(
            jdbc.queryForList(
                "SELECT schema_name FROM information_schema.schemata "
                    + "WHERE schema_name IN ('matching', 'transfer', 'transport', 'analytics')",
                String.class))
        .containsExactly("matching");

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
            com.jayway.jsonpath.JsonPath.<List<Integer>>read(
                goldenBody,
                "$.candidates[?(@.facility.identifier=='" + destinationFacilityId + "')].rank"))
        .containsExactly(1);

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
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/directory/organizations")
                    .header("Authorization", bearer(token(CURATOR)))
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
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/directory/facilities")
                    .header("Authorization", bearer(token(CURATOR)))
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
    mockMvc
        .perform(
            post("/api/v1/directory/locations")
                .header("Authorization", bearer(token(CURATOR)))
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
                        + "{\"sourceCode\":\"manual\",\"priority\":0,"
                        + "\"fallbackWhenHigherPriorityAbsent\":false,"
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
                        + Instant.now().minusSeconds(2)
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
            + "'subject', ?, ?, 'PLATFORM', 'ACTIVE', CURRENT_TIMESTAMP)",
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
