package org.ayuvahak.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.analytics.adapter.persistence.JdbcAnalyticsRevisionStore;
import org.ayuvahak.analytics.adapter.persistence.JdbcAnalyticsStore;
import org.ayuvahak.analytics.domain.AggregateCompleteness;
import org.ayuvahak.analytics.domain.AggregateInput;
import org.ayuvahak.analytics.domain.AggregateSnapshot;
import org.ayuvahak.analytics.domain.AggregateSnapshotRevision;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.analytics.domain.AnalyticsFinalizationReason;
import org.ayuvahak.analytics.domain.AnalyticsRevisionCause;
import org.ayuvahak.analytics.domain.IndicatorDefinitionVersion;
import org.ayuvahak.analytics.domain.SourceMeasure;
import org.ayuvahak.platform.audit.adapter.persistence.JdbcAuditRecordAppender;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.authorization.application.AuthorizationGrantLookup;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
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

/**
 * Verifies immutable revision, finalization, audit, and concurrency behavior in local PostgreSQL.
 */
@Testcontainers
class AnalyticsSnapshotRevisionPostgresIT {

  private static final String PASSWORD = "local-test-only";
  private static final IssuerId ISSUER = new IssuerId("https://identity.example.test/realm");
  private static final ActorReference ANALYTICS =
      ActorReference.service(ISSUER, "synthetic-analytics-reporting");
  private static final ActorReference OPERATOR =
      ActorReference.human(ISSUER, "synthetic-analytics-operator");
  private static final ExternalReference ORGANIZATION =
      new ExternalReference(
          new IssuerId("urn:ayuvahak:directory"), "Organization", UUID.randomUUID().toString());
  private static final SourceMeasure MEASURE = SourceMeasure.referral("SUBMITTED_COUNT");
  private static final ZoneId UTC = ZoneOffset.UTC;
  private static final Duration CORRECTION_WINDOW = Duration.ofDays(30);

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
  private static AnalyticsStore analyticsStore;
  private static JdbcAnalyticsRevisionStore revisionStore;
  private static ExecutionContextFactory contexts;
  private static MutableClock clock;
  private static AnalyticsSnapshotRevisionService service;

  @BeforeAll
  static void startLocalDatabaseAndMigrate() {
    POSTGRES.start();
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbc = new JdbcTemplate(dataSource);
    transactionManager = new DataSourceTransactionManager(dataSource);
    analyticsStore = new JdbcAnalyticsStore(jdbc, transactionManager);
    revisionStore = new JdbcAnalyticsRevisionStore(jdbc);
    contexts = new ExecutionContextFactory((actor, scope) -> actor != null && scope != null);
    clock = new MutableClock(Instant.parse("2026-04-01T12:00:00Z"));
    service = service(allowedAuthorities());
  }

  @AfterAll
  static void stopLocalDatabase() {
    POSTGRES.stop();
  }

  @BeforeEach
  void clearSyntheticAnalyticsRows() {
    jdbc.update("DELETE FROM analytics.aggregate_snapshot_finalization");
    jdbc.update("DELETE FROM analytics.aggregate_snapshot_revision");
    jdbc.update("DELETE FROM analytics.aggregate_snapshot_series");
    jdbc.update("DELETE FROM analytics.aggregate_snapshot_input");
    jdbc.update("DELETE FROM analytics.aggregate_snapshot");
    jdbc.update("DELETE FROM analytics.indicator_definition_measure");
    jdbc.update("DELETE FROM analytics.indicator_definition_version");
    jdbc.update("DELETE FROM audit.audit_record WHERE action_code LIKE 'ANALYTICS_SNAPSHOT_%'");
    clock.set(Instant.parse("2026-04-01T12:00:00Z"));
    analyticsStore.publishDefinition(definition());
  }

