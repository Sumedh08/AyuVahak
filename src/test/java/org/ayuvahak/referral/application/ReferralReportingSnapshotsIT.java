package org.ayuvahak.referral.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.ayuvahak.platform.AyuVahakApplication;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Owner reporting contract tests against disposable local PostgreSQL. */
@SpringBootTest(
    classes = AyuVahakApplication.class,
    properties = {
      "management.tracing.sampling.probability=0.0",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-analytics,synthetic-referral-reader",
      "ayuvahak.authentication.allow-http=true",
      "ayuvahak.eventing.outbox.dispatcher-enabled=false"
    })
@Testcontainers
class ReferralReportingSnapshotsIT {

  private static final String TEST_PASSWORD = "local-test-only";
  private static final String ISSUER = "https://identity.example.test/realms/ayuvahak";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String ANALYTICS_CLIENT = "synthetic-analytics";
  private static final String READ_ONLY_CLIENT = "synthetic-referral-reader";
  private static final Instant PERIOD_START = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant PERIOD_END = Instant.parse("2026-02-01T00:00:00Z");
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(TEST_PASSWORD);

  @Container private static final PostgreSQLContainer CONTAINER = POSTGRES;
  private static final HttpServer LOCAL_JWKS = startLocalJwksServer();

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ReferralReportingSnapshots snapshots;

  @BeforeEach
  void clearReportingFixtures() {
    jdbc.update("DELETE FROM audit.audit_record");
    jdbc.update("DELETE FROM identity_access.authorization_grant");
  }

  @AfterAll
  static void stopLocalJwksServer() {
    LOCAL_JWKS.stop(0);
  }

  @DynamicPropertySource
  static void localInfrastructure(DynamicPropertyRegistry registry) {
    registry.add("AYUVAHAK_DATABASE_URL", POSTGRES::getJdbcUrl);
    registry.add("AYUVAHAK_DATABASE_USERNAME", POSTGRES::getUsername);
    registry.add("AYUVAHAK_DATABASE_PASSWORD", POSTGRES::getPassword);
    registry.add("ayuvahak.authentication.issuer", () -> ISSUER);
    registry.add(
        "ayuvahak.authentication.jwk-set-uri",
        () -> "http://127.0.0.1:" + LOCAL_JWKS.getAddress().getPort() + "/jwks");
  }

