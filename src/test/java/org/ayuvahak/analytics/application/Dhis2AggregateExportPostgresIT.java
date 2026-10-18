package org.ayuvahak.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.analytics.adapter.dhis2.Dhis2DataValueSetMapper;
import org.ayuvahak.analytics.adapter.dhis2.Dhis2ImportSummaryParser;
import org.ayuvahak.analytics.adapter.persistence.JdbcAnalyticsRevisionStore;
import org.ayuvahak.analytics.adapter.persistence.JdbcAnalyticsStore;
import org.ayuvahak.analytics.adapter.persistence.JdbcDhis2ExportOperationStore;
import org.ayuvahak.analytics.adapter.persistence.JdbcDhis2MappingStore;
import org.ayuvahak.analytics.adapter.persistence.JdbcDisclosureReservationStore;
import org.ayuvahak.analytics.domain.AggregateCompleteness;
import org.ayuvahak.analytics.domain.AggregateInput;
import org.ayuvahak.analytics.domain.AggregateSnapshot;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.analytics.domain.AnalyticsRevisionCause;
import org.ayuvahak.analytics.domain.Dhis2ExportOperation;
import org.ayuvahak.analytics.domain.Dhis2ExportReconciliation;
import org.ayuvahak.analytics.domain.Dhis2ExportState;
import org.ayuvahak.analytics.domain.Dhis2MappingVersion;
import org.ayuvahak.analytics.domain.Dhis2PeriodFormat;
import org.ayuvahak.analytics.domain.DisclosurePolicyVersion;
import org.ayuvahak.analytics.domain.DisclosureReservationState;
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
import org.ayuvahak.platform.exchange.adapter.http.LocalDhis2Phase9ExchangeHarness;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationPort;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationRequest;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationResponse;
import org.ayuvahak.platform.exchange.application.PartnerTransportException;
import org.ayuvahak.platform.exchange.application.PartnerTransportFailure;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
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
import tools.jackson.databind.ObjectMapper;

/** Local PostgreSQL acceptance for immutable DHIS2 mappings and the one-shot export lifecycle. */
@Testcontainers
class Dhis2AggregateExportPostgresIT {

