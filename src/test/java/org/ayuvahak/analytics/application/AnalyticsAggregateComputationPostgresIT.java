package org.ayuvahak.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.ayuvahak.analytics.adapter.persistence.JdbcAnalyticsComputationOperationStore;
import org.ayuvahak.analytics.adapter.persistence.JdbcAnalyticsStore;
import org.ayuvahak.analytics.domain.AggregateCompleteness;
import org.ayuvahak.analytics.domain.AggregateSnapshot;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.analytics.domain.IndicatorDefinitionGovernance;
import org.ayuvahak.analytics.domain.IndicatorDefinitionGovernance.AuthorityDefinition;
import org.ayuvahak.analytics.domain.IndicatorDefinitionGovernance.CompletenessRequirement;
import org.ayuvahak.analytics.domain.IndicatorDefinitionGovernance.ReportingCalendar;
import org.ayuvahak.analytics.domain.IndicatorDefinitionGovernance.ReportingDimension;
import org.ayuvahak.analytics.domain.IndicatorDefinitionGovernance.SensitivityClassification;
import org.ayuvahak.analytics.domain.IndicatorDefinitionVersion;
import org.ayuvahak.analytics.domain.ReportingPeriod;
import org.ayuvahak.analytics.domain.SourceMeasure;
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
import org.ayuvahak.testsupport.Phase14GoldenCorpus;
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

/** Verifies reproducible Analytics computation and immutable storage against local PostgreSQL. */
@Testcontainers
class AnalyticsAggregateComputationPostgresIT {

  private static final String PASSWORD = "local-test-only";
  private static final IssuerId ISSUER = new IssuerId("https://identity.example.test/realm");
  private static final ActorReference ANALYTICS =
      ActorReference.service(ISSUER, "synthetic-analytics-reporting");
  private static final ExternalReference ORGANIZATION =
      new ExternalReference(
          new IssuerId("urn:ayuvahak:directory"), "Organization", UUID.randomUUID().toString());
  private static final ExternalReference FACILITY =
      new ExternalReference(
          new IssuerId("urn:ayuvahak:directory"), "Facility", UUID.randomUUID().toString());
  private static final Instant SOURCE_CUTOFF = Instant.parse("2026-03-30T00:00:00Z");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-03-31T12:00:00Z"), ZoneOffset.UTC);