  @Test
  void lateOwnerFactsAppendAnImmutableRevisionAndExactCorrectionIdentityReplays() {
    AggregateSnapshot first = snapshot(4, Instant.parse("2026-03-31T00:00:00Z"));
    AggregateSnapshot late = snapshot(7, Instant.parse("2026-04-02T00:00:00Z"));
    persist(first);
    persist(late);

    AggregateSnapshotRevision revision1 =
        service.registerInitial(
            serviceContext(),
            new RegisterAggregateSnapshotRequest(UUID.randomUUID(), first.snapshotId()));
    clock.set(Instant.parse("2026-04-03T12:00:00Z"));
    UUID correctionId = UUID.randomUUID();
    var request =
        new AppendAggregateSnapshotRevisionRequest(
            correctionId,
            first.snapshotId(),
            revision1.revisionNumber(),
            late.snapshotId(),
            AnalyticsRevisionCause.LATE_OWNER_FACT);

    AggregateSnapshotRevision revision2 = service.appendRevision(serviceContext(), request);
    AggregateSnapshotRevision replay = service.appendRevision(serviceContext(), request);

    assertThat(revision2.revisionNumber()).isEqualTo(2);
    assertThat(revision2.snapshotId()).isEqualTo(late.snapshotId());
    assertThat(revision2.previousSnapshotId()).contains(first.snapshotId());
    assertThat(revision2.latestSourceCutoff()).contains(Instant.parse("2026-04-02T00:00:00Z"));
    assertThat(revision2.state()).isEqualTo(AggregateSnapshotRevision.State.PROVISIONAL);
    assertThat(replay.revisionNumber()).isEqualTo(2);
    assertThat(replay.snapshotId()).isEqualTo(revision2.snapshotId());
    assertThat(replay.replayed()).isTrue();
    assertThat(analyticsStore.findSnapshotById(first.snapshotId()).orElseThrow().value())
        .hasValue(4L);
    assertThat(analyticsStore.findSnapshotById(late.snapshotId()).orElseThrow().value())
        .hasValue(7L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analytics.aggregate_snapshot_revision", Integer.class))
        .isEqualTo(2);
    assertThat(revision2.auditRecordId()).isPresent();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_record WHERE action_code = 'ANALYTICS_SNAPSHOT_REVISED'",
                Integer.class))
        .isEqualTo(1);

