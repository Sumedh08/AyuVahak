package org.ayuvahak.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.ayuvahak.analytics.adapter.persistence.JdbcAnalyticsRevisionStore;
import org.ayuvahak.analytics.adapter.persistence.JdbcAnalyticsStore;
import org.ayuvahak.analytics.adapter.persistence.JdbcDisclosureReservationStore;
import org.ayuvahak.analytics.adapter.persistence.JdbcFederatedAggregateMappingStore;
import org.ayuvahak.analytics.adapter.persistence.JdbcFederatedAggregatePublicationStore;
import org.ayuvahak.analytics.adapter.persistence.JdbcFederatedAggregateReceiptStore;
import org.ayuvahak.analytics.domain.AggregateCompleteness;
import org.ayuvahak.analytics.domain.AggregateInput;
import org.ayuvahak.analytics.domain.AggregateSnapshot;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.analytics.domain.DisclosureCandidate;
import org.ayuvahak.analytics.domain.DisclosureOutput;
import org.ayuvahak.analytics.domain.DisclosurePolicyVersion;
import org.ayuvahak.analytics.domain.DisclosureReservation;
import org.ayuvahak.analytics.domain.DisclosureReservationState;
import org.ayuvahak.analytics.domain.FederatedAggregateEnvelope;
import org.ayuvahak.analytics.domain.FederatedAggregateMappingVersion;
import org.ayuvahak.analytics.domain.FederatedAggregatePublication;
import org.ayuvahak.analytics.domain.FederatedAggregateReceipt;
import org.ayuvahak.analytics.domain.FederatedAggregateSnapshot;
import org.ayuvahak.analytics.domain.IndicatorDefinitionGovernance;
import org.ayuvahak.analytics.domain.IndicatorDefinitionVersion;
import org.ayuvahak.analytics.domain.ReportingPeriod;
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
import org.ayuvahak.platform.exchange.application.FederationOperationFingerprint;
import org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorizationException;
import org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorizationService;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationPort;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationResponse;
import org.ayuvahak.platform.exchange.application.PartnerTrustProfileStore;
import org.ayuvahak.platform.exchange.domain.FederationPeerConfiguration;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerProfileState;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.RetryClass;
import org.ayuvahak.platform.federation.adapter.persistence.JdbcDeploymentIdentityStore;
import org.ayuvahak.platform.federation.application.DeploymentIdentityRegistrationService;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
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

/** Local PostgreSQL acceptance for trusted aggregate receipts and the shared disclosure gate. */
@Testcontainers
class FederatedAggregateFederationPostgresIT {

  private static final String PASSWORD = "local-test-only";
  private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final DeploymentId SOURCE_DEPLOYMENT = new DeploymentId("SYN-DEPLOYMENT-A");
  private static final DeploymentId LOCAL_DEPLOYMENT = new DeploymentId("SYN-DEPLOYMENT-B");
  private static final PartnerKey PEER_KEY = new PartnerKey("SYNTHETIC-PEER-A");
  private static final IssuerId SERVICE_ISSUER =
      new IssuerId("https://identity.example.test/realm");
  private static final ActorReference PEER_PRINCIPAL =
      ActorReference.service(new IssuerId("urn:synthetic:peer-auth"), "SYN-PEER-SERVICE-A");
  private static final ExternalReference LOCAL_ORGANIZATION =
      new ExternalReference(
          new IssuerId("urn:ayuvahak:directory"),
          "Organization",
          "10000000-0000-4000-8000-000000000001");
  private static final ExternalReference SOURCE_ORGANIZATION =
      new ExternalReference(
          new IssuerId(FederatedAggregateMappingVersion.deploymentNamespace(SOURCE_DEPLOYMENT)),
          "Organization",
          "SYN-ORG-SOURCE-001");
  private static final ExternalReference TARGET_ORGANIZATION =
      new ExternalReference(
          new IssuerId(FederatedAggregateMappingVersion.deploymentNamespace(LOCAL_DEPLOYMENT)),
          "Organization",
          LOCAL_ORGANIZATION.value());
  private static final AllowedOperation INBOUND_OPERATION =
      AllowedOperation.analyticsAggregateSnapshot(PartnerTrustConfiguration.Direction.INBOUND);
  private static final String POLICY_KEY = "SYN-FEDERATED-REPORTING-A";
  private static final String DHIS2_OUTPUT = "SYN-DHIS2-CELL-A";
  private static final String FEDERATION_OUTPUT = "SYN-FEDERATED-TOTAL";
  private static final String P08_ACTION = "ANALYTICS_FEDERATED_AGGREGATE_%";
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
  private static DeploymentIdentityRegistrationService localIdentity;
  private static FederatedAggregateReceiptService receiptService;
  private static AuthorizationService analyticsAuthorization;
  private static AnalyticsDisclosureReservationService disclosure;
  private static org.ayuvahak.platform.executioncontext.ExecutionContextFactory contexts;

