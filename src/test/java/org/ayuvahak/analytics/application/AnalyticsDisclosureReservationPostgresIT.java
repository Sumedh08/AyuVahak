package org.ayuvahak.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.ayuvahak.analytics.adapter.persistence.JdbcDisclosureReservationStore;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.analytics.domain.DisclosureCandidate;
import org.ayuvahak.analytics.domain.DisclosureOutput;
import org.ayuvahak.analytics.domain.DisclosurePolicyVersion;
import org.ayuvahak.analytics.domain.DisclosureReservation;
import org.ayuvahak.analytics.domain.DisclosureReservationState;
import org.ayuvahak.analytics.domain.DisclosureScope;
import org.ayuvahak.platform.audit.adapter.persistence.JdbcAuditRecordAppender;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.authorization.application.AuthorizationGrantLookup;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** PostgreSQL acceptance for the shared, durable Analytics disclosure gate. */
@Testcontainers
class AnalyticsDisclosureReservationPostgresIT {

  private static final String PASSWORD = "local-test-only";
  private static final String POLICY_KEY = "SYN-REPORTING-SCOPE-A";
  private static final IssuerId ISSUER = new IssuerId("https://identity.example.test/realm");
  private static final ExternalReference ORGANIZATION =
      new ExternalReference(
          new IssuerId("urn:ayuvahak:directory"), "Organization", UUID.randomUUID().toString());
  private static final ActorReference ANALYTICS =
      ActorReference.service(ISSUER, "synthetic-analytics-disclosure-worker");
  private static final ActorReference ADMIN =
      ActorReference.human(ISSUER, "synthetic-analytics-privacy-admin");
  private static final Set<String> AUTHORITIES =
      Set.of(
          AnalyticsAuthorities.disclosurePolicyAdministration().code(),
          AnalyticsAuthorities.disclosureRelease().code(),
          AnalyticsAuthorities.disclosureReservationLifecycle().code(),
          AnalyticsAuthorities.disclosureReview().code());

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(PASSWORD);

  private static JdbcTemplate jdbc;
  private static DataSourceTransactionManager transactionManager;
  private static AnalyticsDisclosureReservationService service;
  private static ExecutionContextFactory contexts;
  private static MutableDisclosureClock clock;
  private static TestNoSendEvidenceVerifier noSendEvidence;

  @BeforeAll
  static void startLocalDatabaseAndMigrate() {
    POSTGRES.start();
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbc = new JdbcTemplate(dataSource);
    transactionManager = new DataSourceTransactionManager(dataSource);
    contexts = new ExecutionContextFactory((actor, scope) -> actor != null && scope != null);
    clock = new MutableDisclosureClock(Instant.parse("2026-05-01T12:00:00Z"));
    noSendEvidence = new TestNoSendEvidenceVerifier();
    service = newService(noSendEvidence);
  }

  @AfterAll
  static void stopLocalDatabase() {
    POSTGRES.stop();
  }

  @BeforeEach
  void clearDisclosureFixtures() {
    jdbc.update(
        "TRUNCATE analytics.disclosure_privacy_scope, analytics.disclosure_policy_head, analytics.disclosure_reservation "
            + "RESTART IDENTITY CASCADE");
    jdbc.update("DELETE FROM audit.audit_record WHERE action_code LIKE 'ANALYTICS_DISCLOSURE_%'");
    noSendEvidence.clear();
    clock.set(Instant.parse("2026-05-01T12:00:00Z"));
  }

  @Test
  void complementaryOutputsCannotRevealASuppressedCell() {
    UUID broadLineage = UUID.randomUUID();
    activate(
        policy(
            1,
            broadLineage,
            Map.of("CELL_A", Set.of(broadLineage), "TOTAL", Set.of(broadLineage)),
            true));

    DisclosureReservation first =
        service.reserve(releaseContext(), candidate(UUID.randomUUID(), 1, outputA()));
    DisclosureReservation complementary =
        service.reserve(releaseContext(), candidate(UUID.randomUUID(), 1, totalOutput()));

    assertThat(first.state()).isEqualTo(DisclosureReservationState.RESERVED);
    assertThat(complementary.state()).isEqualTo(DisclosureReservationState.WITHHELD);
    assertThat(complementary.reasonCode()).isEqualTo("INFERENCE_RISK");
  }