    AggregateSnapshot changed = snapshot(8, Instant.parse("2026-04-03T00:00:00Z"));
    persist(changed);
    var reusedIdentity =
        new AppendAggregateSnapshotRevisionRequest(
            correctionId,
            first.snapshotId(),
            revision1.revisionNumber(),
            changed.snapshotId(),
            AnalyticsRevisionCause.LATE_OWNER_FACT);
    assertThatThrownBy(() -> service.appendRevision(serviceContext(), reusedIdentity))
        .isInstanceOf(AnalyticsSnapshotRevisionException.class)
        .hasMessageContaining("operation identity");
  }

  @Test
  void finalizationRequiresCompleteSnapshotAndReopeningRequiresAuthorizedCause() {
    AggregateSnapshot first = snapshot(4, Instant.parse("2026-03-31T00:00:00Z"));
    AggregateSnapshot late = snapshot(7, Instant.parse("2026-04-02T00:00:00Z"));
    AggregateSnapshot partial = partialSnapshot();
    persist(first);
    persist(late);
    persist(partial);
    AggregateSnapshotRevision initial =
        service.registerInitial(
            serviceContext(),
            new RegisterAggregateSnapshotRequest(UUID.randomUUID(), first.snapshotId()));
    service.registerInitial(
        serviceContext(),
        new RegisterAggregateSnapshotRequest(UUID.randomUUID(), partial.snapshotId()));

    assertThatThrownBy(
            () ->
                service.finalizeSnapshot(
                    operatorContext(),
                    new FinalizeAggregateSnapshotRequest(
                        UUID.randomUUID(),
                        partial.snapshotId(),
                        1,
                        AnalyticsFinalizationReason.PERIOD_CLOSED)))
        .isInstanceOf(AnalyticsSnapshotRevisionException.class)
        .hasMessageContaining("complete");

    var finalization =
        service.finalizeSnapshot(
            operatorContext(),
            new FinalizeAggregateSnapshotRequest(
                UUID.randomUUID(),
                first.snapshotId(),
                1,
                AnalyticsFinalizationReason.PERIOD_CLOSED));
    clock.set(Instant.parse("2026-04-03T12:00:00Z"));
    assertThat(service.currentRevision(operatorContext(), first.snapshotId()).orElseThrow().state())
        .isEqualTo(AggregateSnapshotRevision.State.FINALIZED);
    assertThatThrownBy(
            () ->
                service(Set.of(AnalyticsAuthorities.lateAggregateRevision().code()))
                    .appendRevision(
                        serviceContext(),
                        new AppendAggregateSnapshotRevisionRequest(
                            UUID.randomUUID(),
                            first.snapshotId(),
                            initial.revisionNumber(),
                            late.snapshotId(),
                            AnalyticsRevisionCause.LATE_OWNER_FACT)))
        .isInstanceOf(AnalyticsSnapshotRevisionException.class)
        .hasMessageContaining("authorized correction");

    AggregateSnapshotRevision corrected =
        service.appendRevision(
            operatorContext(),
            new AppendAggregateSnapshotRevisionRequest(
                UUID.randomUUID(),
                first.snapshotId(),
                initial.revisionNumber(),
                late.snapshotId(),
                AnalyticsRevisionCause.OWNER_RESTATEMENT));

    assertThat(corrected.revisionNumber()).isEqualTo(2);
    assertThat(corrected.previousSnapshotId()).contains(first.snapshotId());
    assertThat(corrected.state()).isEqualTo(AggregateSnapshotRevision.State.PROVISIONAL);
    assertThat(analyticsStore.findSnapshotById(first.snapshotId()).orElseThrow().value())
        .hasValue(4L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_record WHERE action_code IN "
                    + "('ANALYTICS_SNAPSHOT_FINALIZED', 'ANALYTICS_SNAPSHOT_CORRECTED')",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_record WHERE target_type = 'AggregateSnapshotSeries' "
                    + "AND reason_code IS NOT NULL AND reason_code !~ '^[A-Z][A-Z0-9_]{0,79}$'",
                Integer.class))
        .isZero();
  }

  @Test
  void lateSourceRevisionOutsideConfiguredWindowNeedsExplicitCorrectionAuthority() {
    AggregateSnapshot first = snapshot(4, Instant.parse("2026-03-31T00:00:00Z"));
    AggregateSnapshot late = snapshot(7, Instant.parse("2026-04-02T00:00:00Z"));
    persist(first);
    persist(late);
    AggregateSnapshotRevision initial =
        service.registerInitial(
            serviceContext(),
            new RegisterAggregateSnapshotRequest(UUID.randomUUID(), first.snapshotId()));
    clock.set(Instant.parse("2026-05-02T12:00:00Z"));
    var request =
        new AppendAggregateSnapshotRevisionRequest(
            UUID.randomUUID(),
            first.snapshotId(),
            initial.revisionNumber(),
            late.snapshotId(),
            AnalyticsRevisionCause.LATE_OWNER_FACT);

    assertThatThrownBy(
            () ->
                service(Set.of(AnalyticsAuthorities.lateAggregateRevision().code()))
                    .appendRevision(serviceContext(), request))
        .isInstanceOf(AnalyticsSnapshotRevisionException.class)
        .hasMessageContaining("authorized correction");

    AggregateSnapshotRevision corrected =
        service(Set.of(AnalyticsAuthorities.aggregateSnapshotCorrection().code()))
            .appendRevision(operatorContext(), request);
    assertThat(corrected.revisionNumber()).isEqualTo(2);
    assertThat(corrected.authorizedCorrection()).isTrue();
    assertThat(corrected.state()).isEqualTo(AggregateSnapshotRevision.State.PROVISIONAL);
  }

  @Test
  void concurrentCorrectionsSerializeAndTheCommittedRevisionSurvivesStoreReconstruction()
      throws Exception {
    AggregateSnapshot first = snapshot(4, Instant.parse("2026-03-31T00:00:00Z"));
    AggregateSnapshot candidateA = snapshot(7, Instant.parse("2026-04-02T00:00:00Z"));
    AggregateSnapshot candidateB = snapshot(8, Instant.parse("2026-04-03T00:00:00Z"));
    persist(first);
    persist(candidateA);
    persist(candidateB);
    AggregateSnapshotRevision initial =
        service.registerInitial(
            serviceContext(),
            new RegisterAggregateSnapshotRequest(UUID.randomUUID(), first.snapshotId()));
    clock.set(Instant.parse("2026-04-03T12:00:00Z"));
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Object> left =
          workers.submit(() -> appendAfter(start, first, initial, candidateA, UUID.randomUUID()));
      Future<Object> right =
          workers.submit(() -> appendAfter(start, first, initial, candidateB, UUID.randomUUID()));
      start.countDown();
      Object leftResult = left.get(30, TimeUnit.SECONDS);
      Object rightResult = right.get(30, TimeUnit.SECONDS);

      assertThat(
              List.of(leftResult, rightResult).stream()
                  .filter(AggregateSnapshotRevision.class::isInstance))
          .hasSize(1);
      assertThat(
              List.of(leftResult, rightResult).stream()
                  .filter(AnalyticsSnapshotRevisionException.class::isInstance))
          .hasSize(1);
    }

    AnalyticsSnapshotRevisionService restarted = service(allowedAuthorities());
    AggregateSnapshotRevision current =
        restarted.currentRevision(serviceContext(), first.snapshotId()).orElseThrow();
    assertThat(current.revisionNumber()).isEqualTo(2);
    assertThat(current.previousSnapshotId()).contains(first.snapshotId());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analytics.aggregate_snapshot_revision WHERE series_id = ?",
                Integer.class,
                current.seriesId()))
        .isEqualTo(2);
  }

  @Test
  void lateRevisionAndFinalizationRaceCannotAppendAfterFinalizationWithoutCorrectionGrant()
      throws Exception {
    AggregateSnapshot first = snapshot(4, Instant.parse("2026-03-31T00:00:00Z"));
    AggregateSnapshot late = snapshot(7, Instant.parse("2026-04-02T00:00:00Z"));
    persist(first);
    persist(late);
    AggregateSnapshotRevision initial =
        service.registerInitial(
            serviceContext(),
            new RegisterAggregateSnapshotRequest(UUID.randomUUID(), first.snapshotId()));
    clock.set(Instant.parse("2026-04-03T12:00:00Z"));
    CountDownLatch start = new CountDownLatch(1);
    AnalyticsSnapshotRevisionService lateOnly =
        service(Set.of(AnalyticsAuthorities.lateAggregateRevision().code()));
    AnalyticsSnapshotRevisionService finalizeOnly =
        service(Set.of(AnalyticsAuthorities.aggregateSnapshotFinalization().code()));
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Object> revision =
          workers.submit(
              () ->
                  awaitAndCall(
                      start,
                      () ->
                          lateOnly.appendRevision(
                              serviceContext(),
                              new AppendAggregateSnapshotRevisionRequest(
                                  UUID.randomUUID(),
                                  first.snapshotId(),
                                  initial.revisionNumber(),
                                  late.snapshotId(),
                                  AnalyticsRevisionCause.LATE_OWNER_FACT))));
      Future<Object> finalization =
          workers.submit(
              () ->
                  awaitAndCall(
                      start,
                      () ->
                          finalizeOnly.finalizeSnapshot(
                              operatorContext(),
                              new FinalizeAggregateSnapshotRequest(
                                  UUID.randomUUID(),
                                  first.snapshotId(),
                                  initial.revisionNumber(),
                                  AnalyticsFinalizationReason.PERIOD_CLOSED))));
      start.countDown();
      Object revisionResult = revision.get(30, TimeUnit.SECONDS);
      Object finalizationResult = finalization.get(30, TimeUnit.SECONDS);

      assertThat(
              List.of(revisionResult, finalizationResult).stream()
                  .filter(result -> !(result instanceof AnalyticsSnapshotRevisionException)))
          .hasSize(1);
      assertThat(
              List.of(revisionResult, finalizationResult).stream()
                  .filter(AnalyticsSnapshotRevisionException.class::isInstance))
          .hasSize(1);
    }
    AggregateSnapshotRevision current =
        service(allowedAuthorities())
            .currentRevision(serviceContext(), first.snapshotId())
            .orElseThrow();
    if (current.revisionNumber() == 1) {
      assertThat(current.state()).isEqualTo(AggregateSnapshotRevision.State.FINALIZED);
    } else {
      assertThat(current.revisionNumber()).isEqualTo(2);
      assertThat(current.state()).isEqualTo(AggregateSnapshotRevision.State.PROVISIONAL);
    }
  }

  private static Object appendAfter(
      CountDownLatch start,
      AggregateSnapshot previous,
      AggregateSnapshotRevision initial,
      AggregateSnapshot candidate,
      UUID operationId) {
    return awaitAndCall(
        start,
        () ->
            service(Set.of(AnalyticsAuthorities.lateAggregateRevision().code()))
                .appendRevision(
                    serviceContext(),
                    new AppendAggregateSnapshotRevisionRequest(
                        operationId,
                        previous.snapshotId(),
                        initial.revisionNumber(),
                        candidate.snapshotId(),
                        AnalyticsRevisionCause.LATE_OWNER_FACT)));
  }

  private static Object awaitAndCall(
      CountDownLatch start, java.util.concurrent.Callable<?> action) {
    try {
      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("concurrency test did not start");
      }
      return action.call();
    } catch (AnalyticsSnapshotRevisionException expectedConflict) {
      return expectedConflict;
    } catch (Exception failure) {
      throw new RuntimeException(failure);
    }
  }

  private static AnalyticsSnapshotRevisionService service(Set<String> grantedAuthorities) {
    AuthorizationGrantLookup grants =
        (actor, authority, scope) -> grantedAuthorities.contains(authority.code());
    AuthorizationService authorization =
        new AuthorizationService(
            grants,
            new FunctionalAuthorityCatalog(
                List.of(
                    AnalyticsAuthorities.aggregateComputation(),
                    AnalyticsAuthorities.lateAggregateRevision(),
                    AnalyticsAuthorities.aggregateSnapshotFinalization(),
                    AnalyticsAuthorities.aggregateSnapshotCorrection())));
    return new AnalyticsSnapshotRevisionService(
        analyticsStore,
        revisionStore,
        authorization,
        new AuditWriter(new JdbcAuditRecordAppender(jdbc, transactionManager), clock),
        transactionManager,
        clock,
        CORRECTION_WINDOW);
  }

  private static Set<String> allowedAuthorities() {
    return Set.of(
        AnalyticsAuthorities.aggregateComputation().code(),
        AnalyticsAuthorities.lateAggregateRevision().code(),
        AnalyticsAuthorities.aggregateSnapshotFinalization().code(),
        AnalyticsAuthorities.aggregateSnapshotCorrection().code());
  }

  private static ExecutionContext serviceContext() {
    return contexts.forBackgroundWork(
        ANALYTICS,
        RequestedExecutionScope.organization(ORGANIZATION),
        new CorrelationId(UUID.randomUUID().toString()),
        Optional.empty());
  }

  private static ExecutionContext operatorContext() {
    return contexts.forInteractiveRequest(
        OPERATOR,
        RequestedExecutionScope.organization(ORGANIZATION),
        new CorrelationId(UUID.randomUUID().toString()));
  }

  private static IndicatorDefinitionVersion definition() {
    return new IndicatorDefinitionVersion(
        "SYN-OPS-COUNT",
        1,
        "Synthetic operational count",
        ZoneId.of("UTC"),
        List.of(MEASURE),
        true);
  }

  private static AggregateSnapshot snapshot(long value, Instant sourceCutoff) {
    var period =
        org.ayuvahak.analytics.domain.ReportingPeriod.resolve(
            LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-02"), ZoneId.of("UTC"));
    var input =
        new AggregateInput(
            MEASURE,
            Optional.of(UUID.randomUUID()),
            Optional.of(sourceCutoff),
            Optional.of("referral-snapshot-v1"),
            AggregateCompleteness.COMPLETE,
            OptionalLong.of(value),
            Optional.of(sourceCutoff));
    return AggregateSnapshot.create(
        InternalId.generate(),
        definition(),
        ORGANIZATION,
        period,
        AggregateCompleteness.COMPLETE,
        OptionalLong.of(value),
        List.of(input),
        clock.instant());
  }

  private static AggregateSnapshot partialSnapshot() {
    var period =
        org.ayuvahak.analytics.domain.ReportingPeriod.resolve(
            LocalDate.parse("2026-03-02"), LocalDate.parse("2026-03-03"), ZoneId.of("UTC"));
    var input =
        new AggregateInput(
            MEASURE,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            AggregateCompleteness.MISSING_SOURCE,
            OptionalLong.empty(),
            Optional.empty());
    return AggregateSnapshot.create(
        InternalId.generate(),
        definition(),
        ORGANIZATION,
        period,
        AggregateCompleteness.MISSING_SOURCE,
        OptionalLong.empty(),
        List.of(input),
        clock.instant());
  }

  private static void persist(AggregateSnapshot snapshot) {
    analyticsStore.saveOrReplay(snapshot);
  }

  private static final class MutableClock extends Clock {

    private final AtomicReference<Instant> current;

    private MutableClock(Instant initial) {
      current = new AtomicReference<>(initial);
    }

    private void set(Instant value) {
      current.set(value);
    }

    @Override
    public ZoneId getZone() {
      return UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!UTC.equals(zone)) {
        throw new IllegalArgumentException("test clock only supports UTC");
      }
      return this;
    }

    @Override
    public Instant instant() {
      return current.get();
    }
  }
}