  private static final String PASSWORD = "local-test-only";
  private static final IssuerId ISSUER = new IssuerId("https://identity.example.test/realm");
  private static final ExternalReference ORGANIZATION =
      new ExternalReference(
          new IssuerId("urn:ayuvahak:directory"), "Organization", UUID.randomUUID().toString());
  private static final ActorReference ANALYTICS =
      ActorReference.service(ISSUER, "synthetic-analytics-export-worker");
  private static final ActorReference ADMIN =
      ActorReference.human(ISSUER, "synthetic-analytics-mapping-admin");
  private static final Instant NOW = Instant.parse("2026-02-10T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String POLICY_KEY = "SYN-REPORTING-SCOPE-A";
  private static final String OUTPUT_KEY = "SYN-AGGREGATE-OUTPUT";
  private static final Set<String> GRANTED_AUTHORITIES =
      Set.of(
          AnalyticsAuthorities.dhis2MappingAdministration().code(),
          AnalyticsAuthorities.dhis2Export().code(),
          AnalyticsAuthorities.dhis2ExportReconciliation().code(),
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
  private static JdbcAnalyticsStore analyticsStore;
  private static JdbcAnalyticsRevisionStore revisionStore;
  private static JdbcDhis2MappingStore mappingStore;
  private static JdbcDhis2ExportOperationStore operationStore;
  private static AnalyticsDisclosureReservationService disclosure;
  private static Dhis2MappingAdministrationService mappingAdministration;
  private static Dhis2ExportReconciliationService reconciliation;
  private static ExecutionContextFactory contexts;
  private static Dhis2AggregateExportService exporter;
  private static RecordingExchange exchange;

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
    mappingStore = new JdbcDhis2MappingStore(jdbc, transactionManager);
    operationStore = new JdbcDhis2ExportOperationStore(jdbc);
    contexts = new ExecutionContextFactory((actor, scope) -> actor != null && scope != null);

    AuthorizationGrantLookup grants =
        (actor, authority, scope) -> GRANTED_AUTHORITIES.contains(authority.code());
    AuthorizationService authorization = new AuthorizationService(grants, authorities());
    AuditWriter audit =
        new AuditWriter(new JdbcAuditRecordAppender(jdbc, transactionManager), CLOCK);
    disclosure =
        new AnalyticsDisclosureReservationService(
            new JdbcDisclosureReservationStore(jdbc),
            authorization,
            audit,
            transactionManager,
            CLOCK,
            (reservationId, attemptId) -> false);
    mappingAdministration =
        new Dhis2MappingAdministrationService(
            mappingStore, authorization, audit, transactionManager, CLOCK);
    reconciliation =
        new Dhis2ExportReconciliationService(
            analyticsStore,
            operationStore,
            disclosure,
            authorization,
            audit,
            transactionManager,
            CLOCK);
    exchange = new RecordingExchange();
    exporter =
        new Dhis2AggregateExportService(
            analyticsStore,
            revisionStore,
            mappingStore,
            operationStore,
            disclosure,
            new Dhis2DataValueSetMapper(new ObjectMapper()),
            new Dhis2ImportSummaryParser(new ObjectMapper()),
            exchange,
            authorization,
            audit,
            transactionManager,
            CLOCK);
  }

  @AfterAll
  static void stopLocalDatabase() {
    POSTGRES.stop();
  }

  @BeforeEach
  void clearSyntheticFixtures() {
    jdbc.update("TRUNCATE analytics.dhis2_export_operation CASCADE");
    jdbc.update("TRUNCATE analytics.dhis2_mapping_head CASCADE");
    jdbc.update(
        "TRUNCATE analytics.disclosure_privacy_scope, analytics.disclosure_policy_head CASCADE");
    jdbc.update("TRUNCATE analytics.indicator_definition_version CASCADE");
    jdbc.update("DELETE FROM audit.audit_record WHERE action_code LIKE 'ANALYTICS_DHIS2_%'");
    exchange.clear();
  }

  @Test
  void publishesImmutableMappingAndRecordsOneValueAcknowledgementThroughPhase9Port() {
    AggregateSnapshot snapshot = prepareSnapshotAndMapping();
    exchange.respond(200, "application/json", imported());
    UUID operationId = UUID.randomUUID();

    Dhis2ExportOperation operation =
        exporter.export(
            serviceContext(), new Dhis2ExportRequest(operationId, snapshot.snapshotId()));

    assertThat(operation.state()).isEqualTo(Dhis2ExportState.ACKNOWLEDGED);
    assertThat(operation.resultClass()).isEqualTo("IMPORTED_ACKNOWLEDGED");
    assertThat(operation.partnerConfigurationVersion()).contains(1);
    assertThat(operation.importCounts()).isPresent();
    assertThat(exchange.requestCount()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analytics.dhis2_export_target_tuple_claim", Integer.class))
        .isZero();
    PartnerOutboundOperationRequest outbound = exchange.request().orElseThrow();
    assertThat(outbound.operation().isNamedAnalyticsDhis2DataValueSet()).isTrue();
    assertThat(outbound.expectedConfigurationVersion()).contains(1);
    assertThat(outbound.pathSegments()).containsExactly("dataValueSets");
    String body = new String(outbound.body(), StandardCharsets.UTF_8);
    assertThat(body)
        .contains("\"dataSet\":\"SYN-DATASET-001\"", "\"period\":\"202601\"")
        .contains("\"orgUnit\":\"SYN-ORGUNIT-001\"")
        .contains("\"dataElement\":\"SYN-DATAELEMENT-001\"", "\"value\":\"4\"")
        .doesNotContain("SYN-PATIENT", "PatientReference", "clinical");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analytics.dhis2_export_operation", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'analytics' "
                    + "AND table_name = 'dhis2_export_operation' AND column_name IN ('request_body', 'response_body')",
                Integer.class))
        .isZero();
    assertThat(
            disclosure
                .findReservation(
                    serviceContext(), operation.disclosureReservationId().orElseThrow())
                .state())
        .isEqualTo(DisclosureReservationState.DISCLOSED);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_record WHERE action_code = 'ANALYTICS_DHIS2_MAPPING_PUBLISHED'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void exportsOneValueThroughPhase9ToOnlyTheControlledLocalTlsDhis2Mock() throws Exception {
    AggregateSnapshot snapshot = prepareSnapshotAndMapping();
    try (LocalDhis2Phase9ExchangeHarness localPartner =
        LocalDhis2Phase9ExchangeHarness.start(new PartnerKey("synthetic-dhis2"))) {
      AuthorizationGrantLookup grants =
          (actor, authority, scope) -> GRANTED_AUTHORITIES.contains(authority.code());
      AuthorizationService authorization = new AuthorizationService(grants, authorities());
      AuditWriter audit =
          new AuditWriter(new JdbcAuditRecordAppender(jdbc, transactionManager), CLOCK);
      Dhis2AggregateExportService phase9Exporter =
          new Dhis2AggregateExportService(
              analyticsStore,
              revisionStore,
              mappingStore,
              operationStore,
              disclosure,
              new Dhis2DataValueSetMapper(new ObjectMapper()),
              new Dhis2ImportSummaryParser(new ObjectMapper()),
              localPartner.exchange(),
              authorization,
              audit,
              transactionManager,
              CLOCK);

      Dhis2ExportOperation operation =
          phase9Exporter.export(
              serviceContext(), new Dhis2ExportRequest(UUID.randomUUID(), snapshot.snapshotId()));

      assertThat(operation.state()).isEqualTo(Dhis2ExportState.ACKNOWLEDGED);
      assertThat(localPartner.requestCount()).isEqualTo(1);
      assertThat(localPartner.method()).isEqualTo("POST");
      assertThat(localPartner.path()).isEqualTo("/api/dataValueSets");
      assertThat(localPartner.operationReference()).isEqualTo("dhis2-" + operation.operationId());
      assertThat(localPartner.requestBody())
          .contains("SYN-DATAELEMENT-001", "SYN-ORGUNIT-001", "202601", "\"value\":\"4\"");
      assertThat(localPartner.requestBody()).containsOnlyOnce("\"dataElement\"");
    }
  }

  @Test
  void ambiguousIgnoredResultHoldsTupleUntilExactAuthorizedReconciliation() {
    AggregateSnapshot snapshot = prepareSnapshotAndMapping();
    exchange.respond(200, "application/json", ignored());
    UUID operationId = UUID.randomUUID();
    Dhis2ExportOperation ignoredOperation =
        exporter.export(
            serviceContext(), new Dhis2ExportRequest(operationId, snapshot.snapshotId()));
    assertThat(ignoredOperation.state()).isEqualTo(Dhis2ExportState.RECONCILIATION_REQUIRED);
    assertThat(ignoredOperation.resultClass()).isEqualTo("IMPORT_IGNORED_AMBIGUOUS");
    assertThat(operationStore.hasTargetTupleClaim(operationId)).isTrue();
    assertThat(
            disclosure
                .findReservation(
                    serviceContext(), ignoredOperation.disclosureReservationId().orElseThrow())
                .state())
        .isEqualTo(DisclosureReservationState.POTENTIALLY_DISCLOSED);

    Dhis2ExportOperation blocked = export(snapshot);
    assertThat(blocked.state()).isEqualTo(Dhis2ExportState.PREPARED);
    assertThat(blocked.resultClass()).isEqualTo("TARGET_TUPLE_ACTIVE");
    assertThat(exchange.requestCount()).isEqualTo(1);

    var mapping =
        mappingStore
            .findVersion(ignoredOperation.mappingKey(), ignoredOperation.mappingVersion())
            .orElseThrow();
    var tuple = mapping.tupleFor(snapshot.period());
    var wrongTuple =
        new org.ayuvahak.analytics.domain.Dhis2DataValueTuple(
            "SYN-DATAELEMENT-999",
            tuple.period(),
            tuple.orgUnitUid(),
            tuple.categoryOptionComboUid(),
            tuple.attributeOptionComboUid());
    assertThatThrownBy(
            () ->
                reconciliation.reconcile(
                    adminContext(),
                    new Dhis2ExportReconciliationRequest(
                        operationId,
                        wrongTuple,
                        Dhis2ExportReconciliation.Resolution.CONFIRMED_APPLIED,
                        UUID.randomUUID(),
                        OptionalLong.of(snapshot.value().orElseThrow()))))
        .isInstanceOf(Dhis2ExportException.class)
        .extracting(failure -> ((Dhis2ExportException) failure).reason())
        .isEqualTo(Dhis2ExportException.Reason.NOT_AUTHORIZED);
    assertThat(operationStore.hasTargetTupleClaim(operationId)).isTrue();

    UUID evidenceReference = UUID.randomUUID();
    Dhis2ExportOperation resolved =
        reconciliation.reconcile(
            adminContext(),
            new Dhis2ExportReconciliationRequest(
                operationId,
                tuple,
                Dhis2ExportReconciliation.Resolution.CONFIRMED_APPLIED,
                evidenceReference,
                OptionalLong.of(snapshot.value().orElseThrow())));
    assertThat(resolved.state()).isEqualTo(Dhis2ExportState.ACKNOWLEDGED);
    assertThat(operationStore.hasTargetTupleClaim(operationId)).isFalse();
    assertThat(operationStore.findReconciliation(operationId)).isPresent();
    assertThat(operationStore.findReconciliation(operationId).orElseThrow().evidenceReference())
        .isEqualTo(evidenceReference);
    assertThat(
            disclosure
                .findReservation(
                    serviceContext(), ignoredOperation.disclosureReservationId().orElseThrow())
                .state())
        .isEqualTo(DisclosureReservationState.DISCLOSED);
    assertThat(exchange.requestCount()).isEqualTo(1);
  }

  @Test
  void unknownOutcomeRetainsTupleAcrossRestartAndReconciliationDoesNotBlindlyResend()
      throws Exception {
    AggregateSnapshot snapshot = prepareSnapshotAndMapping();
    exchange.failWith(new PartnerTransportException(PartnerTransportFailure.READ_TIMEOUT));
    exchange.blockNextResponse();
    UUID operationId = UUID.randomUUID();
    Dhis2ExportOperation unknown;
    try (ExecutorService workers = Executors.newSingleThreadExecutor()) {
      Future<Dhis2ExportOperation> initialAttempt =
          workers.submit(
              () ->
                  exporter.export(
                      serviceContext(),
                      new Dhis2ExportRequest(operationId, snapshot.snapshotId())));
      assertThat(exchange.awaitBlockedRequest()).isTrue();

      Dhis2AggregateExportService restarted = newExporter();
      unknown =
          restarted.export(
              serviceContext(), new Dhis2ExportRequest(operationId, snapshot.snapshotId()));
      assertThat(unknown.state()).isEqualTo(Dhis2ExportState.OUTCOME_UNKNOWN);
      assertThat(operationStore.hasTargetTupleClaim(operationId)).isTrue();
      assertThat(exchange.requestCount()).isEqualTo(1);

      Dhis2ExportOperation replay =
          restarted.export(
              serviceContext(), new Dhis2ExportRequest(operationId, snapshot.snapshotId()));
      assertThat(replay.state()).isEqualTo(Dhis2ExportState.OUTCOME_UNKNOWN);
      assertThat(exchange.requestCount()).isEqualTo(1);

      AggregateSnapshot corrected = correctedSnapshot(snapshot, 5);
      Dhis2ExportOperation newer = export(corrected);
      assertThat(newer.state()).isEqualTo(Dhis2ExportState.PREPARED);
      assertThat(newer.resultClass()).isEqualTo("TARGET_TUPLE_ACTIVE");
      assertThat(exchange.requestCount()).isEqualTo(1);

      exchange.releaseBlockedResponse();
      assertThat(initialAttempt.get(10, TimeUnit.SECONDS).state())
          .isEqualTo(Dhis2ExportState.OUTCOME_UNKNOWN);
    }

    var tuple = unknown.targetTuple();
    Dhis2ExportOperation resolved =
        reconciliation.reconcile(
            adminContext(),
            new Dhis2ExportReconciliationRequest(
                operationId,
                tuple,
                Dhis2ExportReconciliation.Resolution.CONFIRMED_NOT_APPLIED_AND_QUIESCED,
                UUID.randomUUID(),
                OptionalLong.empty()));
    assertThat(resolved.state()).isEqualTo(Dhis2ExportState.REJECTED);
    assertThat(operationStore.hasTargetTupleClaim(operationId)).isFalse();
    assertThat(
            disclosure
                .findReservation(serviceContext(), unknown.disclosureReservationId().orElseThrow())
                .state())
        .isEqualTo(DisclosureReservationState.POTENTIALLY_DISCLOSED);
    assertThat(exchange.requestCount()).isEqualTo(1);
  }

  @Test
  void duplicateOperationIdentityReplaysOnlySameFingerprintAndStaleRevisionCannotSend() {
    AggregateSnapshot snapshot = prepareSnapshotAndMapping();
    UUID operationId = UUID.randomUUID();
    exchange.respond(200, "application/json", updated());
    Dhis2ExportOperation acknowledged =
        exporter.export(
            serviceContext(), new Dhis2ExportRequest(operationId, snapshot.snapshotId()));
    assertThat(acknowledged.state()).isEqualTo(Dhis2ExportState.ACKNOWLEDGED);

    Dhis2ExportOperation replay =
        exporter.export(
            serviceContext(), new Dhis2ExportRequest(operationId, snapshot.snapshotId()));
    assertThat(replay.operationId()).isEqualTo(operationId);
    assertThat(replay.state()).isEqualTo(Dhis2ExportState.ACKNOWLEDGED);
    assertThat(exchange.requestCount()).isEqualTo(1);

    Dhis2ExportOperation stale = export(snapshot);
    assertThat(stale.state()).isEqualTo(Dhis2ExportState.REJECTED);
    assertThat(stale.resultClass()).isEqualTo("STALE_OR_DUPLICATE_REVISION");
    assertThat(exchange.requestCount()).isEqualTo(1);

    AggregateSnapshot changed = correctedSnapshot(snapshot, 5);
    assertThatThrownBy(
            () ->
                exporter.export(
                    serviceContext(), new Dhis2ExportRequest(operationId, changed.snapshotId())))
        .isInstanceOf(Dhis2ExportOperationConflictException.class);
    assertThat(exchange.requestCount()).isEqualTo(1);
  }

  @Test
  void completedOperationReplaysAfterPartnerRevocationButNewExportIsBlocked() {
    AggregateSnapshot snapshot = prepareSnapshotAndMapping();
    exchange.respond(200, "application/json", imported());
    UUID operationId = UUID.randomUUID();
    Dhis2ExportOperation completed =
        exporter.export(
            serviceContext(), new Dhis2ExportRequest(operationId, snapshot.snapshotId()));
    assertThat(completed.state()).isEqualTo(Dhis2ExportState.ACKNOWLEDGED);

    exchange.deactivateProfile();
    Dhis2ExportOperation replay =
        exporter.export(
            serviceContext(), new Dhis2ExportRequest(operationId, snapshot.snapshotId()));
    assertThat(replay.state()).isEqualTo(Dhis2ExportState.ACKNOWLEDGED);
    assertThat(exchange.requestCount()).isEqualTo(1);

    assertThatThrownBy(() -> export(snapshot))
        .isInstanceOf(Dhis2ExportException.class)
        .extracting(failure -> ((Dhis2ExportException) failure).reason())
        .isEqualTo(Dhis2ExportException.Reason.TARGET_PROFILE_UNAVAILABLE);
    assertThat(exchange.requestCount()).isEqualTo(1);
  }

  @Test
  void newerRevisionCannotOvertakeOlderPreparedOperationThatHasNotClaimedYet() {
    AggregateSnapshot original = prepareSnapshotAndMapping();
    var currentRevision = revisionStore.currentRevision(original.snapshotId()).orElseThrow();
    var targetMapping =
        mappingStore
            .activeMapping(
                original.indicatorKey(), original.indicatorVersion(), original.organization(), NOW)
            .orElseThrow();
    var tuple = targetMapping.tupleFor(original.period());
    UUID olderOperationId = UUID.randomUUID();
    operationStore.createOrReplay(
        new Dhis2ExportOperation(
            olderOperationId,
            "a".repeat(64),
            original.snapshotId(),
            currentRevision.seriesId(),
            currentRevision.revisionNumber(),
            targetMapping.mappingKey(),
            targetMapping.version(),
            targetMapping.partnerKey().value(),
            Optional.empty(),
            tuple,
            targetMapping.dataSetUid(),
            Optional.empty(),
            Dhis2ExportState.PREPARED,
            "PREPARED",
            0,
            Optional.empty(),
            Optional.empty(),
            NOW,
            NOW));

    AggregateSnapshot corrected = correctedSnapshot(original, 5);
    Dhis2ExportOperation newer = export(corrected);
    assertThat(newer.state()).isEqualTo(Dhis2ExportState.PREPARED);
    assertThat(newer.resultClass()).isEqualTo("OLDER_REVISION_PENDING");
    assertThat(operationStore.hasTargetTupleClaim(olderOperationId)).isFalse();
    assertThat(exchange.requestCount()).isZero();
  }

  @Test
  void classifiesPartnerAuthenticationValidationAndUncertainFailuresWithoutRetrying() {
    AggregateSnapshot snapshot = prepareSnapshotAndMapping();
    List<FailureExpectation> failures =
        List.of(
            new FailureExpectation(
                PartnerTransportFailure.REMOTE_AUTHENTICATION_FAILURE,
                Dhis2ExportState.REJECTED,
                "TARGET_AUTHENTICATION_FAILURE"),
            new FailureExpectation(
                PartnerTransportFailure.REMOTE_AUTHORIZATION_FAILURE,
                Dhis2ExportState.REJECTED,
                "TARGET_AUTHORIZATION_FAILURE"),
            new FailureExpectation(
                PartnerTransportFailure.REMOTE_4XX,
                Dhis2ExportState.REJECTED,
                "TARGET_METADATA_VALIDATION_FAILURE"),
            new FailureExpectation(
                PartnerTransportFailure.RATE_LIMITED,
                Dhis2ExportState.OUTCOME_UNKNOWN,
                "TARGET_RATE_LIMITED"),
            new FailureExpectation(
                PartnerTransportFailure.REMOTE_5XX,
                Dhis2ExportState.OUTCOME_UNKNOWN,
                "TARGET_REMOTE_FAILURE"),
            new FailureExpectation(
                PartnerTransportFailure.READ_TIMEOUT,
                Dhis2ExportState.OUTCOME_UNKNOWN,
                "TRANSPORT_OUTCOME_UNKNOWN"));

    for (int index = 0; index < failures.size(); index++) {
      FailureExpectation expected = failures.get(index);
      LocalDate periodStart = LocalDate.of(2026, index + 2, 1);
      AggregateSnapshot periodSnapshot =
          snapshotForPeriod(4, periodStart, periodStart.plusMonths(1));
      analyticsStore.saveOrReplay(periodSnapshot);
      revisionStore.registerInitial(
          periodSnapshot, UUID.randomUUID(), NOW, NOW.plusSeconds(30L * 24 * 60 * 60));
      exchange.failWith(new PartnerTransportException(expected.failure()));
      Dhis2ExportOperation operation = export(periodSnapshot);
      assertThat(operation.state()).isEqualTo(expected.state());
      assertThat(operation.resultClass()).isEqualTo(expected.resultClass());
    }

    assertThat(exchange.requestCount()).isEqualTo(failures.size());
  }

  @Test
  void unresolvedExportOwnsTheCompleteTargetTupleUntilItsOutcomeIsTerminal() throws Exception {
    AggregateSnapshot snapshot = prepareSnapshotAndMapping();
    exchange.respond(200, "application/json", imported());
    exchange.blockNextResponse();
    UUID firstOperationId = UUID.randomUUID();
    UUID competingOperationId = UUID.randomUUID();

    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Dhis2ExportOperation> first =
          workers.submit(
              () ->
                  exporter.export(
                      serviceContext(),
                      new Dhis2ExportRequest(firstOperationId, snapshot.snapshotId())));
      assertThat(exchange.awaitBlockedRequest()).isTrue();

      Dhis2ExportOperation competing =
          exporter.export(
              serviceContext(),
              new Dhis2ExportRequest(competingOperationId, snapshot.snapshotId()));

      Dhis2ExportOperation sameOperationReplay =
          exporter.export(
              serviceContext(), new Dhis2ExportRequest(firstOperationId, snapshot.snapshotId()));
      assertThat(sameOperationReplay.state()).isEqualTo(Dhis2ExportState.SENDING);

      assertThat(competing.state()).isEqualTo(Dhis2ExportState.PREPARED);
      assertThat(competing.resultClass()).isEqualTo("TARGET_TUPLE_ACTIVE");
      assertThat(exchange.requestCount()).isEqualTo(1);

      exchange.releaseBlockedResponse();
      assertThat(first.get(10, TimeUnit.SECONDS).state()).isEqualTo(Dhis2ExportState.ACKNOWLEDGED);
      assertThat(
              exporter
                  .export(
                      serviceContext(),
                      new Dhis2ExportRequest(firstOperationId, snapshot.snapshotId()))
                  .state())
          .isEqualTo(Dhis2ExportState.ACKNOWLEDGED);
      Dhis2ExportOperation completedTupleRetry =
          exporter.export(
              serviceContext(),
              new Dhis2ExportRequest(competingOperationId, snapshot.snapshotId()));
      assertThat(completedTupleRetry.state()).isEqualTo(Dhis2ExportState.REJECTED);
      assertThat(completedTupleRetry.resultClass()).isEqualTo("STALE_OR_DUPLICATE_REVISION");
    }

    assertThat(exchange.requestCount()).isEqualTo(1);
  }