  @Test
  void organizationAndFacilityReleasesForSameMeasureCannotBypassSharedDisclosureHistory() {
    UUID broadLineage = UUID.randomUUID();
    activate(policy(1, broadLineage, Map.of("TOTAL", Set.of(broadLineage)), true));
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    Instant end = Instant.parse("2026-02-01T00:00:00Z");
    DisclosureScope organizationScope =
        new DisclosureScope(
            "SYN-INDICATOR-001",
            1,
            ORGANIZATION.issuer().value(),
            UUID.fromString(ORGANIZATION.value()),
            Optional.empty(),
            start,
            end);
    DisclosureScope facilityScope =
        new DisclosureScope(
            "SYN-INDICATOR-001",
            1,
            ORGANIZATION.issuer().value(),
            UUID.fromString(ORGANIZATION.value()),
            Optional.of(UUID.randomUUID()),
            start,
            end);

    DisclosureReservation organizationRelease =
        service.reserve(
            releaseContext(),
            scopedCandidate(
                UUID.randomUUID(),
                organizationScope,
                Map.of("ORG_A", 9L, "ORG_B", 1L),
                new DisclosureOutput("TOTAL", 10, Map.of("ORG_A", 1L, "ORG_B", 1L))));
    DisclosureReservation facilityRelease =
        service.reserve(
            releaseContext(),
            scopedCandidate(
                UUID.randomUUID(),
                facilityScope,
                Map.of("FACILITY_A", 9L, "FACILITY_B", 1L),
                new DisclosureOutput("TOTAL", 10, Map.of("FACILITY_A", 1L, "FACILITY_B", 1L))));

    assertThat(organizationRelease.state()).isEqualTo(DisclosureReservationState.RESERVED);
    assertThat(facilityRelease.state()).isEqualTo(DisclosureReservationState.REVIEW_REQUIRED);
    assertThat(facilityRelease.reasonCode()).isEqualTo("SCOPE_OVERLAP_REVIEW_REQUIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analytics.disclosure_reservation_output o "
                    + "JOIN analytics.disclosure_reservation r USING (reservation_id) "
                    + "WHERE r.state IN ('RESERVED', 'POTENTIALLY_DISCLOSED', "
                    + "'DISCLOSED', 'REVIEW_REQUIRED')",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void scopeOverlapCannotRouteAnInferableZeroRatioThroughHumanReview() {
    UUID broadLineage = UUID.randomUUID();
    activate(
        policy(
            1,
            broadLineage,
            Map.of("ORG_OUTPUT", Set.of(broadLineage), "ZERO_RATIO", Set.of(broadLineage)),
            true));
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    Instant end = Instant.parse("2026-02-01T00:00:00Z");
    DisclosureScope organizationScope =
        disclosureScope("SYN-INDICATOR-001", 1, Optional.empty(), start, end);
    DisclosureScope facilityScope =
        disclosureScope("SYN-INDICATOR-001", 1, Optional.of(UUID.randomUUID()), start, end);

    DisclosureReservation organizationRelease =
        service.reserve(
            releaseContext(),
            scopedCandidate(
                UUID.randomUUID(),
                organizationScope,
                Map.of("ORG_CELL", 9L),
                new DisclosureOutput("ORG_OUTPUT", 9, Map.of("ORG_CELL", 1L))));
    DisclosureReservation zeroRatio =
        service.reserve(
            releaseContext(),
            scopedCandidate(
                UUID.randomUUID(),
                facilityScope,
                Map.of("NUMERATOR_A", 0L, "NUMERATOR_B", 0L, "DENOMINATOR", 12L),
                new DisclosureOutput(
                    "ZERO_RATIO", 0, Map.of("NUMERATOR_A", 1L, "NUMERATOR_B", 1L), 0, false)));

    assertThat(organizationRelease.state()).isEqualTo(DisclosureReservationState.RESERVED);
    assertThat(zeroRatio.state()).isEqualTo(DisclosureReservationState.WITHHELD);
    assertThat(zeroRatio.reasonCode()).isEqualTo("INFERENCE_RISK");
  }

  @Test
  void overlappingPeriodsForSameFacilityRequireDisclosureReviewAcrossChannels() {
    UUID broadLineage = UUID.randomUUID();
    activate(policy(1, broadLineage, Map.of("TOTAL", Set.of(broadLineage)), true));
    UUID facilityId = UUID.randomUUID();
    DisclosureScope monthlyScope =
        disclosureScope(
            "SYN-INDICATOR-001",
            1,
            Optional.of(facilityId),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-02-01T00:00:00Z"));
    DisclosureScope quarterlyScope =
        disclosureScope(
            "SYN-INDICATOR-001",
            1,
            Optional.of(facilityId),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-04-01T00:00:00Z"));

    DisclosureReservation monthlyRelease =
        service.reserve(
            releaseContext(),
            scopedCandidate(
                UUID.randomUUID(),
                monthlyScope,
                Map.of("MONTH_VALUE", 10L),
                new DisclosureOutput("TOTAL", 10, Map.of("MONTH_VALUE", 1L))));
    DisclosureReservation quarterlyRelease =
        service.reserve(
            releaseContext(),
            scopedCandidate(
                UUID.randomUUID(),
                quarterlyScope,
                Map.of("QUARTER_VALUE", 12L),
                new DisclosureOutput("TOTAL", 12, Map.of("QUARTER_VALUE", 1L))));

    assertThat(monthlyRelease.state()).isEqualTo(DisclosureReservationState.RESERVED);
    assertThat(quarterlyRelease.state()).isEqualTo(DisclosureReservationState.REVIEW_REQUIRED);
    assertThat(quarterlyRelease.reasonCode()).isEqualTo("SCOPE_OVERLAP_REVIEW_REQUIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analytics.disclosure_reservation_output o "
                    + "JOIN analytics.disclosure_reservation r USING (reservation_id) "
                    + "WHERE r.state IN ('RESERVED', 'POTENTIALLY_DISCLOSED', "
                    + "'DISCLOSED', 'REVIEW_REQUIRED')",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void differentIndicatorKeyCannotResetSharedDisclosureScopeHistory() {
    UUID broadLineage = UUID.randomUUID();
    activate(policy(1, broadLineage, Map.of("TOTAL", Set.of(broadLineage)), true));
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    Instant end = Instant.parse("2026-02-01T00:00:00Z");
    Optional<UUID> facility = Optional.of(UUID.randomUUID());
    DisclosureScope firstScope = disclosureScope("SYN-INDICATOR-001", 1, facility, start, end);
    DisclosureScope secondScope = disclosureScope("SYN-INDICATOR-002", 1, facility, start, end);

    DisclosureReservation firstRelease =
        service.reserve(
            releaseContext(),
            scopedCandidate(
                UUID.randomUUID(),
                firstScope,
                Map.of("FIRST_INDICATOR_VALUE", 10L),
                new DisclosureOutput("TOTAL", 10, Map.of("FIRST_INDICATOR_VALUE", 1L))));
    DisclosureReservation secondRelease =
        service.reserve(
            releaseContext(),
            scopedCandidate(
                UUID.randomUUID(),
                secondScope,
                Map.of("SECOND_INDICATOR_VALUE", 11L),
                new DisclosureOutput("TOTAL", 11, Map.of("SECOND_INDICATOR_VALUE", 1L))));

    assertThat(firstRelease.state()).isEqualTo(DisclosureReservationState.RESERVED);
    assertThat(secondRelease.state()).isEqualTo(DisclosureReservationState.REVIEW_REQUIRED);
    assertThat(secondRelease.reasonCode()).isEqualTo("SCOPE_OVERLAP_REVIEW_REQUIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analytics.disclosure_reservation_output o "
                    + "JOIN analytics.disclosure_reservation r USING (reservation_id) "
                    + "WHERE r.state IN ('RESERVED', 'POTENTIALLY_DISCLOSED', "
                    + "'DISCLOSED', 'REVIEW_REQUIRED')",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void newIndicatorDefinitionCannotResetSharedDisclosureHistory() {
    UUID broadLineage = UUID.randomUUID();
    activate(policy(1, broadLineage, Map.of("TOTAL", Set.of(broadLineage)), true));
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    Instant end = Instant.parse("2026-02-01T00:00:00Z");
    Optional<UUID> facility = Optional.of(UUID.randomUUID());
    DisclosureScope versionOne = disclosureScope("SYN-INDICATOR-001", 1, facility, start, end);
    DisclosureScope versionTwo = disclosureScope("SYN-INDICATOR-001", 2, facility, start, end);

    DisclosureReservation firstRelease =
        service.reserve(
            releaseContext(),
            scopedCandidate(
                UUID.randomUUID(),
                versionOne,
                Map.of("SUBMITTED_VALUE", 10L),
                new DisclosureOutput("TOTAL", 10, Map.of("SUBMITTED_VALUE", 1L))));
    DisclosureReservation secondRelease =
        service.reserve(
            releaseContext(),
            scopedCandidate(
                UUID.randomUUID(),
                versionTwo,
                Map.of("RESOLVED_VALUE", 9L),
                new DisclosureOutput("TOTAL", 9, Map.of("RESOLVED_VALUE", 1L))));

    assertThat(firstRelease.state()).isEqualTo(DisclosureReservationState.RESERVED);
    assertThat(secondRelease.state()).isEqualTo(DisclosureReservationState.REVIEW_REQUIRED);
    assertThat(secondRelease.reasonCode()).isEqualTo("SCOPE_OVERLAP_REVIEW_REQUIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analytics.disclosure_reservation_output o "
                    + "JOIN analytics.disclosure_reservation r USING (reservation_id) "
                    + "WHERE r.state IN ('RESERVED', 'POTENTIALLY_DISCLOSED', "
                    + "'DISCLOSED', 'REVIEW_REQUIRED')",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void releaseSetChecksCandidateCellsTogetherBeforeReservingAnything() {
    UUID broadLineage = UUID.randomUUID();
    activate(
        policy(
            1,
            broadLineage,
            Map.of("CELL_A", Set.of(broadLineage), "TOTAL", Set.of(broadLineage)),
            true));

    DisclosureReservation decision =
        service.reserve(
            releaseContext(), candidate(UUID.randomUUID(), 1, outputA(), totalOutput()));

    assertThat(decision.state()).isEqualTo(DisclosureReservationState.WITHHELD);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analytics.disclosure_reservation_output", Integer.class))
        .isZero();
  }

  @Test
  void aggregateBelowMinimumCellCountIsWithheldBySharedReservationGate() {
    UUID broadLineage = UUID.randomUUID();
    activate(
        policy(
            1,
            broadLineage,
            Map.of("CELL_A", Set.of(broadLineage), "TOTAL", Set.of(broadLineage)),
            true));
    DisclosureCandidate smallTotal =
        new DisclosureCandidate(
            POLICY_KEY,
            1,
            UUID.randomUUID(),
            Map.of("CELL_A", 1L, "CELL_B", 1L),
            List.of(new DisclosureOutput("TOTAL", 2, Map.of("CELL_A", 1L, "CELL_B", 1L))));

    DisclosureReservation decision = service.reserve(releaseContext(), smallTotal);

    assertThat(decision.state()).isEqualTo(DisclosureReservationState.WITHHELD);
    assertThat(decision.reasonCode()).isEqualTo("INFERENCE_RISK");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analytics.disclosure_reservation "
                    + "WHERE state = 'RESERVED'",
                Integer.class))
        .isZero();
  }

  @Test
  void sensitiveAggregateWithIncompleteExternalBaselineRequiresReviewBeforeReservation() {
    UUID broadLineage = UUID.randomUUID();
    activate(policy(1, broadLineage, Map.of("TOTAL", Set.of(broadLineage)), false));
    DisclosureCandidate highCountSensitiveTotal =
        new DisclosureCandidate(
            POLICY_KEY,
            1,
            UUID.randomUUID(),
            Map.of("CELL_A", 9L, "CELL_B", 8L),
            List.of(new DisclosureOutput("TOTAL", 17, Map.of("CELL_A", 1L, "CELL_B", 1L))));

    DisclosureReservation decision = service.reserve(releaseContext(), highCountSensitiveTotal);

    assertThat(decision.state()).isEqualTo(DisclosureReservationState.REVIEW_REQUIRED);
    assertThat(decision.reasonCode()).isEqualTo("EXTERNAL_BASELINE_UNKNOWN");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analytics.disclosure_reservation "
                    + "WHERE state = 'RESERVED'",
                Integer.class))
        .isZero();
  }

  @Test
  void concurrentComplementaryReleasesSerializeAgainstCommittedHistory() throws Exception {
    UUID broadLineage = UUID.randomUUID();
    activate(
        policy(
            1,
            broadLineage,
            Map.of("CELL_A", Set.of(broadLineage), "TOTAL", Set.of(broadLineage)),
            true));
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<DisclosureReservation> first =
          workers.submit(() -> reserveAfter(start, candidate(UUID.randomUUID(), 1, outputA())));
      Future<DisclosureReservation> second =
          workers.submit(() -> reserveAfter(start, candidate(UUID.randomUUID(), 1, totalOutput())));
      start.countDown();
      List<DisclosureReservation> decisions =
          List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

      assertThat(decisions)
          .extracting(DisclosureReservation::state)
          .containsExactlyInAnyOrder(
              DisclosureReservationState.RESERVED, DisclosureReservationState.WITHHELD);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM analytics.disclosure_reservation "
                      + "WHERE state = 'RESERVED'",
                  Integer.class))
          .isEqualTo(1);
    }
  }

  @Test
  void policyMergeAndSplitKeepStableBroaderHistoryAcrossVersions() {
    UUID broad = UUID.randomUUID();
    UUID lineageA = UUID.randomUUID();
    UUID lineageTotal = UUID.randomUUID();
    activate(
        policy(1, broad, Map.of("CELL_A", Set.of(lineageA), "TOTAL", Set.of(lineageTotal)), true));
    service.reserve(releaseContext(), candidate(UUID.randomUUID(), 1, outputA()));

    UUID merged = UUID.randomUUID();
    DisclosurePolicyVersion v2 =
        service.activatePolicy(
            adminContext(),
            policy(2, broad, Map.of("CELL_A", Set.of(merged), "TOTAL", Set.of(merged)), true));
    assertThat(v2.lineagesFor("CELL_A")).contains(broad, lineageA, merged);
    assertThat(v2.lineagesFor("TOTAL")).contains(broad, lineageTotal, merged);
    assertThat(
            service
                .reserve(releaseContext(), candidate(UUID.randomUUID(), 2, totalOutput()))
                .state())
        .isEqualTo(DisclosureReservationState.WITHHELD);

    UUID splitA = UUID.randomUUID();
    UUID splitTotal = UUID.randomUUID();
    DisclosurePolicyVersion v3 =
        service.activatePolicy(
            adminContext(),
            policy(3, broad, Map.of("CELL_A", Set.of(splitA), "TOTAL", Set.of(splitTotal)), true));
    assertThat(v3.lineagesFor("CELL_A")).contains(broad, lineageA, merged, splitA);
    assertThat(v3.lineagesFor("TOTAL")).contains(broad, lineageTotal, merged, splitTotal);
    assertThat(
            service
                .reserve(releaseContext(), candidate(UUID.randomUUID(), 3, totalOutput()))
                .state())
        .isEqualTo(DisclosureReservationState.WITHHELD);
  }

  @Test
  void policyRolloverRacingAReleaseCannotResetTheBroadLineage() throws Exception {
    UUID broad = UUID.randomUUID();
    activate(policy(1, broad, Map.of("CELL_A", Set.of(broad), "TOTAL", Set.of(broad)), true));
    CountDownLatch start = new CountDownLatch(1);
    UUID nextLineage = UUID.randomUUID();

    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Object> release =
          workers.submit(
              () -> {
                await(start);
                try {
                  return service.reserve(
                      releaseContext(), candidate(UUID.randomUUID(), 1, outputA()));
                } catch (DisclosureReservationException changedDuringRollover) {
                  return changedDuringRollover;
                }
              });
      Future<DisclosurePolicyVersion> activation =
          workers.submit(
              () -> {
                await(start);
                return service.activatePolicy(
                    adminContext(),
                    policy(
                        2,
                        broad,
                        Map.of("CELL_A", Set.of(nextLineage), "TOTAL", Set.of(nextLineage)),
                        true));
              });
      start.countDown();
      Object releaseResult = release.get(30, TimeUnit.SECONDS);
      activation.get(30, TimeUnit.SECONDS);

      assertThat(releaseResult)
          .isInstanceOfAny(DisclosureReservation.class, DisclosureReservationException.class);
      DisclosureReservation current =
          service.reserve(
              releaseContext(), candidate(UUID.randomUUID(), 2, outputA(), totalOutput()));
      assertThat(current.state()).isEqualTo(DisclosureReservationState.WITHHELD);
    }
  }

  @Test
  void reviewApprovalAndPolicyRolloverCompleteWithoutLockOrderDeadlock() throws Exception {
    UUID broad = UUID.randomUUID();
    Map<String, Set<UUID>> lineages = Map.of("CELL_B", Set.of(broad));
    activate(policy(1, broad, lineages, false));
    DisclosureReservation review =
        service.reserve(releaseContext(), candidate(UUID.randomUUID(), 1, outputB()));
    assertThat(review.state()).isEqualTo(DisclosureReservationState.REVIEW_REQUIRED);

    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Object> approval =
          workers.submit(
              () -> {
                await(start);
                try {
                  return service.resolveReviewRequired(
                      adminContext(),
                      review.reservationId(),
                      review.operationFingerprint(),
                      DisclosureReviewResolution.APPROVE,
                      UUID.randomUUID());
                } catch (DisclosureReservationException policyChangedFirst) {
                  return policyChangedFirst;
                }
              });
      Future<DisclosurePolicyVersion> activation =
          workers.submit(
              () -> {
                await(start);
                return service.activatePolicy(adminContext(), policy(2, broad, lineages, true));
              });
      start.countDown();

      Object approvalResult = approval.get(20, TimeUnit.SECONDS);
      assertThat(activation.get(20, TimeUnit.SECONDS).version()).isEqualTo(2);
      if (approvalResult instanceof DisclosureReservationException conflict) {
        assertThat(conflict.reason())
            .isEqualTo(DisclosureReservationException.Reason.POLICY_VERSION_CONFLICT);
      } else {
        assertThat(((DisclosureReservation) approvalResult).state())
            .isEqualTo(DisclosureReservationState.RESERVED);
      }
    }
  }

  @Test
  void aNewPolicyKeyForTheSameOrganizationCannotResetDisclosureHistory() {
    UUID broadLineage = UUID.randomUUID();
    activate(
        policy(
            POLICY_KEY,
            1,
            broadLineage,
            Map.of("CELL_A", Set.of(broadLineage), "TOTAL", Set.of(broadLineage)),
            true));
    service.reserve(releaseContext(), candidate(UUID.randomUUID(), 1, outputA()));

    String nextPolicyKey = "SYN-REPORTING-SCOPE-B";
    activate(
        policy(
            nextPolicyKey,
            1,
            broadLineage,
            Map.of("CELL_A", Set.of(broadLineage), "TOTAL", Set.of(broadLineage)),
            true));

    DisclosureReservation relatedOutput =
        service.reserve(
            releaseContext(), candidate(nextPolicyKey, UUID.randomUUID(), 1, totalOutput()));

    assertThat(relatedOutput.state()).isEqualTo(DisclosureReservationState.WITHHELD);
    assertThat(relatedOutput.reasonCode()).isEqualTo("INFERENCE_RISK");
  }

  @Test
  void concurrentReleasesAcrossPolicyKeysSerializeOnTheOrganizationLineage() throws Exception {
    UUID broadLineage = UUID.randomUUID();
    activate(
        policy(
            POLICY_KEY,
            1,
            broadLineage,
            Map.of("CELL_A", Set.of(broadLineage), "TOTAL", Set.of(broadLineage)),
            true));
    String secondPolicyKey = "SYN-REPORTING-SCOPE-D";
    activate(
        policy(
            secondPolicyKey,
            1,
            broadLineage,
            Map.of("CELL_A", Set.of(broadLineage), "TOTAL", Set.of(broadLineage)),
            true));
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<DisclosureReservation> first =
          workers.submit(() -> reserveAfter(start, candidate(UUID.randomUUID(), 1, outputA())));
      Future<DisclosureReservation> second =
          workers.submit(
              () ->
                  reserveAfter(
                      start, candidate(secondPolicyKey, UUID.randomUUID(), 1, totalOutput())));
      start.countDown();
      List<DisclosureReservation> results =
          List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

      assertThat(results)
          .extracting(DisclosureReservation::state)
          .containsExactlyInAnyOrder(
              DisclosureReservationState.RESERVED, DisclosureReservationState.WITHHELD);
    }
  }