  @Test
  void reportsFirstSubmissionsAndResolutionsForAuthorizedOrganizationAndHalfOpenPeriod() {
    UUID organization = UUID.randomUUID();
    grantService(ANALYTICS_CLIENT, "ReferralReportingSnapshot", organization);
    ExecutionContext context = serviceContext(ANALYTICS_CLIENT, organization);

    UUID first = insertReferral(organization, "RESOLVED", PERIOD_START);
    addRevision(first, organization, 2, PERIOD_START.plusSeconds(60));
    addResolution(first, "FULFILLED", PERIOD_START.plusSeconds(86_400));

    UUID amended = insertReferral(organization, "SUBMITTED", PERIOD_START.plusSeconds(10));
    addRevision(amended, organization, 2, PERIOD_START.plusSeconds(20));

    insertReferral(organization, "SUBMITTED", PERIOD_END.minusNanos(1_000));
    insertReferral(organization, "SUBMITTED", PERIOD_END);

    UUID otherOrganization = UUID.randomUUID();
    insertReferral(otherOrganization, "RESOLVED", PERIOD_START.plusSeconds(30));
    UUID otherOrganizationResolution = latestReferral(otherOrganization);
    addResolution(otherOrganizationResolution, "UNFULFILLED", PERIOD_START.plusSeconds(40));

    ReferralReportingSnapshot result =
        snapshots.snapshot(context, request(UUID.randomUUID(), PERIOD_END));

    assertThat(result.organization().value()).isEqualTo(organization.toString());
    assertThat(result.facility()).isEmpty();
    assertThat(result.periodStart()).isEqualTo(PERIOD_START);
    assertThat(result.periodEnd()).isEqualTo(PERIOD_END);
    assertThat(result.sourceCutoff()).isEqualTo(PERIOD_END);
    assertThat(result.completeness()).isEqualTo(ReferralReportingCompleteness.COMPLETE);
    assertThat(result.measures().get("SUBMITTED_COUNT")).hasValue(3L);
    assertThat(result.measures().get("RESOLVED_COUNT")).hasValue(1L);
    assertThat(result.sourceVersion()).isPositive();
    assertThat(result.sourceSnapshotReference()).isNotNull();
    assertThat(result.generatedAt()).isNotNull();
    assertThat(result.toString()).doesNotContain("SYN-PATIENT-PHASE12-PRIVATE-91F4");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = "
                    + "'REFERRAL_REPORTING_SNAPSHOT'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void facilityScopedReportCountsOnlyThatFacilitiesReferralFacts() {
    UUID organization = UUID.randomUUID();
    UUID facilityA = UUID.randomUUID();
    UUID facilityB = UUID.randomUUID();
    grantServiceFacility(ANALYTICS_CLIENT, "ReferralReportingSnapshot", organization, facilityA);
    ExecutionContext context = serviceContext(ANALYTICS_CLIENT, organization, facilityA);

    UUID resolvedAtA = insertReferral(organization, facilityA, "RESOLVED", PERIOD_START);
    addResolution(resolvedAtA, "FULFILLED", PERIOD_START.plusSeconds(86_400));
    insertReferral(organization, facilityA, "SUBMITTED", PERIOD_START.plusSeconds(10));

    UUID resolvedAtB =
        insertReferral(organization, facilityB, "RESOLVED", PERIOD_START.plusSeconds(20));
    addResolution(resolvedAtB, "UNFULFILLED", PERIOD_START.plusSeconds(86_400));
    insertReferral(organization, null, "SUBMITTED", PERIOD_START.plusSeconds(30));

    UUID requestId = UUID.randomUUID();
    ReferralReportingSnapshot result = snapshots.snapshot(context, request(requestId, PERIOD_END));

    assertThat(result.organization().value()).isEqualTo(organization.toString());
    assertThat(result.facility())
        .contains(
            new ExternalReference(
                new IssuerId(DIRECTORY_ISSUER), "Facility", facilityA.toString()));
    assertThat(result.measures().get("SUBMITTED_COUNT")).hasValue(2L);
    assertThat(result.measures().get("RESOLVED_COUNT")).hasValue(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT origin_facility_id FROM referral.reporting_snapshot WHERE request_id = ?",
                UUID.class,
                requestId))
        .isEqualTo(facilityA);
  }

  @Test
  void facilityScopedServiceGrantCannotReadAnotherFacility() {
    UUID organization = UUID.randomUUID();
    UUID facilityA = UUID.randomUUID();
    UUID facilityB = UUID.randomUUID();
    grantServiceFacility(ANALYTICS_CLIENT, "ReferralReportingSnapshot", organization, facilityA);
    ExecutionContext wrongFacility = serviceContext(ANALYTICS_CLIENT, organization, facilityB);
    UUID requestId = UUID.randomUUID();

    assertThatThrownBy(() -> snapshots.snapshot(wrongFacility, request(requestId, PERIOD_END)))
        .isInstanceOf(PlatformProblemException.class)
        .satisfies(
            problem ->
                assertThat(((PlatformProblemException) problem).status())
                    .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM referral.reporting_snapshot WHERE request_id = ?",
                Integer.class,
                requestId))
        .isZero();
  }

  @Test
  void stableRequestIdentityConflictsWhenScopeChangesBetweenOrganizationAndFacility() {
    UUID organization = UUID.randomUUID();
    UUID facility = UUID.randomUUID();
    UUID anotherFacility = UUID.randomUUID();
    grantService(ANALYTICS_CLIENT, "ReferralReportingSnapshot", organization);
    grantServiceFacility(ANALYTICS_CLIENT, "ReferralReportingSnapshot", organization, facility);
    grantServiceFacility(
        ANALYTICS_CLIENT, "ReferralReportingSnapshot", organization, anotherFacility);
    UUID requestId = UUID.randomUUID();

    snapshots.snapshot(
        serviceContext(ANALYTICS_CLIENT, organization), request(requestId, PERIOD_END));

    assertThatThrownBy(
            () ->
                snapshots.snapshot(
                    serviceContext(ANALYTICS_CLIENT, organization, facility),
                    request(requestId, PERIOD_END)))
        .isInstanceOf(PlatformProblemException.class)
        .satisfies(
            problem ->
                assertThat(((PlatformProblemException) problem).status())
                    .isEqualTo(org.springframework.http.HttpStatus.CONFLICT));

    UUID facilityRequestId = UUID.randomUUID();
    snapshots.snapshot(
        serviceContext(ANALYTICS_CLIENT, organization, facility),
        request(facilityRequestId, PERIOD_END));
    assertThatThrownBy(
            () ->
                snapshots.snapshot(
                    serviceContext(ANALYTICS_CLIENT, organization, anotherFacility),
                    request(facilityRequestId, PERIOD_END)))
        .isInstanceOf(PlatformProblemException.class)
        .satisfies(
            problem ->
                assertThat(((PlatformProblemException) problem).status())
                    .isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
  }

  @Test
  void completeZeroIsDistinctFromAnIncompletePeriod() {
    UUID organization = UUID.randomUUID();
    grantService(ANALYTICS_CLIENT, "ReferralReportingSnapshot", organization);
    ExecutionContext context = serviceContext(ANALYTICS_CLIENT, organization);

    ReferralReportingSnapshot complete =
        snapshots.snapshot(context, request(UUID.randomUUID(), PERIOD_END));
    assertThat(complete.completeness()).isEqualTo(ReferralReportingCompleteness.COMPLETE);
    assertThat(complete.measures().get("SUBMITTED_COUNT")).hasValue(0L);
    assertThat(complete.measures().get("RESOLVED_COUNT")).hasValue(0L);

    ReferralReportingSnapshot partial =
        snapshots.snapshot(context, request(UUID.randomUUID(), PERIOD_START.plusSeconds(86_400)));
    assertThat(partial.completeness()).isEqualTo(ReferralReportingCompleteness.PARTIAL);
    assertThat(partial.measures().get("SUBMITTED_COUNT")).isEmpty();
    assertThat(partial.measures().get("RESOLVED_COUNT")).isEmpty();
  }

  @Test
  void analyticsServiceNeedsItsDedicatedGrantAndHumanCannotUseTheReportingContract() {
    UUID organization = UUID.randomUUID();
    grantService(READ_ONLY_CLIENT, "ReferralRead", organization);
    ExecutionContext readOnly = serviceContext(READ_ONLY_CLIENT, organization);

    assertThatThrownBy(() -> snapshots.snapshot(readOnly, request(UUID.randomUUID(), PERIOD_END)))
        .isInstanceOf(PlatformProblemException.class);

    ExecutionContext human = humanContext(organization);
    assertThatThrownBy(() -> snapshots.snapshot(human, request(UUID.randomUUID(), PERIOD_END)))
        .isInstanceOf(PlatformProblemException.class);
  }

  @Test
  void stableRequestIdentityReplaysTheSameSnapshotAndRejectsChangedInputs() {
    UUID organization = UUID.randomUUID();
    grantService(ANALYTICS_CLIENT, "ReferralReportingSnapshot", organization);
    ExecutionContext context = serviceContext(ANALYTICS_CLIENT, organization);
    UUID requestId = UUID.randomUUID();

    ReferralReportingSnapshot first = snapshots.snapshot(context, request(requestId, PERIOD_END));
    ReferralReportingSnapshot replay = snapshots.snapshot(context, request(requestId, PERIOD_END));

    assertThat(replay.sourceSnapshotReference()).isEqualTo(first.sourceSnapshotReference());
    assertThat(replay.sourceVersion()).isEqualTo(first.sourceVersion());
    assertThat(replay.generatedAt()).isEqualTo(first.generatedAt());
    assertThatThrownBy(
            () ->
                snapshots.snapshot(
                    context,
                    new ReferralReportingSnapshotRequest(
                        requestId,
                        Set.of("SUBMITTED_COUNT"),
                        PERIOD_START,
                        PERIOD_END,
                        PERIOD_END)))
        .isInstanceOf(PlatformProblemException.class)
        .satisfies(
            problem ->
                assertThat(((PlatformProblemException) problem).status())
                    .isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
  }

  private ReferralReportingSnapshotRequest request(UUID requestId, Instant sourceCutoff) {
    return new ReferralReportingSnapshotRequest(
        requestId,
        Set.of("SUBMITTED_COUNT", "RESOLVED_COUNT"),
        PERIOD_START,
        PERIOD_END,
        sourceCutoff);
  }

  private ExecutionContext serviceContext(String clientId, UUID organization) {
    var factory = new ExecutionContextFactory((actor, scope) -> true);
    return factory.forBackgroundWork(
        ActorReference.service(new IssuerId(ISSUER), clientId),
        RequestedExecutionScope.organization(
            new ExternalReference(
                new IssuerId(DIRECTORY_ISSUER), "Organization", organization.toString())),
        CorrelationId.generate(),
        Optional.empty());
  }

  private ExecutionContext serviceContext(String clientId, UUID organization, UUID facility) {
    var factory = new ExecutionContextFactory((actor, scope) -> true);
    return factory.forBackgroundWork(
        ActorReference.service(new IssuerId(ISSUER), clientId),
        RequestedExecutionScope.facility(
            new ExternalReference(
                new IssuerId(DIRECTORY_ISSUER), "Organization", organization.toString()),
            new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", facility.toString())),
        CorrelationId.generate(),
        Optional.empty());
  }

  private ExecutionContext humanContext(UUID organization) {
    var factory = new ExecutionContextFactory((actor, scope) -> true);
    return factory.forInteractiveRequest(
        ActorReference.human(new IssuerId(ISSUER), "synthetic-human"),
        RequestedExecutionScope.organization(
            new ExternalReference(
                new IssuerId(DIRECTORY_ISSUER), "Organization", organization.toString())),
        CorrelationId.generate());
  }

  private void grantService(String clientId, String authority, UUID organization) {
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
        organization.toString());
  }

  private void grantServiceFacility(
      String clientId, String authority, UUID organization, UUID facility) {
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
        organization.toString(),
        DIRECTORY_ISSUER,
        facility.toString());
  }

  private UUID insertReferral(UUID organization, String state, Instant submittedAt) {
    return insertReferral(organization, null, state, submittedAt);
  }

  private UUID insertReferral(UUID organization, UUID facility, String state, Instant submittedAt) {
    UUID referralId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO referral.draft (referral_id, state, origin_organization_id, "
            + "origin_facility_id, patient_issuer, patient_resource_type, patient_identifier, "
            + "created_by_kind, created_by_issuer, created_by_identifier_type, "
            + "created_by_identifier, updated_by_kind, updated_by_issuer, "
            + "updated_by_identifier_type, updated_by_identifier, version, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, 'Patient', ?, 'SERVICE', ?, 'client_id', ?, "
            + "'SERVICE', ?, 'client_id', ?, ?, ?, ?)",
        referralId,
        state,
        organization,
        facility,
        ISSUER,
        "SYN-PATIENT-PHASE12-PRIVATE-91F4-" + referralId,
        ISSUER,
        "synthetic-referral-source",
        ISSUER,
        "synthetic-referral-source",
        state.equals("RESOLVED") ? 3L : 2L,
        Timestamp.from(submittedAt),
        Timestamp.from(submittedAt));
    addRevision(referralId, organization, facility, 1, submittedAt);
    return referralId;
  }

  private void addRevision(UUID referralId, UUID organization, long revision, Instant submittedAt) {
    addRevision(referralId, organization, null, revision, submittedAt);
  }

  private void addRevision(
      UUID referralId, UUID organization, UUID facility, long revision, Instant submittedAt) {
    jdbc.update(
        "INSERT INTO referral.submitted_revision (referral_id, revision_number, referral_version, "
            + "origin_organization_id, origin_facility_id, patient_issuer, patient_resource_type, patient_identifier, "
            + "source_actor_kind, source_actor_issuer, source_actor_identifier_type, "
            + "source_actor_identifier, submitted_by_kind, submitted_by_issuer, "
            + "submitted_by_identifier_type, submitted_by_identifier, submitted_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'Patient', ?, 'SERVICE', ?, 'client_id', ?, 'SERVICE', ?, "
            + "'client_id', ?, ?)",
        referralId,
        revision,
        revision + 1,
        organization,
        facility,
        ISSUER,
        "SYN-PATIENT-PHASE12-PRIVATE-91F4-" + referralId,
        ISSUER,
        "synthetic-referral-source",
        ISSUER,
        "synthetic-referral-source",
        Timestamp.from(submittedAt));
  }

  private void addResolution(UUID referralId, String outcome, Instant resolvedAt) {
    jdbc.update(
        "INSERT INTO referral.resolution (referral_id, outcome, reason_code, resolved_by_kind, "
            + "resolved_by_issuer, resolved_by_identifier_type, resolved_by_identifier, "
            + "referral_version, resolved_at) VALUES (?, ?, 'SYNTHETIC_TEST', 'SERVICE', ?, "
            + "'client_id', 'synthetic-referral-source', 3, ?)",
        referralId,
        outcome,
        ISSUER,
        Timestamp.from(resolvedAt));
  }

  private UUID latestReferral(UUID organization) {
    return jdbc.queryForObject(
        "SELECT referral_id FROM referral.draft WHERE origin_organization_id = ? "
            + "ORDER BY created_at DESC LIMIT 1",
        UUID.class,
        organization);
  }

  private static HttpServer startLocalJwksServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/jwks",
          exchange -> {
            byte[] body = "{\"keys\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var response = exchange.getResponseBody()) {
              response.write(body);
            }
          });
      server.start();
      return server;
    } catch (java.io.IOException unavailable) {
      throw new ExceptionInInitializerError(unavailable);
    }
  }
}