  @Test
  void rejectsChangedMappingVersionAndRequiresASeparateVersionForTargetChanges() {
    prepareSnapshotAndMapping();
    Dhis2MappingVersion changed = mapping(1, "SYN-DATAELEMENT-002");
    assertThatThrownBy(() -> mappingAdministration.publish(adminContext(), changed))
        .isInstanceOf(Dhis2MappingConflictException.class);
    Dhis2MappingVersion nextVersion = mapping(2, "SYN-DATAELEMENT-002");
    assertThat(mappingAdministration.publish(adminContext(), nextVersion).version()).isEqualTo(2);
    assertThat(mappingStore.findVersion("SYN-MAPPING-001", 1)).isPresent();
    assertThat(mappingStore.findVersion("SYN-MAPPING-001", 2)).isPresent();
  }

  @Test
  void mappingRevisionCannotMoveAnIndicatorScopeToFreshDisclosureHistory() {
    prepareSnapshotAndMapping();
    Dhis2MappingVersion alternatePolicy =
        mapping("SYN-MAPPING-002", 1, "SYN-DATAELEMENT-002", "SYN-OTHER-DISCLOSURE-POLICY");

    assertThatThrownBy(() -> mappingAdministration.publish(adminContext(), alternatePolicy))
        .isInstanceOf(Dhis2MappingConflictException.class);
    assertThat(mappingStore.findVersion("SYN-MAPPING-002", 1)).isEmpty();
  }