  @Test
  void aNewPolicyKeyCannotIntroduceANewConservativeLineageForTheSameOrganization() {
    UUID originalLineage = UUID.randomUUID();
    activate(
        policy(POLICY_KEY, 1, originalLineage, Map.of("CELL_A", Set.of(originalLineage)), true));
    UUID unrelatedLineage = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                activate(
                    policy(
                        "SYN-REPORTING-SCOPE-C",
                        1,
                        unrelatedLineage,
                        Map.of("CELL_A", Set.of(unrelatedLineage)),
                        true)))
        .isInstanceOf(DisclosureReservationException.class)
        .extracting("reason")
        .isEqualTo(DisclosureReservationException.Reason.LINEAGE_INVALID);
  }

  @Test
  void unknownReleaseRemainsPotentiallyDisclosedAcrossServiceRestart() {
    UUID broad = UUID.randomUUID();
    activate(policy(1, broad, Map.of("CELL_A", Set.of(broad), "TOTAL", Set.of(broad)), true));
    DisclosureReservation reserved =
        service.reserve(releaseContext(), candidate(UUID.randomUUID(), 1, outputA()));
    DisclosureReservation unknown =
        service.markPotentiallyDisclosed(releaseContext(), reserved.reservationId());
    service = newService(noSendEvidence);

    DisclosureReservation next =
        service.reserve(releaseContext(), candidate(UUID.randomUUID(), 1, totalOutput()));

    assertThat(unknown.state()).isEqualTo(DisclosureReservationState.POTENTIALLY_DISCLOSED);
    assertThat(service.findReservation(releaseContext(), reserved.reservationId()).state())
        .isEqualTo(DisclosureReservationState.POTENTIALLY_DISCLOSED);
    assertThat(next.state()).isEqualTo(DisclosureReservationState.WITHHELD);
  }

  @Test
  void inconsistentLineageHistoryRequiresReviewInsteadOfRelease() {
    UUID broad = UUID.randomUUID();
    activate(policy(1, broad, Map.of("CELL_A", Set.of(broad)), true));
    DisclosureCandidate prior =
        new DisclosureCandidate(
            POLICY_KEY,
            1,
            UUID.randomUUID(),
            Map.of("CELL_A", 8L),
            List.of(new DisclosureOutput("CELL_A", 8, Map.of("CELL_A", 1L))));
    service.recordKnownExternalBaseline(adminContext(), prior);

    DisclosureReservation result =
        service.reserve(releaseContext(), candidate(UUID.randomUUID(), 1, outputA()));

    assertThat(result.state()).isEqualTo(DisclosureReservationState.REVIEW_REQUIRED);
    assertThat(result.reasonCode()).isEqualTo("INFERENCE_REVIEW_REQUIRED");
  }

  @Test
  void privacyReviewerCanResolveReviewWithEvidenceAndSharedGateRetainsReleaseHistory() {
    UUID broad = UUID.randomUUID();
    activate(policy(1, broad, Map.of("CELL_B", Set.of(broad)), false));
    DisclosureCandidate lowCount = candidate(UUID.randomUUID(), 1, outputB());
    DisclosureReservation review = service.reserve(releaseContext(), lowCount);

    assertThat(review.state()).isEqualTo(DisclosureReservationState.REVIEW_REQUIRED);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analytics.disclosure_reservation_output "
                    + "WHERE reservation_id = ?",
                Integer.class,
                review.reservationId()))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                service.resolveReviewRequired(
                    releaseContext(),
                    review.reservationId(),
                    review.operationFingerprint(),
                    DisclosureReviewResolution.APPROVE,
                    UUID.randomUUID()))
        .isInstanceOf(DisclosureReservationException.class)
        .extracting(error -> ((DisclosureReservationException) error).reason())
        .isEqualTo(DisclosureReservationException.Reason.NOT_AUTHORIZED);
    assertThat(service.findReservation(releaseContext(), review.reservationId()).state())
        .isEqualTo(DisclosureReservationState.REVIEW_REQUIRED);

    UUID evidence = UUID.randomUUID();
    DisclosureReservation approved =
        service.resolveReviewRequired(
            adminContext(),
            review.reservationId(),
            review.operationFingerprint(),
            DisclosureReviewResolution.APPROVE,
            evidence);
    assertThat(approved.state()).isEqualTo(DisclosureReservationState.RESERVED);
    assertThat(approved.reasonCode()).isEqualTo("AUTHORIZED_REVIEW_APPROVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT evidence_reference FROM analytics.disclosure_reservation_review "
                    + "WHERE reservation_id = ?",
                UUID.class,
                review.reservationId()))
        .isEqualTo(evidence);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_record WHERE action_code = ? "
                    + "AND target_id = ?",
                Integer.class,
                "ANALYTICS_DISCLOSURE_AUTHORIZED_REVIEW_APPROVED",
                review.reservationId()))
        .isEqualTo(1);

    DisclosureReservation replay = service.reserve(releaseContext(), lowCount);
    assertThat(replay.state()).isEqualTo(DisclosureReservationState.RESERVED);
    assertThat(replay.replayed()).isTrue();

    activate(policy(2, broad, Map.of("CELL_B", Set.of(broad)), true));
    DisclosureReservation exactRepeat =
        service.reserve(releaseContext(), candidate(UUID.randomUUID(), 2, outputB()));
    assertThat(exactRepeat.state()).isEqualTo(DisclosureReservationState.RESERVED);
  }

  @Test
  void privacyReviewerMayWithholdButCannotResolveAChangedFingerprint() {
    UUID broad = UUID.randomUUID();
    activate(policy(1, broad, Map.of("CELL_B", Set.of(broad)), false));
    DisclosureCandidate proposed = candidate(UUID.randomUUID(), 1, outputB());
    DisclosureReservation review = service.reserve(releaseContext(), proposed);

    assertThatThrownBy(
            () ->
                service.resolveReviewRequired(
                    adminContext(),
                    review.reservationId(),
                    "0".repeat(64),
                    DisclosureReviewResolution.WITHHOLD,
                    UUID.randomUUID()))
        .isInstanceOf(DisclosureReservationException.class);
    assertThat(
            service
                .resolveReviewRequired(
                    adminContext(),
                    review.reservationId(),
                    review.operationFingerprint(),
                    DisclosureReviewResolution.WITHHOLD,
                    UUID.randomUUID())
                .state())
        .isEqualTo(DisclosureReservationState.WITHHELD);
  }

  @Test
  void newZeroEquationIsWithheldWhenKnownBaselineDoesNotMatchAfterPolicyRollover() {
    UUID broad = UUID.randomUUID();
    Map<String, Set<UUID>> lineages =
        Map.of("DENOMINATOR", Set.of(broad), "ZERO_RATIO", Set.of(broad));
    activate(policy(1, broad, lineages, true));
    DisclosureCandidate knownExternalDenominator =
        new DisclosureCandidate(
            POLICY_KEY,
            1,
            UUID.randomUUID(),
            Map.of("NUMERATOR_A", 0L, "NUMERATOR_B", 0L, "DENOMINATOR", 12L),
            List.of(new DisclosureOutput("DENOMINATOR", 12, Map.of("DENOMINATOR", 1L))));
    service.recordKnownExternalBaseline(adminContext(), knownExternalDenominator);

    activate(policy(2, broad, lineages, false));
    DisclosureCandidate candidate = zeroRatioCandidate(UUID.randomUUID(), 2);
    DisclosureReservation result = service.reserve(releaseContext(), candidate);
    assertThat(result.state()).isEqualTo(DisclosureReservationState.WITHHELD);
    assertThat(result.reasonCode()).isEqualTo("INFERENCE_RISK");
  }

  @Test
  void reviewApprovalAcceptsAnExactZeroEquationAlreadyDisclosed() {
    UUID broad = UUID.randomUUID();
    Map<String, Set<UUID>> lineages = Map.of("ZERO", Set.of(broad));
    activate(new DisclosurePolicyVersion(POLICY_KEY, 1, ORGANIZATION, 5, true, broad, lineages));
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    Instant end = Instant.parse("2026-02-01T00:00:00Z");
    DisclosureOutput zero = new DisclosureOutput("ZERO", 0, Map.of("CELL_A", 1L));
    DisclosureCandidate organizationRelease =
        new DisclosureCandidate(
            POLICY_KEY,
            1,
            UUID.randomUUID(),
            Map.of("CELL_A", 0L),
            List.of(zero),
            disclosureScope("SYN-INDICATOR-001", 1, Optional.empty(), start, end));
    service.recordKnownExternalBaseline(adminContext(), organizationRelease);

    service.activatePolicy(
        adminContext(),
        new DisclosurePolicyVersion(POLICY_KEY, 2, ORGANIZATION, 5, true, broad, lineages));
    DisclosureCandidate facilityRepeat =
        new DisclosureCandidate(
            POLICY_KEY,
            2,
            UUID.randomUUID(),
            Map.of("CELL_A", 0L),
            List.of(zero),
            disclosureScope("SYN-INDICATOR-001", 1, Optional.of(UUID.randomUUID()), start, end));
    DisclosureReservation review = service.reserve(releaseContext(), facilityRepeat);
    assertThat(review.state()).isEqualTo(DisclosureReservationState.REVIEW_REQUIRED);

    DisclosureReservation approved =
        service.resolveReviewRequired(
            adminContext(),
            review.reservationId(),
            review.operationFingerprint(),
            DisclosureReviewResolution.APPROVE,
            UUID.randomUUID());

    assertThat(approved.state()).isEqualTo(DisclosureReservationState.RESERVED);
    assertThat(approved.reasonCode()).isEqualTo("AUTHORIZED_REVIEW_APPROVED");
  }

  @Test
  void pendingReviewIsIncludedWhenEvaluatingLaterRelatedReleases() {
    UUID broad = UUID.randomUUID();
    activate(policy(1, broad, Map.of("TOTAL", Set.of(broad), "CELL_A", Set.of(broad)), false));
    DisclosureScope scope =
        disclosureScope(
            "SYN-INDICATOR-001",
            1,
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-02-01T00:00:00Z"));
    DisclosureCandidate pendingTotal =
        new DisclosureCandidate(
            POLICY_KEY,
            1,
            UUID.randomUUID(),
            Map.of("CELL_A", 9L, "CELL_B", 1L),
            List.of(new DisclosureOutput("TOTAL", 10, Map.of("CELL_A", 1L, "CELL_B", 1L))),
            scope);

    DisclosureReservation review = service.reserve(releaseContext(), pendingTotal);
    assertThat(review.state()).isEqualTo(DisclosureReservationState.REVIEW_REQUIRED);

    DisclosureCandidate relatedHighCell =
        new DisclosureCandidate(
            POLICY_KEY,
            1,
            UUID.randomUUID(),
            Map.of("CELL_A", 9L),
            List.of(new DisclosureOutput("CELL_A", 9, Map.of("CELL_A", 1L))),
            org.ayuvahak.analytics.domain.IndicatorDefinitionGovernance.SensitivityClassification
                .LOW_SENSITIVITY_AGGREGATE,
            scope);

    DisclosureReservation later = service.reserve(releaseContext(), relatedHighCell);

    assertThat(later.state()).isEqualTo(DisclosureReservationState.REVIEW_REQUIRED);
    assertThat(later.reasonCode()).isEqualTo("EXTERNAL_BASELINE_UNKNOWN");
    assertThat(
            service
                .resolveReviewRequired(
                    adminContext(),
                    review.reservationId(),
                    review.operationFingerprint(),
                    DisclosureReviewResolution.APPROVE,
                    UUID.randomUUID())
                .state())
        .isEqualTo(DisclosureReservationState.RESERVED);
  }

  @Test
  void signedRatioDisclosureEquationSurvivesPostgresAndProtectsSmallSourceCell() {
    UUID broad = UUID.randomUUID();
    activate(policy(1, broad, Map.of("RATIO", Set.of(broad), "DENOMINATOR", Set.of(broad)), true));
    Map<String, Long> cells = Map.of("SYN-NUMERATOR", 3L, "SYN-DENOMINATOR", 9L);
    DisclosureOutput ratio =
        new DisclosureOutput(
            "RATIO", 333_333L, Map.of("SYN-NUMERATOR", 3L, "SYN-DENOMINATOR", -1L), 0L, false);
    DisclosureReservation first =
        service.reserve(
            releaseContext(),
            new DisclosureCandidate(POLICY_KEY, 1, UUID.randomUUID(), cells, List.of(ratio)));
    assertThat(first.state()).isEqualTo(DisclosureReservationState.RESERVED);

    DisclosureReservation denominatorRelease =
        service.reserve(
            releaseContext(),
            new DisclosureCandidate(
                POLICY_KEY,
                1,
                UUID.randomUUID(),
                cells,
                List.of(new DisclosureOutput("DENOMINATOR", 9L, Map.of("SYN-DENOMINATOR", 1L)))));
    assertThat(denominatorRelease.state()).isEqualTo(DisclosureReservationState.WITHHELD);
    assertThat(denominatorRelease.reasonCode()).isEqualTo("INFERENCE_RISK");
  }

  @Test
  void externalBaselineAndIncompleteBaselineAreConservative() {
    UUID broad = UUID.randomUUID();
    activate(policy(1, broad, Map.of("CELL_A", Set.of(broad), "TOTAL", Set.of(broad)), true));
    service.recordKnownExternalBaseline(adminContext(), candidate(UUID.randomUUID(), 1, outputA()));

    assertThat(
            service
                .reserve(releaseContext(), candidate(UUID.randomUUID(), 1, totalOutput()))
                .state())
        .isEqualTo(DisclosureReservationState.WITHHELD);

    UUID anotherBroad = UUID.randomUUID();
    activate(
        policy(
            2,
            broad,
            Map.of("CELL_A", Set.of(anotherBroad), "TOTAL", Set.of(anotherBroad)),
            false));
    assertThat(
            service
                .reserve(releaseContext(), candidate(UUID.randomUUID(), 2, totalOutput()))
                .state())
        .isEqualTo(DisclosureReservationState.REVIEW_REQUIRED);
  }

  @Test
  void sameOperationReplaysButChangedContentConflicts() {
    UUID broad = UUID.randomUUID();
    activate(policy(1, broad, Map.of("CELL_A", Set.of(broad), "TOTAL", Set.of(broad)), true));
    UUID operation = UUID.randomUUID();
    DisclosureCandidate first = candidate(operation, 1, outputA());
    DisclosureReservation reserved = service.reserve(releaseContext(), first);
    DisclosureReservation replay = service.reserve(releaseContext(), first);

    assertThat(replay.reservationId()).isEqualTo(reserved.reservationId());
    assertThat(replay.replayed()).isTrue();
    assertThatThrownBy(
            () -> service.reserve(releaseContext(), candidate(operation, 1, totalOutput())))
        .isInstanceOf(DisclosureReservationException.class);
  }

  @Test
  void reservationCanBeWithdrawnOnlyWithVerifiedNoBytesSentEvidence() {
    UUID broad = UUID.randomUUID();
    activate(policy(1, broad, Map.of("CELL_A", Set.of(broad), "TOTAL", Set.of(broad)), true));
    DisclosureReservation reservation =
        service.reserve(releaseContext(), candidate(UUID.randomUUID(), 1, outputA()));
    UUID attemptId = UUID.randomUUID();

    assertThatThrownBy(
            () -> service.withdrawNotSent(releaseContext(), reservation.reservationId(), attemptId))
        .isInstanceOf(DisclosureReservationException.class);
    assertThat(service.findReservation(releaseContext(), reservation.reservationId()).state())
        .isEqualTo(DisclosureReservationState.RESERVED);

    noSendEvidence.prove(attemptId, reservation.reservationId());
    assertThat(
            service
                .withdrawNotSent(releaseContext(), reservation.reservationId(), attemptId)
                .state())
        .isEqualTo(DisclosureReservationState.WITHDRAWN_NOT_SENT);
  }

  private static DisclosureReservation reserveAfter(
      CountDownLatch start, DisclosureCandidate candidate) throws InterruptedException {
    await(start);
    return service.reserve(releaseContext(), candidate);
  }

  private static void await(CountDownLatch start) throws InterruptedException {
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("test workers did not start");
    }
  }

  private static void activate(DisclosurePolicyVersion policy) {
    service.activatePolicy(adminContext(), policy);
  }

  private static AnalyticsDisclosureReservationService newService(NoSendEvidenceVerifier verifier) {
    AuthorizationGrantLookup grants =
        (actor, authority, scope) -> AUTHORITIES.contains(authority.code());
    AuthorizationService authorization =
        new AuthorizationService(
            grants,
            new FunctionalAuthorityCatalog(
                List.of(
                    AnalyticsAuthorities.disclosurePolicyAdministration(),
                    AnalyticsAuthorities.disclosureRelease(),
                    AnalyticsAuthorities.disclosureReservationLifecycle(),
                    AnalyticsAuthorities.disclosureReview())));
    AuditWriter audit =
        new AuditWriter(new JdbcAuditRecordAppender(jdbc, transactionManager), Clock.systemUTC());
    return new AnalyticsDisclosureReservationService(
        new JdbcDisclosureReservationStore(jdbc),
        authorization,
        audit,
        transactionManager,
        clock,
        verifier);
  }

  private static ExecutionContext releaseContext() {
    return contexts.forBackgroundWork(
        ANALYTICS,
        RequestedExecutionScope.organization(ORGANIZATION),
        new CorrelationId(UUID.randomUUID().toString()),
        Optional.empty());
  }

  private static ExecutionContext adminContext() {
    return contexts.forInteractiveRequest(
        ADMIN,
        RequestedExecutionScope.organization(ORGANIZATION),
        new CorrelationId(UUID.randomUUID().toString()));
  }

  private static DisclosurePolicyVersion policy(
      int version,
      UUID conservativeLineage,
      Map<String, Set<UUID>> lineages,
      boolean externalBaselineComplete) {
    return policy(POLICY_KEY, version, conservativeLineage, lineages, externalBaselineComplete);
  }

  private static DisclosurePolicyVersion policy(
      String policyKey,
      int version,
      UUID conservativeLineage,
      Map<String, Set<UUID>> lineages,
      boolean externalBaselineComplete) {
    return new DisclosurePolicyVersion(
        policyKey,
        version,
        ORGANIZATION,
        5,
        externalBaselineComplete,
        conservativeLineage,
        lineages);
  }

  private static DisclosureCandidate candidate(
      UUID operationId, int policyVersion, DisclosureOutput... outputs) {
    return candidate(POLICY_KEY, operationId, policyVersion, outputs);
  }

  private static DisclosureCandidate candidate(
      String policyKey, UUID operationId, int policyVersion, DisclosureOutput... outputs) {
    List<DisclosureOutput> outputList = new ArrayList<>(List.of(outputs));
    return new DisclosureCandidate(
        policyKey, policyVersion, operationId, Map.of("CELL_A", 9L, "CELL_B", 1L), outputList);
  }

  private static DisclosureCandidate zeroRatioCandidate(UUID operationId, int policyVersion) {
    return new DisclosureCandidate(
        POLICY_KEY,
        policyVersion,
        operationId,
        Map.of("NUMERATOR_A", 0L, "NUMERATOR_B", 0L, "DENOMINATOR", 12L),
        List.of(
            new DisclosureOutput(
                "ZERO_RATIO", 0, Map.of("NUMERATOR_A", 1L, "NUMERATOR_B", 1L), 0, false)));
  }

  private static DisclosureCandidate scopedCandidate(
      UUID operationId, DisclosureScope scope, Map<String, Long> cells, DisclosureOutput output) {
    return new DisclosureCandidate(POLICY_KEY, 1, operationId, cells, List.of(output), scope);
  }

  private static DisclosureScope disclosureScope(
      String indicatorKey,
      int indicatorVersion,
      Optional<UUID> facilityId,
      Instant start,
      Instant end) {
    return new DisclosureScope(
        indicatorKey,
        indicatorVersion,
        ORGANIZATION.issuer().value(),
        UUID.fromString(ORGANIZATION.value()),
        facilityId,
        start,
        end);
  }

  private static DisclosureOutput outputA() {
    return new DisclosureOutput("CELL_A", 9, Map.of("CELL_A", 1L));
  }

  private static DisclosureOutput outputB() {
    return new DisclosureOutput("CELL_B", 1, Map.of("CELL_B", 1L));
  }

  private static DisclosureOutput totalOutput() {
    return new DisclosureOutput("TOTAL", 10, Map.of("CELL_A", 1L, "CELL_B", 1L));
  }

  private static final class TestNoSendEvidenceVerifier implements NoSendEvidenceVerifier {
    private final java.util.Set<String> proofs = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public boolean provesNoRequestBytesCouldHaveLeft(UUID reservationId, UUID outboundAttemptId) {
      return proofs.contains(reservationId + ":" + outboundAttemptId);
    }

    void prove(UUID attemptId, UUID reservationId) {
      proofs.add(reservationId + ":" + attemptId);
    }

    void clear() {
      proofs.clear();
    }
  }

  private static final class MutableDisclosureClock extends Clock {
    private volatile Instant now;

    private MutableDisclosureClock(Instant now) {
      this.now = now;
    }

    void set(Instant instant) {
      now = instant;
    }

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