  @BeforeAll
  static void startLocalDatabaseAndMigrate() {
    POSTGRES.start();
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbc = new JdbcTemplate(dataSource);
    transactionManager = new DataSourceTransactionManager(dataSource);
    localIdentity =
        new DeploymentIdentityRegistrationService(new JdbcDeploymentIdentityStore(jdbc));
    localIdentity.register(
        LOCAL_DEPLOYMENT, "SYNTHETIC-LOCAL-INSTALLATION-BINDING-0000000001", NOW);
    contexts =
        new org.ayuvahak.platform.executioncontext.ExecutionContextFactory(
            (actor, scope) -> actor != null && scope != null);
    AuthorizationGrantLookup grants =
        (actor, authority, scope) -> AUTHORITIES.contains(authority.code());
    analyticsAuthorization =
        new AuthorizationService(
            grants,
            new FunctionalAuthorityCatalog(
                List.of(
                    AnalyticsAuthorities.disclosurePolicyAdministration(),
                    AnalyticsAuthorities.disclosureRelease(),
                    AnalyticsAuthorities.disclosureReservationLifecycle(),
                    AnalyticsAuthorities.disclosureReview())));
    AuditWriter audit =
        new AuditWriter(new JdbcAuditRecordAppender(jdbc, transactionManager), CLOCK);
    disclosure =
        new AnalyticsDisclosureReservationService(
            new JdbcDisclosureReservationStore(jdbc),
            analyticsAuthorization,
            audit,
            transactionManager,
            CLOCK,
            (reservationId, attemptId) -> false);
  }

  @AfterAll
  static void stopLocalDatabase() {
    POSTGRES.stop();
  }

  @BeforeEach
  void clearSyntheticFixtures() {
    jdbc.update("TRUNCATE analytics.federated_aggregate_mapping_head CASCADE");
    jdbc.update("TRUNCATE analytics.indicator_definition_version CASCADE");
    jdbc.update(
        "TRUNCATE analytics.disclosure_privacy_scope, analytics.disclosure_policy_head CASCADE");
    jdbc.update("DELETE FROM audit.audit_record WHERE action_code LIKE ?", P08_ACTION);
    AnalyticsStore analytics = new JdbcAnalyticsStore(jdbc, transactionManager);
    IndicatorDefinitionVersion definition = definition();
    analytics.publishDefinition(definition);
    FederatedAggregateMappingStore mappings =
        new JdbcFederatedAggregateMappingStore(jdbc, transactionManager);
    mappings.publish(inboundMapping(definition), NOW);
    receiptService = receiptService(mappings, analytics);
  }