  @Container
  private static final PostgreSQLContainer POSTGRES =
      org.ayuvahak.testcontainers.PostgresTestContainerFactory.create(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(PASSWORD);

  private static JdbcTemplate jdbc;
  private static DataSourceTransactionManager transactionManager;
  private static AnalyticsStore store;
  private static AnalyticsComputationOperationStore operations;
  private static ExecutionContextFactory contexts;

  @BeforeAll
  static void startLocalDatabaseAndMigrate() {
    POSTGRES.start();
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbc = new JdbcTemplate(dataSource);
    transactionManager = new DataSourceTransactionManager(dataSource);
    store = new JdbcAnalyticsStore(jdbc, transactionManager);
    operations = new JdbcAnalyticsComputationOperationStore(jdbc);
    contexts = new ExecutionContextFactory((actor, scope) -> actor != null && scope != null);
  }

  @AfterAll
  static void stopLocalDatabase() {
    POSTGRES.stop();
  }

  @BeforeEach
  void clearSyntheticAnalyticsRows() {
    jdbc.update("DELETE FROM analytics.computation_operation");
    jdbc.update("DELETE FROM analytics.aggregate_snapshot_input");
    jdbc.update("DELETE FROM analytics.aggregate_snapshot");
    jdbc.update("DELETE FROM analytics.indicator_definition_dimension");
    jdbc.update("DELETE FROM analytics.indicator_definition_measure");
    jdbc.update("DELETE FROM analytics.indicator_definition_version");
  }

  @Test
  void keepsVersionedInputsAndReplaysTheSameComputationAfterStoreReconstruction() {
    IndicatorDefinitionVersion definition =
        definition(1, List.of(referralSubmitted(), transferCompleted()));
    store.publishDefinition(definition);
    var reader =
        new SyntheticOwnerReader(
            List.of(
                input(
                    referralSubmitted(),
                    Phase14GoldenCorpus.integer(
                        "$.cases.analyticsComputation.sourceMeasureValues.REFERRAL_SUBMITTED")),
                input(
                    transferCompleted(),
                    Phase14GoldenCorpus.integer(
                        "$.cases.analyticsComputation.sourceMeasureValues.TRANSFER_COMPLETED"))));
    var service = service(reader, true);
    InternalId computationId = InternalId.generate();

    var accepted = service.enqueue(context(), request(computationId));
    assertThat(accepted.state()).isEqualTo(AnalyticsComputationOperation.State.PENDING);
    assertThat(reader.readCount()).isZero();

    AnalyticsComputationOperation completed = service.processNext().orElseThrow();
    assertThat(completed.state().name())
        .isEqualTo(Phase14GoldenCorpus.string("$.cases.analyticsComputation.operationState"));
    AggregateSnapshot result =
        store.findSnapshotById(completed.snapshotId().orElseThrow()).orElseThrow();
    assertThat(reader.readCount()).isEqualTo(2);

    assertThat(result.completeness().name())
        .isEqualTo(Phase14GoldenCorpus.string("$.cases.analyticsComputation.completeness"));
    assertThat(result.value())
        .hasValue(Phase14GoldenCorpus.integer("$.cases.analyticsComputation.value"));
    assertThat(result.period().startInclusiveUtc())
        .isEqualTo(
            Instant.parse(
                Phase14GoldenCorpus.string("$.cases.analyticsComputation.startInclusiveUtc")));
    assertThat(result.period().endExclusiveUtc())
        .isEqualTo(
            Instant.parse(
                Phase14GoldenCorpus.string("$.cases.analyticsComputation.endExclusiveUtc")));
    assertThat(result.inputs())
        .hasSize(Phase14GoldenCorpus.integer("$.cases.analyticsComputation.inputCount"));
    assertThat(result.inputs())
        .extracting(org.ayuvahak.analytics.domain.AggregateInput::sourceCutoff)
        .containsExactlyInAnyOrder(
            Optional.of(SOURCE_CUTOFF), Optional.of(Instant.parse("2026-03-31T11:00:00Z")));

    AnalyticsStore restartedStore = new JdbcAnalyticsStore(jdbc, transactionManager);
    AnalyticsComputationOperation replayOperation =
        service(reader, true, restartedStore).enqueue(context(), request(computationId));
    assertThat(replayOperation.state()).isEqualTo(AnalyticsComputationOperation.State.COMPLETED);
    AggregateSnapshot replay =
        restartedStore.findSnapshotById(replayOperation.snapshotId().orElseThrow()).orElseThrow();

    assertThat(replay.snapshotId()).isEqualTo(result.snapshotId());
    assertThat(replay.inputFingerprint()).isEqualTo(result.inputFingerprint());
    assertThat(replay.value())
        .hasValue(Phase14GoldenCorpus.integer("$.cases.analyticsComputation.value"));
    assertThat(store.findDefinition("SYN-OPS-COUNT", 1)).contains(definition);
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM analytics.aggregate_snapshot", Integer.class))
        .isEqualTo(
            Phase14GoldenCorpus.integer(
                "$.cases.analyticsComputation.idempotentReplaySnapshotCount"));
  }

  @Test
  void recoversSnapshotCommittedBeforeWorkerRestartWithoutRepeatingOwnerReads() {
    IndicatorDefinitionVersion definition = definition(1, List.of(referralSubmitted()));
    store.publishDefinition(definition);
    InternalId requestId = InternalId.generate();
    var reader = new SyntheticOwnerReader(List.of(input(referralSubmitted(), 4)));
    var service = service(reader, true);
    service.enqueue(context(), request(requestId));
    AnalyticsComputationOperation claimed =
        operations.claimNext(CLOCK.instant(), CLOCK.instant().plusSeconds(30)).orElseThrow();

    ReportingPeriod period =
        ReportingPeriod.resolve(
            LocalDate.parse("2026-03-29"),
            LocalDate.parse("2026-03-30"),
            ZoneId.of("Europe/Berlin"));
    AggregateSnapshot committedBeforeCrash =
        AggregateSnapshot.create(
            requestId,
            definition,
            ORGANIZATION,
            period,
            AggregateCompleteness.COMPLETE,
            OptionalLong.of(4),
            List.of(input(referralSubmitted(), 4).toAggregateInput()),
            CLOCK.instant());
    store.saveOrReplay(committedBeforeCrash);
    jdbc.update(
        "UPDATE analytics.computation_operation SET lease_until = ? WHERE request_id = ?",
        java.sql.Timestamp.from(CLOCK.instant().minusSeconds(1)),
        requestId.value());

    assertThat(service.processNext()).isEmpty();
    AnalyticsComputationOperation recovered = operations.find(requestId).orElseThrow();
    assertThat(recovered.state()).isEqualTo(AnalyticsComputationOperation.State.COMPLETED);
    assertThat(recovered.snapshotId()).contains(committedBeforeCrash.snapshotId());
    assertThat(claimed.attemptCount()).isEqualTo(1);
    assertThat(reader.readCount()).isZero();
  }

  @Test
  void transientOwnerFailureIsDurablyRetriedAndCompletesAfterBackoff() {
    IndicatorDefinitionVersion definition = definition(1, List.of(referralSubmitted()));
    store.publishDefinition(definition);
    var clock = new MutableClock(CLOCK.instant());
    var reader = new SyntheticOwnerReader(List.of(input(referralSubmitted(), 4)), 1);
    var service = service(reader, true, store, clock);
    InternalId requestId = InternalId.generate();
    service.enqueue(context(), request(requestId));

    AnalyticsComputationOperation firstAttempt = service.processNext().orElseThrow();
    assertThat(firstAttempt.state()).isEqualTo(AnalyticsComputationOperation.State.RETRY_PENDING);
    assertThat(firstAttempt.attemptCount()).isEqualTo(1);
    assertThat(firstAttempt.snapshotId()).isEmpty();
    assertThat(reader.readCount()).isEqualTo(1);

    clock.advanceSeconds(3);
    AnalyticsComputationOperation recovered =
        service(reader, true, store, clock).processNext().orElseThrow();
    assertThat(recovered.state()).isEqualTo(AnalyticsComputationOperation.State.COMPLETED);
    assertThat(recovered.attemptCount()).isEqualTo(2);
    assertThat(reader.readCount()).isEqualTo(2);
    assertThat(store.findSnapshotById(recovered.snapshotId().orElseThrow())).isPresent();
  }

  @Test
  void staleWorkerCannotCompleteOrRetryAfterAnotherWorkerReclaimsExpiredLease() {
    IndicatorDefinitionVersion definition = definition(1, List.of(referralSubmitted()));
    store.publishDefinition(definition);
    InternalId requestId = InternalId.generate();
    service(new SyntheticOwnerReader(List.of(input(referralSubmitted(), 4))), true)
        .enqueue(context(), request(requestId));
    AnalyticsComputationOperation firstClaim =
        operations.claimNext(CLOCK.instant(), CLOCK.instant().plusSeconds(1)).orElseThrow();
    AnalyticsComputationOperation reclaimed =
        operations
            .claimNext(CLOCK.instant().plusSeconds(2), CLOCK.instant().plusSeconds(30))
            .orElseThrow();
    assertThat(reclaimed.attemptCount()).isEqualTo(firstClaim.attemptCount() + 1);

    assertThatThrownBy(
            () ->
                operations.complete(
                    requestId, firstClaim.attemptCount(), UUID.randomUUID(), CLOCK.instant()))
        .isInstanceOf(AnalyticsComputationOperationConflictException.class);
    assertThatThrownBy(
            () ->
                operations.retry(
                    requestId,
                    firstClaim.attemptCount(),
                    "COMPUTATION_UNAVAILABLE",
                    8,
                    CLOCK.instant().plusSeconds(5),
                    CLOCK.instant()))
        .isInstanceOf(AnalyticsComputationOperationConflictException.class);
    assertThat(operations.find(requestId).orElseThrow().state())
        .isEqualTo(AnalyticsComputationOperation.State.PROCESSING);
  }

  @Test
  void unavailableOwnerInputIsMissingSourceRatherThanNumericZero() {
    store.publishDefinition(definition(1, List.of(referralSubmitted())));
    var reader = new SyntheticOwnerReader(List.of(missingInput(referralSubmitted())));

    AggregateSnapshot result = compute(service(reader, true), request(InternalId.generate()));

    assertThat(result.completeness()).isEqualTo(AggregateCompleteness.MISSING_SOURCE);
    assertThat(result.value()).isEmpty();
    assertThat(result.inputs().getFirst().value()).isEmpty();
  }

  @Test
  void completeZeroIsExplicitAndUnauthorizedIdentityCannotPersistAResult() {
    IndicatorDefinitionVersion zeroDefinition = definition(1, List.of(referralSubmitted()));
    store.publishDefinition(zeroDefinition);
    AggregateSnapshot zero =
        compute(
            service(new SyntheticOwnerReader(List.of(input(referralSubmitted(), 0))), true),
            request(InternalId.generate()));

    assertThat(zero.completeness()).isEqualTo(AggregateCompleteness.COMPLETE);
    assertThat(zero.value()).hasValue(0L);

    assertThatThrownBy(
            () ->
                service(new SyntheticOwnerReader(List.of(input(referralSubmitted(), 9))), false)
                    .enqueue(context(), request(InternalId.generate())))
        .isInstanceOf(AnalyticsComputationException.class)
        .hasMessageContaining("not authorized");
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM analytics.aggregate_snapshot", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void computesAndPersistsASeparateDenominatorRatioWithDocumentedRounding() {
    IndicatorDefinitionVersion ratio =
        new IndicatorDefinitionVersion(
            "SYN-REFERRAL-RATIO",
            1,
            "Synthetic referral ratio",
            org.ayuvahak.analytics.domain.AggregationFormula.RATIO_6DP_HALF_EVEN,
            ZoneId.of("Europe/Berlin"),
            List.of(referralSubmitted()),
            true,
            List.of(referralResolved()),
            IndicatorDefinitionGovernance.syntheticFixture("Synthetic ratio demonstration"));
    store.publishDefinition(ratio);
    InternalId computationId = InternalId.generate();
    AnalyticsComputationRequest request =
        new AnalyticsComputationRequest(
            computationId,
            ratio.key(),
            ratio.version(),
            LocalDate.parse("2026-03-29"),
            LocalDate.parse("2026-03-30"),
            SOURCE_CUTOFF);
    var reader =
        new SyntheticOwnerReader(
            List.of(input(referralSubmitted(), 1), input(referralResolved(), 6)));

    AnalyticsAggregateComputationService service = service(reader, true);
    AnalyticsComputationOperation completed = service.enqueue(context(), request);
    assertThat(completed.state()).isEqualTo(AnalyticsComputationOperation.State.PENDING);
    completed = service.processNext().orElseThrow();
    AggregateSnapshot result =
        store.findSnapshotById(completed.snapshotId().orElseThrow()).orElseThrow();

    assertThat(result.completeness()).isEqualTo(AggregateCompleteness.COMPLETE);
    assertThat(result.numericValue()).contains(new java.math.BigDecimal("0.166667"));
    assertThat(result.value()).isEmpty();
    assertThat(result.inputs()).hasSize(2);
    assertThat(store.findDefinition(ratio.key(), ratio.version())).contains(ratio);
  }

  @Test
  void persistsFacilityScopeWithoutCollapsingItIntoOrganizationScope() {
    IndicatorDefinitionVersion definition = definition(1, List.of(transferCompleted()));
    store.publishDefinition(definition);
    InternalId computationId = InternalId.generate();
    AnalyticsComputationRequest request = request(computationId);
    var reader = new SyntheticOwnerReader(List.of(input(transferCompleted(), 2, FACILITY)));
    AnalyticsAggregateComputationService service = service(reader, true);

    AnalyticsComputationOperation accepted = service.enqueue(facilityContext(), request);
    assertThat(accepted.facility()).contains(FACILITY);
    AnalyticsComputationOperation completed = service.processNext().orElseThrow();
    AggregateSnapshot snapshot =
        store.findSnapshotById(completed.snapshotId().orElseThrow()).orElseThrow();
    AnalyticsComputationOperation recovered = operations.find(computationId).orElseThrow();

    assertThat(snapshot.organization()).isEqualTo(ORGANIZATION);
    assertThat(snapshot.facility()).contains(FACILITY);
    assertThat(snapshot.value()).hasValue(2L);
    assertThat(recovered.facility()).contains(FACILITY);
    assertThat(
            jdbc.queryForObject(
                "SELECT facility_id FROM analytics.aggregate_snapshot WHERE snapshot_id = ?",
                UUID.class,
                snapshot.snapshotId()))
        .isEqualTo(UUID.fromString(FACILITY.value()));
  }

  @Test
  void changedDefinitionRequiresANewImmutableVersionAndRetainsTheOldVersion() {
    IndicatorDefinitionVersion first = definition(1, List.of(referralSubmitted()));
    IndicatorDefinitionVersion second = definition(2, List.of(referralResolved()));
    store.publishDefinition(first);
    store.publishDefinition(second);

    assertThat(store.findDefinition("SYN-OPS-COUNT", 1)).contains(first);
    assertThat(store.findDefinition("SYN-OPS-COUNT", 2)).contains(second);
    assertThatThrownBy(() -> store.publishDefinition(definition(1, List.of(referralResolved()))))
        .isInstanceOf(AnalyticsDefinitionConflictException.class);
  }

  @Test
  void persistsCompleteIndicatorGovernanceAndFormulaInputs() {
    IndicatorDefinitionGovernance governance =
        new IndicatorDefinitionGovernance(
            "Synthetic count of submitted referrals for local acceptance",
            Instant.parse("2026-01-01T00:00:00Z"),
            Optional.of(Instant.parse("2027-01-01T00:00:00Z")),
            ReportingCalendar.MONTHLY,
            EnumSet.of(ReportingDimension.ORGANIZATION, ReportingDimension.SOURCE_ATTRIBUTION),
            CompletenessRequirement.ALL_REQUIRED_SOURCES_COMPLETE_FOR_NUMERIC_VALUE,
            Optional.of(Duration.ofHours(12)),
            SensitivityClassification.SENSITIVE_AGGREGATE,
            Optional.of("SYN-ANALYTICS-POLICY-001"),
            Optional.of(new AuthorityDefinition("urn:synthetic:authority", "SYN-definition-v1")));
    IndicatorDefinitionVersion definition =
        new IndicatorDefinitionVersion(
            "SYN-OPS-COUNT",
            1,
            "Synthetic operational count",
            org.ayuvahak.analytics.domain.AggregationFormula.SUM,
            ZoneId.of("Europe/Berlin"),
            List.of(referralSubmitted()),
            true,
            List.of(),
            governance);

    store.publishDefinition(definition);

    assertThat(store.findDefinition(definition.key(), definition.version())).contains(definition);
    assertThat(
            jdbc.queryForObject(
                "SELECT denominator_definition FROM analytics.indicator_definition_version "
                    + "WHERE indicator_key = ? AND definition_version = ?",
                String.class,
                definition.key(),
                definition.version()))
        .isEqualTo("NONE");
  }

  @Test
  void sourceFreshnessRequirementTurnsOldOwnerSnapshotIntoStaleNotAValue() {
    IndicatorDefinitionGovernance governance =
        new IndicatorDefinitionGovernance(
            "Synthetic freshness-gated submitted referral count",
            Instant.EPOCH,
            Optional.empty(),
            ReportingCalendar.DAILY,
            java.util.EnumSet.of(ReportingDimension.ORGANIZATION),
            CompletenessRequirement.ALL_REQUIRED_SOURCES_COMPLETE_FOR_NUMERIC_VALUE,
            Optional.of(Duration.ofHours(1)),
            SensitivityClassification.SENSITIVE_AGGREGATE,
            Optional.empty(),
            Optional.empty());
    IndicatorDefinitionVersion base = definition(1, List.of(referralSubmitted()));
    IndicatorDefinitionVersion governed =
        new IndicatorDefinitionVersion(
            base.key(),
            base.version(),
            base.description(),
            base.formula(),
            base.reportingTimeZone(),
            base.sourceMeasures(),
            base.zeroAllowed(),
            List.of(),
            governance);
    store.publishDefinition(governed);
    var reader =
        new SyntheticOwnerReader(
            List.of(input(referralSubmitted(), 4, Instant.parse("2026-03-31T09:00:00Z"))));

    AggregateSnapshot result = compute(service(reader, true), request(InternalId.generate()));

    assertThat(result.completeness()).isEqualTo(AggregateCompleteness.STALE_SOURCE);
    assertThat(result.value()).isEmpty();
    assertThat(result.inputs().getFirst().value()).isEmpty();
  }

  private static AnalyticsAggregateComputationService service(
      OwnerReportingSnapshotReader reader, boolean allowed) {
    return service(reader, allowed, store);
  }

  private static AnalyticsAggregateComputationService service(
      OwnerReportingSnapshotReader reader, boolean allowed, AnalyticsStore analyticsStore) {
    return service(reader, allowed, analyticsStore, CLOCK);
  }

  private static AnalyticsAggregateComputationService service(
      OwnerReportingSnapshotReader reader,
      boolean allowed,
      AnalyticsStore analyticsStore,
      Clock clock) {
    AuthorizationGrantLookup grants = (actor, authority, scope) -> allowed;
    AuthorizationService authorization =
        new AuthorizationService(
            grants,
            new FunctionalAuthorityCatalog(List.of(AnalyticsAuthorities.aggregateComputation())));
    return new AnalyticsAggregateComputationService(
        analyticsStore, operations, reader, authorization, contexts, clock);
  }

  private static AggregateSnapshot compute(
      AnalyticsAggregateComputationService service, AnalyticsComputationRequest request) {
    service.enqueue(context(), request);
    var completed = service.processNext().orElseThrow();
    return store.findSnapshotById(completed.snapshotId().orElseThrow()).orElseThrow();
  }

  private static ExecutionContext context() {
    return contexts.forBackgroundWork(
        ANALYTICS,
        RequestedExecutionScope.organization(ORGANIZATION),
        new CorrelationId(UUID.randomUUID().toString()),
        Optional.empty());
  }

  private static ExecutionContext facilityContext() {
    return contexts.forBackgroundWork(
        ANALYTICS,
        RequestedExecutionScope.facility(ORGANIZATION, FACILITY),
        new CorrelationId(UUID.randomUUID().toString()),
        Optional.empty());
  }

  private static AnalyticsComputationRequest request(InternalId computationId) {
    return new AnalyticsComputationRequest(
        computationId,
        "SYN-OPS-COUNT",
        1,
        LocalDate.parse("2026-03-29"),
        LocalDate.parse("2026-03-30"),
        SOURCE_CUTOFF);
  }

  private static IndicatorDefinitionVersion definition(int version, List<SourceMeasure> measures) {
    return new IndicatorDefinitionVersion(
        "SYN-OPS-COUNT",
        version,
        "Synthetic operational count",
        ZoneId.of("Europe/Berlin"),
        measures,
        true);
  }

  private static SourceMeasure referralSubmitted() {
    return SourceMeasure.referral("SUBMITTED_COUNT");
  }

  private static SourceMeasure referralResolved() {
    return SourceMeasure.referral("RESOLVED_COUNT");
  }

  private static SourceMeasure transferCompleted() {
    return SourceMeasure.transfer("TRANSFER_COMPLETED", SourceMeasure.Attribution.ORIGIN);
  }

  private static OwnerMeasureSnapshot input(SourceMeasure measure, long value) {
    return input(measure, value, Instant.parse("2026-03-31T11:30:00Z"));
  }

  private static OwnerMeasureSnapshot input(
      SourceMeasure measure, long value, Instant generatedAt) {
    return input(measure, value, generatedAt, Optional.empty());
  }

  private static OwnerMeasureSnapshot input(
      SourceMeasure measure, long value, ExternalReference facility) {
    return input(measure, value, Instant.parse("2026-03-31T11:30:00Z"), Optional.of(facility));
  }

  private static OwnerMeasureSnapshot input(
      SourceMeasure measure,
      long value,
      Instant generatedAt,
      Optional<ExternalReference> facility) {
    return new OwnerMeasureSnapshot(
        measure,
        ORGANIZATION,
        facility,
        Instant.parse("2026-03-28T23:00:00Z"),
        Instant.parse("2026-03-29T22:00:00Z"),
        Optional.of(
            UUID.nameUUIDFromBytes(
                measure.stableKey().getBytes(java.nio.charset.StandardCharsets.UTF_8))),
        Optional.of(
            measure.owner() == SourceMeasure.Owner.REFERRAL
                ? SOURCE_CUTOFF
                : Instant.parse("2026-03-31T11:00:00Z")),
        Optional.of(
            measure.owner() == SourceMeasure.Owner.REFERRAL
                ? "referral-snapshot-v1"
                : "transfer-reporting-v1"),
        AggregateCompleteness.COMPLETE,
        OptionalLong.of(value),
        Optional.of(generatedAt));
  }

  private static OwnerMeasureSnapshot missingInput(SourceMeasure measure) {
    return new OwnerMeasureSnapshot(
        measure,
        ORGANIZATION,
        Instant.parse("2026-03-28T23:00:00Z"),
        Instant.parse("2026-03-29T22:00:00Z"),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        AggregateCompleteness.MISSING_SOURCE,
        OptionalLong.empty(),
        Optional.empty());
  }

  private static final class SyntheticOwnerReader implements OwnerReportingSnapshotReader {
    private final List<OwnerMeasureSnapshot> results;
    private final java.util.concurrent.atomic.AtomicInteger reads =
        new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger failuresRemaining;

    private SyntheticOwnerReader(List<OwnerMeasureSnapshot> results) {
      this(results, 0);
    }

    private SyntheticOwnerReader(List<OwnerMeasureSnapshot> results, int failures) {
      this.results = List.copyOf(results);
      this.failuresRemaining = new java.util.concurrent.atomic.AtomicInteger(failures);
    }

    private int readCount() {
      return reads.get();
    }

    @Override
    public OwnerMeasureSnapshot snapshot(
        ExecutionContext context, SourceMeasure measure, OwnerReportingRequest request) {
      reads.incrementAndGet();
      if (failuresRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
        throw new IllegalStateException("synthetic local owner source unavailable");
      }
      return results.stream()
          .filter(input -> input.measure().equals(measure))
          .findFirst()
          .orElseThrow(() -> new AssertionError("unexpected owner measure " + measure.key()));
    }
  }

  private static final class MutableClock extends Clock {
    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }

    private void advanceSeconds(long seconds) {
      current = current.plusSeconds(seconds);
    }
  }
}
