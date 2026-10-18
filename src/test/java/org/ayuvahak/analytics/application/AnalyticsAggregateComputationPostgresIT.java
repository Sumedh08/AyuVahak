package org.ayuvahak.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.ayuvahak.analytics.adapter.persistence.JdbcAnalyticsStore;
import org.ayuvahak.analytics.domain.AggregateCompleteness;
import org.ayuvahak.analytics.domain.AggregateSnapshot;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.analytics.domain.IndicatorDefinitionVersion;
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
  private static final Instant SOURCE_CUTOFF = Instant.parse("2026-03-30T00:00:00Z");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-03-31T12:00:00Z"), ZoneOffset.UTC);

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
  private static AnalyticsStore store;
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
    contexts = new ExecutionContextFactory((actor, scope) -> actor != null && scope != null);
  }

  @AfterAll
  static void stopLocalDatabase() {
    POSTGRES.stop();
  }

  @BeforeEach
  void clearSyntheticAnalyticsRows() {
    jdbc.update("DELETE FROM analytics.aggregate_snapshot_input");
    jdbc.update("DELETE FROM analytics.aggregate_snapshot");
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
            List.of(input(referralSubmitted(), 4), input(transferCompleted(), 2)));
    var service = service(reader, true);
    InternalId computationId = InternalId.generate();

    AggregateSnapshot result = service.compute(context(), request(computationId));

    assertThat(result.completeness()).isEqualTo(AggregateCompleteness.COMPLETE);
    assertThat(result.value()).hasValue(6L);
    assertThat(result.period().startInclusiveUtc())
        .isEqualTo(Instant.parse("2026-03-28T23:00:00Z"));
    assertThat(result.period().endExclusiveUtc()).isEqualTo(Instant.parse("2026-03-29T22:00:00Z"));
    assertThat(result.inputs()).hasSize(2);
    assertThat(result.inputs())
        .extracting(org.ayuvahak.analytics.domain.AggregateInput::sourceCutoff)
        .containsExactlyInAnyOrder(
            Optional.of(SOURCE_CUTOFF), Optional.of(Instant.parse("2026-03-31T11:00:00Z")));

    AnalyticsStore restartedStore = new JdbcAnalyticsStore(jdbc, transactionManager);
    AggregateSnapshot replay =
        service(reader, true, restartedStore).compute(context(), request(computationId));

    assertThat(replay.snapshotId()).isEqualTo(result.snapshotId());
    assertThat(replay.inputFingerprint()).isEqualTo(result.inputFingerprint());
    assertThat(replay.value()).hasValue(6L);
    assertThat(store.findDefinition("SYN-OPS-COUNT", 1)).contains(definition);
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM analytics.aggregate_snapshot", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void unavailableOwnerInputIsMissingSourceRatherThanNumericZero() {
    store.publishDefinition(definition(1, List.of(referralSubmitted())));
    var reader = new SyntheticOwnerReader(List.of(missingInput(referralSubmitted())));

    AggregateSnapshot result =
        service(reader, true).compute(context(), request(InternalId.generate()));

    assertThat(result.completeness()).isEqualTo(AggregateCompleteness.MISSING_SOURCE);
    assertThat(result.value()).isEmpty();
    assertThat(result.inputs().getFirst().value()).isEmpty();
  }

  @Test
  void completeZeroIsExplicitAndUnauthorizedIdentityCannotPersistAResult() {
    IndicatorDefinitionVersion zeroDefinition = definition(1, List.of(referralSubmitted()));
    store.publishDefinition(zeroDefinition);
    AggregateSnapshot zero =
        service(new SyntheticOwnerReader(List.of(input(referralSubmitted(), 0))), true)
            .compute(context(), request(InternalId.generate()));

    assertThat(zero.completeness()).isEqualTo(AggregateCompleteness.COMPLETE);
    assertThat(zero.value()).hasValue(0L);

    assertThatThrownBy(
            () ->
                service(new SyntheticOwnerReader(List.of(input(referralSubmitted(), 9))), false)
                    .compute(context(), request(InternalId.generate())))
        .isInstanceOf(AnalyticsComputationException.class)
        .hasMessageContaining("not authorized");
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM analytics.aggregate_snapshot", Integer.class))
        .isEqualTo(1);
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

  private static AnalyticsAggregateComputationService service(
      OwnerReportingSnapshotReader reader, boolean allowed) {
    return service(reader, allowed, store);
  }

  private static AnalyticsAggregateComputationService service(
      OwnerReportingSnapshotReader reader, boolean allowed, AnalyticsStore analyticsStore) {
    AuthorizationGrantLookup grants = (actor, authority, scope) -> allowed;
    AuthorizationService authorization =
        new AuthorizationService(
            grants,
            new FunctionalAuthorityCatalog(List.of(AnalyticsAuthorities.aggregateComputation())));
    return new AnalyticsAggregateComputationService(analyticsStore, reader, authorization, CLOCK);
  }

  private static ExecutionContext context() {
    return contexts.forBackgroundWork(
        ANALYTICS,
        RequestedExecutionScope.organization(ORGANIZATION),
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
    return new OwnerMeasureSnapshot(
        measure,
        ORGANIZATION,
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
        Optional.of(Instant.parse("2026-03-31T11:30:00Z")));
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

  private record SyntheticOwnerReader(List<OwnerMeasureSnapshot> results)
      implements OwnerReportingSnapshotReader {
    private SyntheticOwnerReader {
      results = new ArrayList<>(results);
    }

    @Override
    public OwnerMeasureSnapshot snapshot(
        ExecutionContext context, SourceMeasure measure, OwnerReportingRequest request) {
      return results.stream()
          .filter(input -> input.measure().equals(measure))
          .findFirst()
          .orElseThrow(() -> new AssertionError("unexpected owner measure " + measure.key()));
    }
  }
}