  @Test
  void correctedSnapshotReusesDisclosureCellsAndCannotResetPriorReleaseHistory() {
    AggregateSnapshot original = prepareSnapshotAndMapping(4, true);
    exchange.respond(200, "application/json", imported());
    Dhis2ExportOperation priorRelease = export(original);
    assertThat(priorRelease.state()).isEqualTo(Dhis2ExportState.ACKNOWLEDGED);

    AggregateSnapshot corrected = correctedSnapshot(original, 5);
    Dhis2ExportOperation correction = export(corrected);

    assertThat(correction.state()).isEqualTo(Dhis2ExportState.WITHHELD);
    assertThat(correction.resultClass()).isEqualTo("DISCLOSURE_REVIEW_REQUIRED");
    assertThat(exchange.requestCount()).isEqualTo(1);
    assertThat(
            disclosure
                .findReservation(
                    serviceContext(), correction.disclosureReservationId().orElseThrow())
                .state())
        .isEqualTo(DisclosureReservationState.REVIEW_REQUIRED);
  }

  @Test
  void overlappingPeriodWithUnrepresentedSubperiodCellsFailsClosedAcrossMappingChange() {
    AggregateSnapshot january = prepareSnapshotAndMapping(4, true);
    exchange.respond(200, "application/json", imported());
    assertThat(export(january).state()).isEqualTo(Dhis2ExportState.ACKNOWLEDGED);

    mappingAdministration.deactivate(adminContext(), "SYN-MAPPING-001");
    Dhis2MappingVersion quarterlyMapping =
        mapping(
            "SYN-MAPPING-002", 1, "SYN-DATAELEMENT-002", POLICY_KEY, Dhis2PeriodFormat.QUARTERLY);
    mappingAdministration.publish(adminContext(), quarterlyMapping);
    AggregateSnapshot firstQuarter =
        snapshotForPeriod(9, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-04-01"));
    analyticsStore.saveOrReplay(firstQuarter);
    revisionStore.registerInitial(
        firstQuarter, UUID.randomUUID(), NOW, NOW.plusSeconds(30L * 24 * 60 * 60));

    Dhis2ExportOperation blocked = export(firstQuarter);

    assertThat(blocked.state()).isEqualTo(Dhis2ExportState.WITHHELD);
    assertThat(blocked.resultClass()).isEqualTo("DISCLOSURE_OVERLAP_REVIEW_REQUIRED");
    assertThat(exchange.requestCount()).isEqualTo(1);
  }

  @Test
  void samePeriodOutputKeyAndPolicyVersionRolloverCannotHidePriorRelease() {
    AggregateSnapshot snapshot = prepareSnapshotAndMapping(4, true);
    exchange.respond(200, "application/json", imported());
    assertThat(export(snapshot).state()).isEqualTo(Dhis2ExportState.ACKNOWLEDGED);

    UUID lineage =
        jdbc.queryForObject(
            "SELECT conservative_lineage_id FROM analytics.disclosure_policy_head WHERE policy_key = ?",
            UUID.class,
            POLICY_KEY);
    mappingAdministration.deactivate(adminContext(), "SYN-MAPPING-001");
    mappingAdministration.publish(
        adminContext(),
        mapping(
            "SYN-MAPPING-002",
            1,
            "SYN-DATAELEMENT-002",
            POLICY_KEY,
            Dhis2PeriodFormat.MONTHLY,
            "SYN-AGGREGATE-OUTPUT-V2"));
    disclosure.activatePolicy(
        adminContext(),
        new DisclosurePolicyVersion(
            POLICY_KEY,
            2,
            ORGANIZATION,
            1,
            true,
            lineage,
            Map.of(OUTPUT_KEY, Set.of(lineage), "SYN-AGGREGATE-OUTPUT-V2", Set.of(lineage))));

    Dhis2ExportOperation blocked = export(snapshot);

    assertThat(blocked.state()).isEqualTo(Dhis2ExportState.WITHHELD);
    assertThat(blocked.resultClass()).isEqualTo("DISCLOSURE_OVERLAP_REVIEW_REQUIRED");
    assertThat(exchange.requestCount()).isEqualTo(1);
  }

  @Test
  void disclosureCandidatePreservesTheImmutableAdditiveSourceCellEquation() {
    IndicatorDefinitionVersion definition =
        new IndicatorDefinitionVersion(
            "SYN-INDICATOR-001",
            1,
            "Synthetic monthly combined count",
            ZoneId.of("UTC"),
            List.of(
                SourceMeasure.referral("SUBMITTED_COUNT"),
                SourceMeasure.referral("RESOLVED_COUNT")),
            true);
    analyticsStore.publishDefinition(definition);
    SourceMeasure submitted = SourceMeasure.referral("SUBMITTED_COUNT");
    SourceMeasure resolved = SourceMeasure.referral("RESOLVED_COUNT");
    AggregateSnapshot snapshot =
        AggregateSnapshot.create(
            InternalId.generate(),
            definition,
            ORGANIZATION,
            org.ayuvahak.analytics.domain.ReportingPeriod.resolve(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"), ZoneId.of("UTC")),
            AggregateCompleteness.COMPLETE,
            OptionalLong.of(7),
            List.of(completeInput(submitted, 4), completeInput(resolved, 3)),
            Instant.parse("2026-02-03T00:00:00Z"));
    analyticsStore.saveOrReplay(snapshot);
    revisionStore.registerInitial(
        snapshot, UUID.randomUUID(), NOW, NOW.plusSeconds(30L * 24 * 60 * 60));
    mappingAdministration.publish(adminContext(), mapping(1, "SYN-DATAELEMENT-001"));
    UUID lineage = UUID.randomUUID();
    disclosure.activatePolicy(
        adminContext(),
        new DisclosurePolicyVersion(
            POLICY_KEY, 1, ORGANIZATION, 1, true, lineage, Map.of(OUTPUT_KEY, Set.of(lineage))));
    exchange.respond(200, "application/json", imported());

    Dhis2ExportOperation exported = export(snapshot);

    assertThat(exported.state()).isEqualTo(Dhis2ExportState.ACKNOWLEDGED);
    UUID reservationId = exported.disclosureReservationId().orElseThrow();
    List<String> cellKeys =
        jdbc.queryForList(
            "SELECT cell_key FROM analytics.disclosure_reservation_cell WHERE reservation_id = ? ORDER BY cell_key",
            String.class,
            reservationId);
    assertThat(cellKeys).hasSize(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT output_value FROM analytics.disclosure_reservation_output WHERE reservation_id = ?",
                Long.class,
                reservationId))
        .isEqualTo(7L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analytics.disclosure_reservation_term WHERE reservation_id = ?",
                Integer.class,
                reservationId))
        .isEqualTo(2);
  }

