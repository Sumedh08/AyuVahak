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
          AnalyticsAuthorities.disclosureReservationLifecycle().code());

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
                    AnalyticsAuthorities.disclosureReservationLifecycle())));
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

  private static DisclosureOutput outputA() {
    return new DisclosureOutput("CELL_A", 9, Map.of("CELL_A", 1L));
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