  @Test
  void authenticatedPeerReceiptReplaysExactlyAndConflictsOnChangedRequestOrContent() {
    FederatedAggregateEnvelope envelope = envelope(7);
    byte[] requestBytes =
        "SYNTHETIC-AGGREGATE-ENVELOPE".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    FederationOperationFingerprint fingerprint = fingerprint(requestBytes);
    UUID operationId = UUID.randomUUID();
    var peer = authorizePeer(PartnerProfileState.ACTIVE);

    FederatedAggregateReceipt first =
        receiptService.receive(peer, operationId, fingerprint, envelope);
    FederatedAggregateReceipt replay =
        receiptService.receive(peer, operationId, fingerprint, envelope);

    assertThat(first.state()).isEqualTo(FederatedAggregateReceipt.State.COMBINABLE);
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.receiptId()).isEqualTo(first.receiptId());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analytics.federated_aggregate_receipt", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_record WHERE action_code = 'ANALYTICS_FEDERATED_AGGREGATE_RECEIVED'",
                Integer.class))
        .isEqualTo(1);

    assertThatThrownBy(
            () ->
                receiptService.receive(
                    peer,
                    operationId,
                    fingerprint(
                        "CHANGED-REQUEST".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    envelope))
        .isInstanceOfSatisfying(
            FederatedAggregateException.class,
            failure ->
                assertThat(failure.reason())
                    .isEqualTo(FederatedAggregateException.Reason.RECEIPT_CONFLICT));
    assertThatThrownBy(
            () ->
                receiptService.receive(
                    peer,
                    operationId,
                    fingerprint(
                        "CHANGED-CONTENT".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    envelope(8)))
        .isInstanceOfSatisfying(
            FederatedAggregateException.class,
            failure ->
                assertThat(failure.reason())
                    .isEqualTo(FederatedAggregateException.Reason.RECEIPT_CONFLICT));
  }

  @Test
  void receiptRejectsWrongTimezoneCalendarAndEffectivePeriod() {
    var peer = authorizePeer(PartnerProfileState.ACTIVE);

    assertIncompatible(
        peer,
        ReportingPeriod.resolve(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), ZoneId.of("Asia/Kolkata")));
    assertIncompatible(
        peer,
        ReportingPeriod.resolve(
            LocalDate.of(2026, 1, 2), LocalDate.of(2026, 2, 2), ZoneId.of("UTC")));
    assertIncompatible(
        peer,
        ReportingPeriod.resolve(
            LocalDate.of(2025, 12, 1), LocalDate.of(2026, 1, 1), ZoneId.of("UTC")));

    assertThat(
            jdbc.queryForList(
                "SELECT receipt_state FROM analytics.federated_aggregate_receipt "
                    + "ORDER BY received_at"))
        .extracting(row -> row.get("receipt_state"))
        .containsOnly("INCOMPATIBLE");
  }

  @Test
  void receiptRejectsUtcBoundariesThatDoNotMatchTheRecordedZoneRules() {
    var peer = authorizePeer(PartnerProfileState.ACTIVE);
    ReportingPeriod valid =
        ReportingPeriod.resolve(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), ZoneId.of("UTC"));
    ReportingPeriod forged =
        ReportingPeriod.restore(
            valid.startInclusiveLocal(),
            valid.endExclusiveLocal(),
            valid.timeZoneId(),
            valid.startInclusiveUtc().plusSeconds(3_600),
            valid.endExclusiveUtc().plusSeconds(3_600),
            valid.timeZoneRulesVersion());

    assertIncompatible(peer, forged);
  }

  @Test
  void revokedOrWrongOperationPeerCannotReachAggregateReceipt() {
    var revokedAuthorization = peerAuthorizationService(PartnerProfileState.REVOKED);
    assertThatThrownBy(
            () ->
                revokedAuthorization.authorize(
                    PEER_PRINCIPAL, SOURCE_DEPLOYMENT, INBOUND_OPERATION, LOCAL_ORGANIZATION))
        .isInstanceOf(FederationPeerInboundAuthorizationException.class);
    assertThatThrownBy(
            () ->
                peerAuthorizationService(PartnerProfileState.ACTIVE)
                    .authorize(
                        PEER_PRINCIPAL,
                        SOURCE_DEPLOYMENT,
                        AllowedOperation.capacityRemoteEvidence(
                            PartnerTrustConfiguration.Direction.INBOUND),
                        LOCAL_ORGANIZATION))
        .isInstanceOf(FederationPeerInboundAuthorizationException.class);
  }

  @Test
  void reviewedDisclosureResumesTheSameFederatedPublication() {
    IndicatorDefinitionVersion definition = sensitiveDefinition();
    AnalyticsStore analytics = new JdbcAnalyticsStore(jdbc, transactionManager);
    analytics.publishDefinition(definition);
    SourceMeasure measure = SourceMeasure.referral("SUBMITTED_COUNT");
    AggregateSnapshot sourceSnapshot =
        analytics.saveOrReplay(
            AggregateSnapshot.create(
                InternalId.generate(),
                definition,
                LOCAL_ORGANIZATION,
                ReportingPeriod.resolve(
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), ZoneId.of("UTC")),
                AggregateCompleteness.COMPLETE,
                java.util.OptionalLong.of(8),
                List.of(
                    new AggregateInput(
                        measure,
                        Optional.of(UUID.randomUUID()),
                        Optional.of(NOW.minusSeconds(3600)),
                        Optional.of("referral-reporting-v1"),
                        AggregateCompleteness.COMPLETE,
                        java.util.OptionalLong.of(8),
                        Optional.of(NOW.minusSeconds(60)))),
                NOW));
    JdbcAnalyticsRevisionStore revisions = new JdbcAnalyticsRevisionStore(jdbc);
    var revision =
        revisions.registerInitial(
            sourceSnapshot, UUID.randomUUID(), NOW, NOW.plus(Duration.ofDays(7)));
    ExternalReference receivingOrganization =
        new ExternalReference(
            new IssuerId(FederatedAggregateMappingVersion.deploymentNamespace(SOURCE_DEPLOYMENT)),
            "Organization",
            "SYN-ORG-RECEIVER-001");
    FederatedAggregateMappingVersion mapping =
        new FederatedAggregateMappingVersion(
            "SYN-OUTBOUND-REVIEW-MAP",
            1,
            FederatedAggregateMappingVersion.Direction.OUTBOUND,
            definition.key(),
            definition.version(),
            definition.fingerprint(),
            LOCAL_ORGANIZATION,
            PEER_KEY,
            SOURCE_DEPLOYMENT,
            1,
            receivingOrganization,
            POLICY_KEY,
            FEDERATION_OUTPUT,
            NOW.minusSeconds(3600),
            Optional.empty());
    JdbcFederatedAggregateMappingStore mappings =
        new JdbcFederatedAggregateMappingStore(jdbc, transactionManager);
    mappings.publish(mapping, NOW);
    UUID lineage = UUID.randomUUID();
    disclosure.activatePolicy(
        adminContext(),
        new DisclosurePolicyVersion(
            POLICY_KEY,
            1,
            LOCAL_ORGANIZATION,
            1,
            false,
            lineage,
            Map.of(FEDERATION_OUTPUT, Set.of(lineage))));

    FederatedAggregateSnapshot wire =
        FederatedAggregateSnapshot.from(sourceSnapshot, definition, revision, LOCAL_DEPLOYMENT);
    var mapper =
        new org.ayuvahak.analytics.adapter.federation.FederatedAggregateEnvelopeJsonMapper(
            new ObjectMapper());
    int[] sends = {0};
    FederationPeerOperationPort localPeer =
        request -> {
          sends[0]++;
          return new PartnerOutboundOperationResponse(
              200,
              Optional.of("application/json"),
              mapper.encodeAcknowledgement(
                  new FederatedAggregateReceipt(
                      UUID.randomUUID(),
                      wire,
                      request.operationId().value(),
                      receivingOrganization,
                      PEER_KEY,
                      1,
                      Optional.of(mapping.mappingKey()),
                      Optional.of(mapping.version()),
                      FederatedAggregateReceipt.State.COMBINABLE,
                      "COMPATIBLE",
                      NOW,
                      false),
                  SOURCE_DEPLOYMENT),
              1);
        };
    FederatedAggregatePublicationService publisher =
        publicationService(analytics, revisions, mappings, localPeer, mapper, CLOCK);
    FederatedAggregatePublicationRequest request =
        new FederatedAggregatePublicationRequest(sourceSnapshot.snapshotId(), mapping.mappingKey());

    FederatedAggregatePublication held = publisher.publish(releaseContext(), request);

    assertThat(held.state()).isEqualTo(FederatedAggregatePublication.State.WITHHELD);
    assertThat(held.disclosureReservationId()).isPresent();
    assertThat(sends[0]).isZero();
    var reservation =
        disclosure.findReservation(releaseContext(), held.disclosureReservationId().orElseThrow());
    assertThat(reservation.state()).isEqualTo(DisclosureReservationState.REVIEW_REQUIRED);
    disclosure.resolveReviewRequired(
        adminContext(),
        reservation.reservationId(),
        reservation.operationFingerprint(),
        DisclosureReviewResolution.APPROVE,
        UUID.randomUUID());

    FederatedAggregatePublication published = publisher.publish(releaseContext(), request);

    assertThat(published.operationId()).isEqualTo(held.operationId());
    assertThat(published.state()).isEqualTo(FederatedAggregatePublication.State.ACKNOWLEDGED);
    assertThat(sends[0]).isEqualTo(1);
    assertThat(
            disclosure.findReservation(releaseContext(), reservation.reservationId()).state())
        .isEqualTo(DisclosureReservationState.DISCLOSED);
  }

  @Test
  void dhis2AndPeerReleasesSerializeThroughTheSamePostgresDisclosureLineage() throws Exception {
    UUID lineage = UUID.randomUUID();
    DisclosurePolicyVersion policy =
        new DisclosurePolicyVersion(
            POLICY_KEY,
            1,
            LOCAL_ORGANIZATION,
            5,
            true,
            lineage,
            Map.of(DHIS2_OUTPUT, Set.of(lineage), FEDERATION_OUTPUT, Set.of(lineage)));
    disclosure.activatePolicy(adminContext(), policy);

    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<DisclosureReservation> dhis2 =
          workers.submit(() -> reserveAfter(start, release(DHIS2_OUTPUT)));
      Future<DisclosureReservation> federation =
          workers.submit(() -> reserveAfter(start, release(FEDERATION_OUTPUT)));
      start.countDown();
      List<DisclosureReservation> decisions =
          List.of(dhis2.get(30, TimeUnit.SECONDS), federation.get(30, TimeUnit.SECONDS));

      assertThat(decisions)
          .extracting(DisclosureReservation::state)
          .containsExactlyInAnyOrder(
              DisclosureReservationState.RESERVED, DisclosureReservationState.WITHHELD);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM analytics.disclosure_reservation WHERE state = 'RESERVED'",
                  Integer.class))
          .isEqualTo(1);
    }
  }

  @Test
  void recoversPersistedSendingPublicationAfterRestartUsingSamePeerOperationIdentity() {
    IndicatorDefinitionVersion definition = definition();
    AnalyticsStore analytics = new JdbcAnalyticsStore(jdbc, transactionManager);
    analytics.publishDefinition(definition);
    JdbcAnalyticsRevisionStore revisions = new JdbcAnalyticsRevisionStore(jdbc);
    UUID computationId = UUID.randomUUID();
    AggregateSnapshot snapshot =
        AggregateSnapshot.create(
            new InternalId(computationId),
            definition,
            LOCAL_ORGANIZATION,
            ReportingPeriod.resolve(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), ZoneId.of("UTC")),
            AggregateCompleteness.COMPLETE,
            java.util.OptionalLong.of(8),
            List.of(
                new AggregateInput(
                    SourceMeasure.referral("SUBMITTED_COUNT"),
                    Optional.of(UUID.randomUUID()),
                    Optional.of(NOW.minusSeconds(3600)),
                    Optional.of("referral-reporting-v1"),
                    AggregateCompleteness.COMPLETE,
                    java.util.OptionalLong.of(8),
                    Optional.of(NOW.minusSeconds(60)))),
            NOW);
    AggregateSnapshot sourceSnapshot = analytics.saveOrReplay(snapshot);
    revisions.registerInitial(sourceSnapshot, UUID.randomUUID(), NOW, NOW.plus(Duration.ofDays(7)));

    UUID lineage = UUID.randomUUID();
    DisclosurePolicyVersion policy =
        new DisclosurePolicyVersion(
            POLICY_KEY,
            1,
            LOCAL_ORGANIZATION,
            1,
            true,
            lineage,
            Map.of("SYN-FEDERATED-OUT", Set.of(lineage)));
    disclosure.activatePolicy(adminContext(), policy);

    DeploymentId receiving = SOURCE_DEPLOYMENT;
    ExternalReference receivingOrganization =
        new ExternalReference(
            new IssuerId(FederatedAggregateMappingVersion.deploymentNamespace(receiving)),
            "Organization",
            "SYN-ORG-RECEIVER-001");
    FederatedAggregateMappingVersion mapping =
        new FederatedAggregateMappingVersion(
            "SYN-OUTBOUND-AGGREGATE-MAP",
            1,
            FederatedAggregateMappingVersion.Direction.OUTBOUND,
            definition.key(),
            definition.version(),
            definition.fingerprint(),
            LOCAL_ORGANIZATION,
            PEER_KEY,
            receiving,
            1,
            receivingOrganization,
            POLICY_KEY,
            "SYN-FEDERATED-OUT",
            NOW.minusSeconds(60),
            Optional.empty());
    JdbcFederatedAggregateMappingStore mappings =
        new JdbcFederatedAggregateMappingStore(jdbc, transactionManager);
    mappings.publish(mapping, NOW);

    FederatedAggregateSnapshot wire =
        FederatedAggregateSnapshot.from(
            sourceSnapshot,
            definition,
            revisions.currentRevision(sourceSnapshot.snapshotId()).orElseThrow(),
            LOCAL_DEPLOYMENT);
    var mapper =
        new org.ayuvahak.analytics.adapter.federation.FederatedAggregateEnvelopeJsonMapper(
            new ObjectMapper());
    Map<UUID, PartnerOutboundOperationResponse> remoteReceipts = new HashMap<>();
    List<UUID> sentIds = new ArrayList<>();
    List<String> sentFingerprints = new ArrayList<>();
    FederationPeerOperationPort localPeer =
        request -> {
          UUID operationId = request.operationId().value();
          sentIds.add(operationId);
          sentFingerprints.add(request.fingerprint().value());
          PartnerOutboundOperationResponse prior = remoteReceipts.get(operationId);
          if (prior != null) {
            return prior;
          }
          PartnerOutboundOperationResponse committed =
              new PartnerOutboundOperationResponse(
                  200,
                  Optional.of("application/json"),
                  mapper.encodeAcknowledgement(
                      new FederatedAggregateReceipt(
                          UUID.randomUUID(),
                          wire,
                          operationId,
                          receivingOrganization,
                          PEER_KEY,
                          1,
                          Optional.of(mapping.mappingKey()),
                          Optional.of(mapping.version()),
                          FederatedAggregateReceipt.State.COMBINABLE,
                          "COMPATIBLE",
                          NOW,
                          false),
                      receiving),
                  1);
          remoteReceipts.put(operationId, committed);
          throw new AssertionError("synthetic peer committed and dropped the response");
        };

    MutableClock clock = new MutableClock(NOW);
    FederatedAggregatePublicationService firstProcess =
        publicationService(analytics, revisions, mappings, localPeer, mapper, clock);
    assertThatThrownBy(
            () ->
                firstProcess.publish(
                    releaseContext(),
                    new FederatedAggregatePublicationRequest(
                        sourceSnapshot.snapshotId(), mapping.mappingKey())))
        .isInstanceOf(AssertionError.class);
    JdbcFederatedAggregatePublicationStore publicationStore =
        new JdbcFederatedAggregatePublicationStore(jdbc, transactionManager);
    UUID operationId =
        jdbc.queryForObject(
            "SELECT operation_id FROM analytics.federated_aggregate_publication "
                + "WHERE source_snapshot_id = ? AND mapping_key = ? AND mapping_version = 1",
            UUID.class,
            sourceSnapshot.snapshotId(),
            mapping.mappingKey());
    FederatedAggregatePublication sending = publicationStore.find(operationId).orElseThrow();
    assertThat(sending.state()).isEqualTo(FederatedAggregatePublication.State.SENDING);

    clock.advance(Duration.ofMinutes(3));
    FederatedAggregatePublication recovered =
        publicationService(analytics, revisions, mappings, localPeer, mapper, clock)
            .publish(
                releaseContext(),
                new FederatedAggregatePublicationRequest(
                    sourceSnapshot.snapshotId(), mapping.mappingKey()));

    assertThat(recovered.state()).isEqualTo(FederatedAggregatePublication.State.ACKNOWLEDGED);
    assertThat(recovered.operationId()).isEqualTo(sending.operationId());
    assertThat(sentIds).containsExactly(sending.operationId(), sending.operationId());
    assertThat(sentFingerprints).hasSize(2).containsOnly(sentFingerprints.getFirst());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analytics.federated_aggregate_publication "
                    + "WHERE source_snapshot_id = ? AND mapping_key = ?",
                Integer.class,
                sourceSnapshot.snapshotId(),
                mapping.mappingKey()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM analytics.disclosure_reservation WHERE reservation_id = ?",
                String.class,
                recovered.disclosureReservationId().orElseThrow()))
        .isEqualTo("DISCLOSED");
  }

  private static DisclosureReservation reserveAfter(
      CountDownLatch start, DisclosureCandidate candidate) throws InterruptedException {
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("test workers did not start");
    }
    return disclosure.reserve(releaseContext(), candidate);
  }

  private static DisclosureCandidate release(String outputKey) {
    Map<String, Long> cells = Map.of("SYN-CELL-A", 9L, "SYN-CELL-B", 1L);
    DisclosureOutput output =
        outputKey.equals(DHIS2_OUTPUT)
            ? new DisclosureOutput(outputKey, 9, Map.of("SYN-CELL-A", 1L))
            : new DisclosureOutput(outputKey, 10, Map.of("SYN-CELL-A", 1L, "SYN-CELL-B", 1L));
    return new DisclosureCandidate(POLICY_KEY, 1, UUID.randomUUID(), cells, List.of(output));
  }

  private FederatedAggregatePublicationService publicationService(
      AnalyticsStore analytics,
      JdbcAnalyticsRevisionStore revisions,
      JdbcFederatedAggregateMappingStore mappings,
      FederationPeerOperationPort exchange,
      org.ayuvahak.analytics.adapter.federation.FederatedAggregateEnvelopeJsonMapper mapper,
      Clock clock) {
    List<org.ayuvahak.platform.authorization.domain.FunctionalAuthority> authorities =
        List.of(
            AnalyticsAuthorities.disclosurePolicyAdministration(),
            AnalyticsAuthorities.disclosureRelease(),
            AnalyticsAuthorities.disclosureReservationLifecycle(),
            AnalyticsAuthorities.federatedAggregatePublication());
    AuthorizationService authorization =
        new AuthorizationService(
            (actor, authority, scope) -> true, new FunctionalAuthorityCatalog(authorities));
    return new FederatedAggregatePublicationService(
        analytics,
        revisions,
        mappings,
        new JdbcFederatedAggregatePublicationStore(jdbc, transactionManager),
        disclosure,
        mapper,
        exchange,
        localIdentity,
        authorization,
        new AuditWriter(new JdbcAuditRecordAppender(jdbc, transactionManager), clock),
        transactionManager,
        clock);
  }

  private FederatedAggregateReceiptService receiptService(
      FederatedAggregateMappingStore mappings, AnalyticsStore analytics) {
    AuditWriter audit =
        new AuditWriter(new JdbcAuditRecordAppender(jdbc, transactionManager), CLOCK);
    return new FederatedAggregateReceiptService(
        mappings,
        new JdbcFederatedAggregateReceiptStore(jdbc),
        analytics,
        localIdentity,
        audit,
        transactionManager,
        CLOCK);
  }

  private FederationPeerInboundAuthorizationService peerAuthorizationService(
      PartnerProfileState state) {
    PartnerProfileVersion profile = inboundProfile(state);
    PartnerTrustProfileStore profiles =
        new PartnerTrustProfileStore() {
          @Override
          public Optional<PartnerProfileVersion> findActiveInboundPeer(
              ActorReference principal,
              DeploymentId peerDeployment,
              AllowedOperation operation,
              ExternalReference requestedScope) {
            return profile.configuration().inboundPrincipal().filter(principal::equals).isPresent()
                    && profile
                        .configuration()
                        .federationPeer()
                        .orElseThrow()
                        .deploymentId()
                        .equals(peerDeployment)
                    && profile.configuration().allowedOperations().contains(operation)
                    && profile
                        .configuration()
                        .federationPeer()
                        .orElseThrow()
                        .permits(profile.configuration().organization(), requestedScope)
                ? Optional.of(profile)
                : Optional.empty();
          }

          @Override
          public void configureBoundedReceiptWait() {
            throw new UnsupportedOperationException();
          }

          @Override
          public PartnerProfileVersion createInitial(
              PartnerTrustConfiguration config, Instant now) {
            throw new UnsupportedOperationException();
          }

          @Override
          public PartnerProfileVersion createNextVersion(
              PartnerTrustConfiguration config, int expectedCurrentVersion, Instant now) {
            throw new UnsupportedOperationException();
          }

          @Override
          public PartnerProfileVersion activate(PartnerKey key, int version, Instant now) {
            throw new UnsupportedOperationException();
          }

          @Override
          public PartnerProfileVersion suspend(PartnerKey key, Instant now) {
            throw new UnsupportedOperationException();
          }

          @Override
          public PartnerProfileVersion revoke(PartnerKey key, Instant now) {
            throw new UnsupportedOperationException();
          }

          @Override
          public List<PartnerProfileVersion> versions(PartnerKey key) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Optional<PartnerProfileVersion> findById(InternalId id) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Optional<PartnerProfileVersion> findActive(
              PartnerKey key, AllowedOperation operation) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Optional<PartnerProfileVersion> findActiveInbound(
              ActorReference principal, AllowedOperation operation) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Optional<PartnerProfileVersion> findActivePeer(
              PartnerKey key,
              DeploymentId peerDeployment,
              AllowedOperation operation,
              ExternalReference requestedScope) {
            throw new UnsupportedOperationException();
          }

          @Override
          public PartnerTrustProfileStore.ReceiptClaim claimReceipt(
              CommandReceiptKey key, RequestFingerprint fingerprint, Instant now) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void completeReceipt(
              CommandReceiptKey key, int status, InternalId resultVersion, Instant now) {
            throw new UnsupportedOperationException();
          }
        };
    return new FederationPeerInboundAuthorizationService(profiles, localIdentity);
  }

  private static PartnerProfileVersion inboundProfile(PartnerProfileState state) {
    Instant now = NOW.minusSeconds(60);
    PartnerTrustConfiguration configuration =
        new PartnerTrustConfiguration(
            PEER_KEY,
            LOCAL_ORGANIZATION,
            "https://synthetic-peer-a.example.test",
            "/api/federation",
            new PartnerTrustConfiguration.Authentication(
                PartnerTrustConfiguration.AuthenticationMethod.OAUTH_CLIENT_CREDENTIALS,
                Optional.of("env:SYNTHETIC-CLIENT-SECRET"),
                Optional.of("env:SYNTHETIC-OAUTH-KEYS"),
                Optional.of("https://identity.synthetic.example.test"),
                Optional.of("/oauth/token"),
                Optional.of("https://identity.synthetic.example.test/issuer"),
                Optional.of("SYNTHETIC-AUDIENCE"),
                Optional.of("SYNTHETIC-CLIENT")),
            PartnerTrustConfiguration.TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            new PartnerTrustConfiguration.Limits(
                1_000, 2_000, 5_000, 100_000, 200_000, 2, 20, 8, 1),
            RetryClass.NEVER,
            List.of(INBOUND_OPERATION),
            Optional.of(PEER_PRINCIPAL),
            Optional.of(
                new FederationPeerConfiguration(
                    SOURCE_DEPLOYMENT,
                    LOCAL_ORGANIZATION,
                    FederationPeerConfiguration.supportedProtocolProfile(),
                    List.of())));
    return new PartnerProfileVersion(InternalId.generate(), 1, state, configuration, now, now);
  }

  private static FederatedAggregateMappingVersion inboundMapping(
      IndicatorDefinitionVersion definition) {
    return new FederatedAggregateMappingVersion(
        "SYN-AGGREGATE-MAP-A",
        1,
        FederatedAggregateMappingVersion.Direction.INBOUND,
        definition.key(),
        definition.version(),
        definition.fingerprint(),
        SOURCE_ORGANIZATION,
        PEER_KEY,
        SOURCE_DEPLOYMENT,
        1,
        LOCAL_ORGANIZATION,
        POLICY_KEY,
        FEDERATION_OUTPUT,
        NOW.minusSeconds(3600),
        Optional.empty());
  }

  private static IndicatorDefinitionVersion definition() {
    return new IndicatorDefinitionVersion(
        "SYN-INDICATOR-001",
        1,
        "Synthetic aggregate for local federation acceptance",
        org.ayuvahak.analytics.domain.AggregationFormula.SUM,
        ZoneId.of("UTC"),
        List.of(SourceMeasure.referral("SUBMITTED_COUNT")),
        true,
        List.of(),
        new IndicatorDefinitionGovernance(
            "Synthetic monthly reporting purpose",
            Instant.parse("2026-01-01T00:00:00Z"),
            Optional.of(Instant.parse("2027-01-01T00:00:00Z")),
            IndicatorDefinitionGovernance.ReportingCalendar.MONTHLY,
            Set.of(IndicatorDefinitionGovernance.ReportingDimension.ORGANIZATION),
            IndicatorDefinitionGovernance.CompletenessRequirement
                .ALL_REQUIRED_SOURCES_COMPLETE_FOR_NUMERIC_VALUE,
            Optional.empty(),
            IndicatorDefinitionGovernance.SensitivityClassification.LOW_SENSITIVITY_AGGREGATE,
            Optional.empty(),
            Optional.empty()));
  }

  private static IndicatorDefinitionVersion sensitiveDefinition() {
    IndicatorDefinitionVersion base = definition();
    IndicatorDefinitionGovernance governance = base.governance();
    return new IndicatorDefinitionVersion(
        "SYN-INDICATOR-REVIEW-001",
        base.version(),
        base.description(),
        base.formula(),
        base.reportingTimeZone(),
        base.sourceMeasures(),
        base.zeroAllowed(),
        base.denominatorMeasures(),
        new IndicatorDefinitionGovernance(
            governance.ownerApprovedPurpose(),
            governance.effectiveFrom(),
            governance.effectiveUntil(),
            governance.reportingCalendar(),
            governance.allowedDimensions(),
            governance.completenessRequirement(),
            governance.maximumSourceAge(),
            IndicatorDefinitionGovernance.SensitivityClassification.SENSITIVE_AGGREGATE,
            governance.requiredDisclosurePolicyKey(),
            governance.authorityDefinition()));
  }

  private static FederatedAggregateEnvelope envelope(long value) {
    return envelope(
        value,
        ReportingPeriod.resolve(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), ZoneId.of("UTC")));
  }

  private static FederatedAggregateEnvelope envelope(long value, ReportingPeriod period) {
    FederatedAggregateSnapshot snapshot =
        new FederatedAggregateSnapshot(
            SOURCE_DEPLOYMENT,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "SYN-INDICATOR-001",
            1,
            definition().fingerprint(),
            "a".repeat(64),
            SOURCE_ORGANIZATION,
            period,
            AggregateCompleteness.COMPLETE,
            value,
            SourceMeasure.Attribution.NONE,
            NOW.minusSeconds(30));
    return new FederatedAggregateEnvelope(snapshot, TARGET_ORGANIZATION);
  }

  private void assertIncompatible(
      org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorization peer,
      ReportingPeriod period) {
    byte[] requestBytes =
        UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    FederatedAggregateReceipt receipt =
        receiptService.receive(
            peer, UUID.randomUUID(), fingerprint(requestBytes), envelope(7, period));
    assertThat(receipt.state()).isEqualTo(FederatedAggregateReceipt.State.INCOMPATIBLE);
  }

  private org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorization
      authorizePeer(PartnerProfileState state) {
    return peerAuthorizationService(state)
        .authorize(PEER_PRINCIPAL, SOURCE_DEPLOYMENT, INBOUND_OPERATION, LOCAL_ORGANIZATION);
  }

  private static FederationOperationFingerprint fingerprint(byte[] bytes) {
    return FederationOperationFingerprint.calculate(
        SOURCE_DEPLOYMENT, LOCAL_DEPLOYMENT, TARGET_ORGANIZATION, INBOUND_OPERATION, bytes);
  }

  private static org.ayuvahak.platform.executioncontext.ExecutionContext releaseContext() {
    return contexts.forBackgroundWork(
        ActorReference.service(SERVICE_ISSUER, "synthetic-analytics-disclosure-worker"),
        org.ayuvahak.platform.executioncontext.RequestedExecutionScope.organization(
            LOCAL_ORGANIZATION),
        new CorrelationId(UUID.randomUUID().toString()),
        Optional.empty());
  }

  private static org.ayuvahak.platform.executioncontext.ExecutionContext adminContext() {
    return contexts.forInteractiveRequest(
        ActorReference.human(SERVICE_ISSUER, "synthetic-analytics-privacy-admin"),
        org.ayuvahak.platform.executioncontext.RequestedExecutionScope.organization(
            LOCAL_ORGANIZATION),
        new CorrelationId(UUID.randomUUID().toString()));
  }

  private static final class MutableClock extends Clock {

    private final java.util.concurrent.atomic.AtomicReference<Instant> current;

    private MutableClock(Instant initial) {
      current = new java.util.concurrent.atomic.AtomicReference<>(initial);
    }

    private void advance(Duration duration) {
      current.updateAndGet(value -> value.plus(duration));
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current.get();
    }
  }
}