  @Test
  void exportFailsClosedBeforeCreatingAnOperationWhenSnapshotInputsDoNotExplainTheTotal() {
    IndicatorDefinitionVersion definition =
        new IndicatorDefinitionVersion(
            "SYN-INDICATOR-001",
            1,
            "Synthetic monthly combined count",
            ZoneId.of("UTC"),
            List.of(
                SourceMeasure.referral("SUBMITTED_COUNT"),
                SourceMeasure.referral("RESOLVED_COUNT")),
            true);
    analyticsStore.publishDefinition(definition);
    AggregateSnapshot snapshot =
        AggregateSnapshot.create(
            InternalId.generate(),
            definition,
            ORGANIZATION,
            org.ayuvahak.analytics.domain.ReportingPeriod.resolve(
                LocalDate.parse("2026-02-01"), LocalDate.parse("2026-03-01"), ZoneId.of("UTC")),
            AggregateCompleteness.COMPLETE,
            OptionalLong.of(8),
            List.of(
                completeInput(SourceMeasure.referral("SUBMITTED_COUNT"), 2),
                completeInput(SourceMeasure.referral("RESOLVED_COUNT"), 3)),
            NOW);
    analyticsStore.saveOrReplay(snapshot);
    revisionStore.registerInitial(
        snapshot, UUID.randomUUID(), NOW, NOW.plusSeconds(30L * 24 * 60 * 60));
    mappingAdministration.publish(adminContext(), mapping(1, "SYN-DATAELEMENT-001"));
    UUID lineage = UUID.randomUUID();
    disclosure.activatePolicy(
        adminContext(),
        new DisclosurePolicyVersion(
            POLICY_KEY, 1, ORGANIZATION, 1, true, lineage, Map.of(OUTPUT_KEY, Set.of(lineage))));

    assertThatThrownBy(
            () ->
                exporter.export(
                    serviceContext(),
                    new Dhis2ExportRequest(UUID.randomUUID(), snapshot.snapshotId())))
        .isInstanceOf(Dhis2ExportException.class)
        .extracting(failure -> ((Dhis2ExportException) failure).reason())
        .isEqualTo(Dhis2ExportException.Reason.DISCLOSURE_UNAVAILABLE);
    assertThat(exchange.requestCount()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analytics.dhis2_export_operation", Integer.class))
        .isZero();
  }

  @Test
  void refusesIncompleteSnapshotAndZeroNotConfiguredForTheTarget() {
    AggregateSnapshot snapshot = prepareSnapshotAndMapping(0, false);
    assertThatThrownBy(
            () ->
                exporter.export(
                    serviceContext(),
                    new Dhis2ExportRequest(UUID.randomUUID(), snapshot.snapshotId())))
        .isInstanceOf(Dhis2ExportException.class)
        .extracting(failure -> ((Dhis2ExportException) failure).reason())
        .isEqualTo(Dhis2ExportException.Reason.ZERO_NOT_ALLOWED);
    assertThat(exchange.requestCount()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analytics.dhis2_export_operation", Integer.class))
        .isZero();
  }

  private static Dhis2ExportOperation export(AggregateSnapshot snapshot) {
    return exporter.export(
        serviceContext(), new Dhis2ExportRequest(UUID.randomUUID(), snapshot.snapshotId()));
  }

  private static Dhis2AggregateExportService newExporter() {
    AuthorizationService authorization =
        new AuthorizationService(
            (actor, authority, scope) -> GRANTED_AUTHORITIES.contains(authority.code()),
            authorities());
    AuditWriter audit =
        new AuditWriter(new JdbcAuditRecordAppender(jdbc, transactionManager), CLOCK);
    return new Dhis2AggregateExportService(
        analyticsStore,
        revisionStore,
        mappingStore,
        operationStore,
        disclosure,
        new Dhis2DataValueSetMapper(new ObjectMapper()),
        new Dhis2ImportSummaryParser(new ObjectMapper()),
        exchange,
        authorization,
        audit,
        transactionManager,
        CLOCK);
  }

  private static AggregateSnapshot prepareSnapshotAndMapping() {
    return prepareSnapshotAndMapping(4, true);
  }

  private static AggregateSnapshot prepareSnapshotAndMapping(long value, boolean zeroSignificant) {
    IndicatorDefinitionVersion definition =
        new IndicatorDefinitionVersion(
            "SYN-INDICATOR-001",
            1,
            "Synthetic monthly count",
            ZoneId.of("UTC"),
            List.of(SourceMeasure.referral("SUBMITTED_COUNT")),
            true);
    analyticsStore.publishDefinition(definition);
    SourceMeasure measure = SourceMeasure.referral("SUBMITTED_COUNT");
    AggregateSnapshot snapshot =
        AggregateSnapshot.create(
            InternalId.generate(),
            definition,
            ORGANIZATION,
            org.ayuvahak.analytics.domain.ReportingPeriod.resolve(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"), ZoneId.of("UTC")),
            AggregateCompleteness.COMPLETE,
            OptionalLong.of(value),
            List.of(
                new AggregateInput(
                    measure,
                    Optional.of(UUID.randomUUID()),
                    Optional.of(Instant.parse("2026-02-01T00:00:00Z")),
                    Optional.of("referral-reporting-v1"),
                    AggregateCompleteness.COMPLETE,
                    OptionalLong.of(value),
                    Optional.of(Instant.parse("2026-02-02T00:00:00Z")))),
            Instant.parse("2026-02-03T00:00:00Z"));
    analyticsStore.saveOrReplay(snapshot);
    revisionStore.registerInitial(
        snapshot, UUID.randomUUID(), NOW, NOW.plusSeconds(30L * 24 * 60 * 60));
    mappingAdministration.publish(
        adminContext(), mapping(1, "SYN-DATAELEMENT-001", zeroSignificant));
    UUID lineage = UUID.randomUUID();
    disclosure.activatePolicy(
        adminContext(),
        new DisclosurePolicyVersion(
            POLICY_KEY, 1, ORGANIZATION, 1, true, lineage, Map.of(OUTPUT_KEY, Set.of(lineage))));
    return snapshot;
  }

  private static AggregateSnapshot correctedSnapshot(AggregateSnapshot previous, long value) {
    IndicatorDefinitionVersion definition =
        analyticsStore
            .findDefinition(previous.indicatorKey(), previous.indicatorVersion())
            .orElseThrow();
    AggregateSnapshot corrected =
        AggregateSnapshot.create(
            InternalId.generate(),
            definition,
            previous.organization(),
            previous.period(),
            AggregateCompleteness.COMPLETE,
            OptionalLong.of(value),
            List.of(completeInput(SourceMeasure.referral("SUBMITTED_COUNT"), value)),
            NOW.plusSeconds(1));
    analyticsStore.saveOrReplay(corrected);
    revisionStore.appendRevision(
        previous,
        corrected,
        new AppendAggregateSnapshotRevisionRequest(
            UUID.randomUUID(),
            previous.snapshotId(),
            1,
            corrected.snapshotId(),
            AnalyticsRevisionCause.LATE_OWNER_FACT),
        NOW.plusSeconds(1),
        true,
        true);
    return corrected;
  }

  private static AggregateSnapshot snapshotForPeriod(
      long value, LocalDate startInclusive, LocalDate endExclusive) {
    IndicatorDefinitionVersion definition =
        analyticsStore.findDefinition("SYN-INDICATOR-001", 1).orElseThrow();
    return AggregateSnapshot.create(
        InternalId.generate(),
        definition,
        ORGANIZATION,
        org.ayuvahak.analytics.domain.ReportingPeriod.resolve(
            startInclusive, endExclusive, ZoneId.of("UTC")),
        AggregateCompleteness.COMPLETE,
        OptionalLong.of(value),
        List.of(completeInput(SourceMeasure.referral("SUBMITTED_COUNT"), value)),
        NOW);
  }

  private static AggregateInput completeInput(SourceMeasure measure, long value) {
    return new AggregateInput(
        measure,
        Optional.of(UUID.randomUUID()),
        Optional.of(Instant.parse("2026-02-01T00:00:00Z")),
        Optional.of("synthetic-owner-reporting-v1"),
        AggregateCompleteness.COMPLETE,
        OptionalLong.of(value),
        Optional.of(Instant.parse("2026-02-02T00:00:00Z")));
  }

  private static Dhis2MappingVersion mapping(int version, String dataElementUid) {
    return mapping("SYN-MAPPING-001", version, dataElementUid, POLICY_KEY, true);
  }

  private static Dhis2MappingVersion mapping(
      int version, String dataElementUid, boolean zeroSignificant) {
    return mapping("SYN-MAPPING-001", version, dataElementUid, POLICY_KEY, zeroSignificant);
  }

  private static Dhis2MappingVersion mapping(
      String mappingKey, int version, String dataElementUid, String disclosurePolicyKey) {
    return mapping(mappingKey, version, dataElementUid, disclosurePolicyKey, true);
  }

  private static Dhis2MappingVersion mapping(
      String mappingKey,
      int version,
      String dataElementUid,
      String disclosurePolicyKey,
      boolean zeroSignificant) {
    return mapping(
        mappingKey,
        version,
        dataElementUid,
        disclosurePolicyKey,
        zeroSignificant,
        Dhis2PeriodFormat.MONTHLY);
  }

  private static Dhis2MappingVersion mapping(
      String mappingKey,
      int version,
      String dataElementUid,
      String disclosurePolicyKey,
      Dhis2PeriodFormat periodFormat) {
    return mapping(mappingKey, version, dataElementUid, disclosurePolicyKey, true, periodFormat);
  }

  private static Dhis2MappingVersion mapping(
      String mappingKey,
      int version,
      String dataElementUid,
      String disclosurePolicyKey,
      Dhis2PeriodFormat periodFormat,
      String disclosureOutputKey) {
    return new Dhis2MappingVersion(
        mappingKey,
        version,
        "SYN-INDICATOR-001",
        1,
        ORGANIZATION,
        new PartnerKey("synthetic-dhis2"),
        dataElementUid,
        "SYN-ORGUNIT-001",
        Optional.of("SYN-DATASET-001"),
        Optional.of("SYN-COC-001"),
        Optional.of("SYN-AOC-001"),
        periodFormat,
        disclosurePolicyKey,
        disclosureOutputKey,
        true,
        true,
        Instant.parse("2026-01-01T00:00:00Z"),
        Optional.empty());
  }

  private static Dhis2MappingVersion mapping(
      String mappingKey,
      int version,
      String dataElementUid,
      String disclosurePolicyKey,
      boolean zeroSignificant,
      Dhis2PeriodFormat periodFormat) {
    return new Dhis2MappingVersion(
        mappingKey,
        version,
        "SYN-INDICATOR-001",
        1,
        ORGANIZATION,
        new PartnerKey("synthetic-dhis2"),
        dataElementUid,
        "SYN-ORGUNIT-001",
        Optional.of("SYN-DATASET-001"),
        Optional.of("SYN-COC-001"),
        Optional.of("SYN-AOC-001"),
        periodFormat,
        disclosurePolicyKey,
        OUTPUT_KEY,
        true,
        zeroSignificant,
        Instant.parse("2026-01-01T00:00:00Z"),
        Optional.empty());
  }

  private static ExecutionContext serviceContext() {
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

  private static FunctionalAuthorityCatalog authorities() {
    return new FunctionalAuthorityCatalog(
        List.of(
            AnalyticsAuthorities.dhis2MappingAdministration(),
            AnalyticsAuthorities.dhis2Export(),
            AnalyticsAuthorities.dhis2ExportReconciliation(),
            AnalyticsAuthorities.disclosurePolicyAdministration(),
            AnalyticsAuthorities.disclosureRelease(),
            AnalyticsAuthorities.disclosureReservationLifecycle()));
  }

  private record FailureExpectation(
      PartnerTransportFailure failure, Dhis2ExportState state, String resultClass) {}

  private static String imported() {
    return "{\"importCount\":{\"imported\":1,\"updated\":0,\"ignored\":0,\"deleted\":0}}";
  }

  private static String updated() {
    return "{\"importCount\":{\"imported\":0,\"updated\":1,\"ignored\":0,\"deleted\":0}}";
  }

  private static String ignored() {
    return "{\"importCount\":{\"imported\":0,\"updated\":0,\"ignored\":1,\"deleted\":0}}";
  }

  private static final class RecordingExchange implements PartnerOutboundOperationPort {
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<PartnerOutboundOperationRequest> lastRequest =
        new AtomicReference<>();
    private final AtomicReference<PartnerOutboundOperationResponse> response =
        new AtomicReference<>();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> responseEntered = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> responseReleased = new AtomicReference<>();
    private final AtomicBoolean blockResponse = new AtomicBoolean();
    private final AtomicBoolean profileActive = new AtomicBoolean(true);

    @Override
    public int activeConfigurationVersion(
        org.ayuvahak.platform.exchange.domain.PartnerKey partnerKey,
        org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation
            operation) {
      if (!profileActive.get()) {
        throw new PartnerTransportException(PartnerTransportFailure.PROFILE_NOT_ACTIVE);
      }
      return 1;
    }

    @Override
    public PartnerOutboundOperationResponse execute(PartnerOutboundOperationRequest request) {
      calls.incrementAndGet();
      lastRequest.set(request);
      CountDownLatch entered = responseEntered.get();
      CountDownLatch released = responseReleased.get();
      if (entered != null && blockResponse.compareAndSet(true, false)) {
        entered.countDown();
        try {
          if (released == null || !released.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("local test response was not released");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("local test response was interrupted", interrupted);
        }
      }
      RuntimeException transportFailure = failure.getAndSet(null);
      if (transportFailure != null) {
        throw transportFailure;
      }
      return response.get();
    }

    void respond(int status, String contentType, String body) {
      response.set(
          new PartnerOutboundOperationResponse(
              status, Optional.of(contentType), body.getBytes(StandardCharsets.UTF_8), 1));
      failure.set(null);
    }

    void failWith(RuntimeException exception) {
      failure.set(exception);
    }

    void blockNextResponse() {
      responseEntered.set(new CountDownLatch(1));
      responseReleased.set(new CountDownLatch(1));
      blockResponse.set(true);
    }

    boolean awaitBlockedRequest() throws InterruptedException {
      CountDownLatch entered = responseEntered.get();
      return entered != null && entered.await(10, TimeUnit.SECONDS);
    }

    void releaseBlockedResponse() {
      CountDownLatch released = responseReleased.getAndSet(null);
      if (released != null) {
        released.countDown();
      }
    }

    int requestCount() {
      return calls.get();
    }

    void deactivateProfile() {
      profileActive.set(false);
    }

    Optional<PartnerOutboundOperationRequest> request() {
      return Optional.ofNullable(lastRequest.get());
    }

    void clear() {
      calls.set(0);
      lastRequest.set(null);
      failure.set(null);
      response.set(null);
      responseEntered.set(null);
      blockResponse.set(false);
      profileActive.set(true);
      CountDownLatch released = responseReleased.getAndSet(null);
      if (released != null) {
        released.countDown();
      }
    }
  }
}
