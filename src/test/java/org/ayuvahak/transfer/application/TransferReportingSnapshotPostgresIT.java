package org.ayuvahak.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.ayuvahak.platform.audit.adapter.persistence.JdbcAuditRecordAppender;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.authorization.adapter.persistence.JdbcAuthorizationGrantLookup;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.transfer.domain.TransferAuthorities;
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

/** Verifies the public Transfer reporting port against isolated PostgreSQL state. */
@Testcontainers
class TransferReportingSnapshotPostgresIT {

  private static final String PASSWORD = "local-test-only";
  private static final IssuerId ISSUER = new IssuerId("https://identity.example.test/realm");
  private static final ActorReference ANALYTICS =
      ActorReference.service(ISSUER, "synthetic-analytics-reporting");
  private static final ActorReference HUMAN = ActorReference.human(ISSUER, "SYN-USER-001");
  private static final ExecutionContextFactory CONTEXTS =
      new ExecutionContextFactory((actor, scope) -> actor != null && scope != null);
  private static final Instant PERIOD_START = Instant.parse("2026-06-01T00:00:00Z");
  private static final Instant PERIOD_END = Instant.parse("2026-07-01T00:00:00Z");

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
  private static TransferReportingSnapshotPort snapshots;

  @BeforeAll
  static void startLocalDatabaseAndMigrate() {
    POSTGRES.start();
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbc = new JdbcTemplate(dataSource);
    var transactionManager = new DataSourceTransactionManager(dataSource);
    var authorization =
        new AuthorizationService(
            new JdbcAuthorizationGrantLookup(jdbc),
            new FunctionalAuthorityCatalog(Set.of(TransferAuthorities.reportingSnapshots())));
    var audit =
        new AuditWriter(
            new JdbcAuditRecordAppender(jdbc, transactionManager), java.time.Clock.systemUTC());
    snapshots =
        new TransferReportingSnapshots(
            new org.ayuvahak.transfer.adapter.persistence.JdbcTransferReportingSnapshotStore(
                jdbc, transactionManager),
            authorization,
            audit,
            java.time.Clock.systemUTC());
  }

  @AfterAll
  static void stopLocalDatabase() {
    POSTGRES.stop();
  }

  @BeforeEach
  void clearRequestsAndSyntheticGrants() {
    jdbc.update("DELETE FROM transfer.reporting_snapshot");
    jdbc.update(
        "DELETE FROM identity_access.authorization_grant WHERE principal_identifier IN (?, ?)",
        ANALYTICS.identity().value(),
        HUMAN.identity().value());
  }

  @Test
  void returnsBoundedMeasuresWithOriginAndDestinationAttributionAndHalfOpenPeriod() {
    UUID originOrganization = id();
    UUID originFacility = id();
    UUID destinationOrganization = id();
    UUID destinationFacility = id();
    grant(ANALYTICS, organizationScope(originOrganization));
    grant(ANALYTICS, facilityScope(originOrganization, originFacility));
    grant(ANALYTICS, organizationScope(destinationOrganization));

    UUID directJourney =
        insertAcceptedJourney(
            originOrganization,
            originFacility,
            destinationOrganization,
            destinationFacility,
            Instant.parse("2026-06-10T12:00:00Z"),
            Instant.parse("2026-06-10T12:01:00Z"));
    insertCompletedFact(
        directJourney,
        originOrganization,
        originFacility,
        destinationOrganization,
        destinationFacility,
        Instant.parse("2026-06-20T12:00:00Z"),
        Instant.parse("2026-06-20T12:01:00Z"));
    insertUnfulfilledClosure(
        originOrganization,
        originFacility,
        Instant.parse("2026-06-25T12:00:00Z"),
        Instant.parse("2026-06-25T12:01:00Z"));

    // end is exclusive, even though the accepted decision was recorded before source cutoff.
    insertAcceptedJourney(
        originOrganization,
        originFacility,
        destinationOrganization,
        destinationFacility,
        PERIOD_END,
        Instant.parse("2026-06-30T12:00:00Z"));
    // An in-period occurrence that was recorded after the captured cutoff is not in this snapshot.
    insertAcceptedJourney(
        originOrganization,
        originFacility,
        destinationOrganization,
        destinationFacility,
        Instant.parse("2026-06-15T12:00:00Z"),
        Instant.now().plusSeconds(3600));

    var originAccepted =
        snapshot(
            context(ANALYTICS, organizationScope(originOrganization)),
            TransferReportingSnapshotPort.Measure.ACCEPTED,
            TransferReportingSnapshotPort.Attribution.ORIGIN);
    var destinationAccepted =
        snapshot(
            context(ANALYTICS, organizationScope(destinationOrganization)),
            TransferReportingSnapshotPort.Measure.ACCEPTED,
            TransferReportingSnapshotPort.Attribution.DESTINATION);
    var originFacilityAccepted =
        snapshot(
            context(ANALYTICS, facilityScope(originOrganization, originFacility)),
            TransferReportingSnapshotPort.Measure.ACCEPTED,
            TransferReportingSnapshotPort.Attribution.ORIGIN);
    var originCompleted =
        snapshot(
            context(ANALYTICS, organizationScope(originOrganization)),
            TransferReportingSnapshotPort.Measure.COMPLETED,
            TransferReportingSnapshotPort.Attribution.ORIGIN);
    var destinationCompleted =
        snapshot(
            context(ANALYTICS, organizationScope(destinationOrganization)),
            TransferReportingSnapshotPort.Measure.COMPLETED,
            TransferReportingSnapshotPort.Attribution.DESTINATION);
    var originUnfulfilled =
        snapshot(
            context(ANALYTICS, organizationScope(originOrganization)),
            TransferReportingSnapshotPort.Measure.UNFULFILLED,
            TransferReportingSnapshotPort.Attribution.ORIGIN);

    assertSnapshot(originAccepted, 1, originOrganization, PERIOD_START, PERIOD_END);
    assertSnapshot(destinationAccepted, 1, destinationOrganization, PERIOD_START, PERIOD_END);
    assertSnapshot(originFacilityAccepted, 1, originOrganization, PERIOD_START, PERIOD_END);
    assertThat(originFacilityAccepted.scope().kind())
        .isEqualTo(org.ayuvahak.platform.executioncontext.ExecutionScope.Kind.FACILITY);
    assertSnapshot(originCompleted, 1, originOrganization, PERIOD_START, PERIOD_END);
    assertSnapshot(destinationCompleted, 1, destinationOrganization, PERIOD_START, PERIOD_END);
    assertSnapshot(originUnfulfilled, 1, originOrganization, PERIOD_START, PERIOD_END);
    assertThat(originAccepted.sourceCutoff()).isBefore(Instant.now().plusSeconds(3600));
    assertThat(originAccepted.sourceSnapshotId()).isNotNull();
    assertThat(originAccepted.ownerContractVersion()).isEqualTo("transfer-reporting-v1");
    assertThat(originAccepted.toString())
        .doesNotContain(directJourney.toString())
        .doesNotContain("PatientReference")
        .doesNotContain("clinical");
  }

  @Test
  void receiverOwnedFederatedAcceptanceIsCountedOnceOnlyAtItsDestination() {
    UUID originOrganization = id();
    UUID originFacility = id();
    UUID destinationOrganization = id();
    UUID destinationFacility = id();
    UUID journey =
        insertFederatedOriginAcceptance(
            originOrganization, originFacility, Instant.parse("2026-06-12T10:00:00Z"));
    UUID receivingOperationId = id();
    insertFederatedReceivingAcceptance(
        journey,
        destinationOrganization,
        destinationFacility,
        receivingOperationId,
        Instant.parse("2026-06-12T10:00:00Z"));
    // Receiver request identity is peer-scoped; replay must leave one counted receiving fact.
    insertFederatedReceivingAcceptance(
        journey,
        destinationOrganization,
        destinationFacility,
        receivingOperationId,
        Instant.parse("2026-06-12T10:00:00Z"));
    grant(ANALYTICS, organizationScope(originOrganization));
    grant(ANALYTICS, organizationScope(destinationOrganization));

    var origin =
        snapshot(
            context(ANALYTICS, organizationScope(originOrganization)),
            TransferReportingSnapshotPort.Measure.ACCEPTED,
            TransferReportingSnapshotPort.Attribution.ORIGIN);
    var destination =
        snapshot(
            context(ANALYTICS, organizationScope(destinationOrganization)),
            TransferReportingSnapshotPort.Measure.ACCEPTED,
            TransferReportingSnapshotPort.Attribution.DESTINATION);
    var wrongSide =
        snapshot(
            context(ANALYTICS, organizationScope(originOrganization)),
            TransferReportingSnapshotPort.Measure.ACCEPTED,
            TransferReportingSnapshotPort.Attribution.DESTINATION);

    assertThat(origin.value()).isEqualTo(1);
    assertThat(destination.value()).isEqualTo(1);
    assertThat(wrongSide.value()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.federated_receiving_request "
                    + "WHERE origin_deployment_id = 'SYN-DEPLOYMENT-A' AND request_operation_id = ?",
                Integer.class,
                receivingOperationId))
        .isEqualTo(1);
  }

  @Test
  void exactRequestReplayReturnsTheImmutableSnapshotAndChangedRequestConflicts() {
    UUID originOrganization = id();
    UUID originFacility = id();
    grant(ANALYTICS, organizationScope(originOrganization));
    insertUnfulfilledClosure(
        originOrganization,
        originFacility,
        Instant.parse("2026-06-20T12:00:00Z"),
        Instant.parse("2026-06-20T12:01:00Z"));
    var requestId = InternalId.generate();
    var query =
        new TransferReportingSnapshotPort.SnapshotRequest(
            requestId,
            TransferReportingSnapshotPort.Measure.UNFULFILLED,
            TransferReportingSnapshotPort.Attribution.ORIGIN,
            PERIOD_START,
            PERIOD_END);
    ExecutionContext context = context(ANALYTICS, organizationScope(originOrganization));

    var first = snapshots.snapshot(context, query);
    var replay = snapshots.snapshot(context, query);
    var changed =
        new TransferReportingSnapshotPort.SnapshotRequest(
            requestId,
            TransferReportingSnapshotPort.Measure.ACCEPTED,
            TransferReportingSnapshotPort.Attribution.ORIGIN,
            PERIOD_START,
            PERIOD_END);

    assertThat(replay).isEqualTo(first);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.reporting_snapshot WHERE request_id = ?",
                Integer.class,
                requestId.value()))
        .isEqualTo(1);
    assertThatThrownBy(() -> snapshots.snapshot(context, changed))
        .isInstanceOf(org.ayuvahak.platform.api.PlatformProblemException.class);
  }

  @Test
  void completeZeroIsDistinctFromDeniedOrUnsupportedAttribution() {
    UUID organization = id();
    grant(ANALYTICS, organizationScope(organization));
    var context = context(ANALYTICS, organizationScope(organization));

    var zero =
        snapshot(
            context,
            TransferReportingSnapshotPort.Measure.COMPLETED,
            TransferReportingSnapshotPort.Attribution.ORIGIN);

    assertSnapshot(zero, 0, organization, PERIOD_START, PERIOD_END);
    var unsupported =
        new TransferReportingSnapshotPort.SnapshotRequest(
            InternalId.generate(),
            TransferReportingSnapshotPort.Measure.UNFULFILLED,
            TransferReportingSnapshotPort.Attribution.DESTINATION,
            PERIOD_START,
            PERIOD_END);
    assertThatThrownBy(() -> snapshots.snapshot(context, unsupported))
        .isInstanceOf(org.ayuvahak.platform.api.PlatformProblemException.class);

    var noGrantContext = context(ANALYTICS, organizationScope(id()));
    assertThatThrownBy(
            () ->
                snapshot(
                    noGrantContext,
                    TransferReportingSnapshotPort.Measure.COMPLETED,
                    TransferReportingSnapshotPort.Attribution.ORIGIN))
        .isInstanceOf(org.ayuvahak.platform.api.PlatformProblemException.class);
  }

  @Test
  void humanCannotUseTheMachineReportingContractEvenWithAnAuthorityGrant() {
    UUID organization = id();
    grant(HUMAN, organizationScope(organization));
    ExecutionContext humanContext = context(HUMAN, organizationScope(organization));

    assertThatThrownBy(
            () ->
                snapshot(
                    humanContext,
                    TransferReportingSnapshotPort.Measure.ACCEPTED,
                    TransferReportingSnapshotPort.Attribution.ORIGIN))
        .isInstanceOf(org.ayuvahak.platform.api.PlatformProblemException.class);
  }

  private TransferReportingSnapshotPort.Snapshot snapshot(
      ExecutionContext context,
      TransferReportingSnapshotPort.Measure measure,
      TransferReportingSnapshotPort.Attribution attribution) {
    return snapshots.snapshot(
        context,
        new TransferReportingSnapshotPort.SnapshotRequest(
            InternalId.generate(), measure, attribution, PERIOD_START, PERIOD_END));
  }

  private static void assertSnapshot(
      TransferReportingSnapshotPort.Snapshot value,
      long expectedValue,
      UUID expectedOrganization,
      Instant periodStart,
      Instant periodEnd) {
    assertThat(value.value()).isEqualTo(expectedValue);
    assertThat(value.scope().organization().orElseThrow().value())
        .isEqualTo(expectedOrganization.toString());
    assertThat(value.periodStart()).isEqualTo(periodStart);
    assertThat(value.periodEnd()).isEqualTo(periodEnd);
    assertThat(value.completeness()).isEqualTo(TransferReportingSnapshotPort.Completeness.COMPLETE);
    assertThat(value.sourceCutoff()).isNotNull();
    assertThat(value.generatedAt()).isNotNull();
    assertThat(value.dimensions()).containsEntry("attribution", value.attribution().name());
  }

  private UUID insertAcceptedJourney(
      UUID originOrganization,
      UUID originFacility,
      UUID destinationOrganization,
      UUID destinationFacility,
      Instant occurredAt,
      Instant recordedAt) {
    UUID journeyId = id();
    UUID referralId = id();
    UUID attemptId = id();
    UUID transferId = id();
    jdbc.update(
        "INSERT INTO transfer.journey (journey_id, referral_id, origin_organization_id, origin_facility_id, "
            + "created_by_kind, created_by_issuer, created_by_identifier_type, created_by_identifier, created_at, version) "
            + "VALUES (?, ?, ?, ?, 'SERVICE', 'https://identity.example.test/realm', 'client_id', 'synthetic-fixture', ?, 1)",
        journeyId,
        referralId,
        originOrganization,
        originFacility,
        TimestampValue.now());
    jdbc.update(
        "INSERT INTO transfer.request_attempt (request_attempt_id, journey_id, attempt_number, referral_id, "
            + "referral_revision, recommendation_id, destination_facility_id, request_status, requested_by_kind, "
            + "requested_by_issuer, requested_by_identifier_type, requested_by_identifier, requested_at, expires_at, version) "
            + "VALUES (?, ?, 1, ?, 1, ?, ?, 'ACCEPTED', 'SERVICE', 'https://identity.example.test/realm', "
            + "'client_id', 'synthetic-fixture', ?, ?, 2)",
        attemptId,
        journeyId,
        referralId,
        id(),
        destinationFacility,
        timestamp(occurredAt.minusSeconds(60)),
        timestamp(occurredAt.plusSeconds(3600)));
    jdbc.update(
        "INSERT INTO transfer.transfer_record (transfer_id, journey_id, request_attempt_id, transfer_status, created_at, version) "
            + "VALUES (?, ?, ?, 'AWAITING_RESERVATION', ?, 1)",
        transferId,
        journeyId,
        attemptId,
        timestamp(occurredAt));
    jdbc.update(
        "INSERT INTO transfer.request_decision (decision_id, request_attempt_id, decision_kind, reason_code, transfer_id, "
            + "recorded_by_kind, recorded_by_issuer, recorded_by_identifier_type, recorded_by_identifier, authority_code, "
            + "receiving_organization_id, receiving_facility_id, occurred_at, recorded_at, request_version) "
            + "VALUES (?, ?, 'ACCEPTED', NULL, ?, 'SERVICE', 'https://identity.example.test/realm', 'client_id', "
            + "'synthetic-fixture', 'AcceptanceAuthority', ?, ?, ?, ?, 2)",
        id(),
        attemptId,
        transferId,
        destinationOrganization,
        destinationFacility,
        timestamp(occurredAt),
        timestamp(recordedAt));
    return journeyId;
  }

  private void insertCompletedFact(
      UUID journeyId,
      UUID originOrganization,
      UUID originFacility,
      UUID destinationOrganization,
      UUID destinationFacility,
      Instant occurredAt,
      Instant recordedAt) {
    UUID transferId =
        jdbc.queryForObject(
            "SELECT transfer_id FROM transfer.transfer_record WHERE journey_id = ?",
            UUID.class,
            journeyId);
    jdbc.update(
        "UPDATE transfer.transfer_record SET transfer_status = 'COMPLETED', version = 2 WHERE transfer_id = ?",
        transferId);
    jdbc.update(
        "INSERT INTO transfer.active_destination (transfer_id, organization_id, facility_id, reservation_operation_id, "
            + "version, updated_at) VALUES (?, ?, ?, ?, 1, ?)",
        transferId,
        destinationOrganization,
        destinationFacility,
        id(),
        timestamp(recordedAt));
    jdbc.update(
        "INSERT INTO transfer.progress_fact (progress_fact_id, transfer_id, journey_id, fact_kind, previous_status, "
            + "transfer_status, reason_code, recorded_by_kind, recorded_by_issuer, recorded_by_identifier_type, "
            + "recorded_by_identifier, authority_code, organization_id, facility_id, occurred_at, recorded_at, "
            + "transfer_version, aggregate_sequence) VALUES (?, ?, ?, 'COMPLETION', 'HANDED_OVER', 'COMPLETED', "
            + "'SYNTHETIC_COMPLETE', 'SERVICE', 'https://identity.example.test/realm', 'client_id', 'synthetic-fixture', "
            + "'TransferCompletionAuthority', ?, ?, ?, ?, 2, 2)",
        id(),
        transferId,
        journeyId,
        destinationOrganization,
        destinationFacility,
        timestamp(occurredAt),
        timestamp(recordedAt));
  }

  private void insertUnfulfilledClosure(
      UUID originOrganization, UUID originFacility, Instant occurredAt, Instant recordedAt) {
    UUID journeyId = id();
    UUID referralId = id();
    jdbc.update(
        "INSERT INTO transfer.journey (journey_id, referral_id, origin_organization_id, origin_facility_id, "
            + "created_by_kind, created_by_issuer, created_by_identifier_type, created_by_identifier, created_at, version) "
            + "VALUES (?, ?, ?, ?, 'SERVICE', 'https://identity.example.test/realm', 'client_id', 'synthetic-fixture', ?, 1)",
        journeyId,
        referralId,
        originOrganization,
        originFacility,
        timestamp(occurredAt.minusSeconds(3600)));
    jdbc.update(
        "INSERT INTO transfer.journey_closure (journey_id, referral_id, outcome, reason_code, closed_by_kind, "
            + "closed_by_issuer, closed_by_identifier_type, closed_by_identifier, authority_code, organization_id, "
            + "facility_id, occurred_at, recorded_at, aggregate_sequence) VALUES (?, ?, 'UNFULFILLED', 'SYNTHETIC_NO_MATCH', "
            + "'SERVICE', 'https://identity.example.test/realm', 'client_id', 'synthetic-fixture', "
            + "'TransferJourneyClosureAuthority', ?, ?, ?, ?, 1)",
        journeyId,
        referralId,
        originOrganization,
        originFacility,
        timestamp(occurredAt),
        timestamp(recordedAt));
  }

  private UUID insertFederatedOriginAcceptance(
      UUID originOrganization, UUID originFacility, Instant acceptedAt) {
    UUID journeyId = id();
    UUID referralId = id();
    UUID attemptId = id();
    UUID operationId = id();
    UUID requestId = id();
    Instant recordedAt = Instant.now().minusSeconds(20);
    jdbc.update(
        "INSERT INTO transfer.journey (journey_id, referral_id, origin_organization_id, origin_facility_id, "
            + "created_by_kind, created_by_issuer, created_by_identifier_type, created_by_identifier, created_at, version) "
            + "VALUES (?, ?, ?, ?, 'SERVICE', 'https://identity.example.test/realm', 'client_id', 'synthetic-fixture', ?, 1)",
        journeyId,
        referralId,
        originOrganization,
        originFacility,
        timestamp(recordedAt.minusSeconds(3600)));
    jdbc.update(
        "INSERT INTO transfer.federated_origin_receiving_attempt (origin_attempt_id, journey_id, attempt_number, "
            + "origin_deployment_id, peer_deployment_id, partner_key, partner_configuration_version, operation_id, "
            + "request_fingerprint, command_fingerprint, request_body, operation_state, remote_outcome, "
            + "remote_receiving_request_id, remote_capacity_reservation_id, remote_reservation_status, remote_accepted_at, "
            + "remote_source_updated_at, remote_version, attempt_version, created_at, updated_at) "
            + "VALUES (?, ?, 1, 'SYN-DEPLOYMENT-A', 'SYN-DEPLOYMENT-B', 'SYN-PARTNER-B', 1, ?, ?, ?, "
            + "'{}'::bytea, 'RESOLVED', 'ACCEPTED_RESERVED', ?, ?, 'RESERVED', ?, ?, 2, 2, ?, ?)",
        attemptId,
        journeyId,
        operationId,
        "a".repeat(64),
        "b".repeat(64),
        requestId,
        id(),
        timestamp(acceptedAt),
        timestamp(recordedAt),
        timestamp(recordedAt.minusSeconds(10)),
        timestamp(recordedAt));
    return journeyId;
  }

  private void insertFederatedReceivingAcceptance(
      UUID originJourney,
      UUID destinationOrganization,
      UUID destinationFacility,
      UUID operationId,
      Instant acceptedAt) {
    Instant recordedAt = Instant.now().minusSeconds(10);
    jdbc.update(
        "INSERT INTO transfer.federated_receiving_request (receiving_request_id, origin_deployment_id, "
            + "request_operation_id, request_fingerprint, origin_journey_id, origin_facility_id, "
            + "destination_organization_issuer, destination_organization_id, destination_facility_issuer, "
            + "destination_facility_id, capacity_dimension_code, capacity_unit, capacity_quantity, "
            + "capacity_interval_start, capacity_interval_end, request_state, accepted_by_issuer, accepted_by_type, "
            + "accepted_by_id, accepted_at, capacity_operation_id, capacity_reservation_status, capacity_reservation_id, "
            + "capacity_lifecycle_sequence, request_version, received_at, updated_at) "
            + "VALUES (?, 'SYN-DEPLOYMENT-A', ?, ?, ?, ?, 'urn:ayuvahak:directory', ?, 'urn:ayuvahak:directory', ?, "
            + "'SYN-BED', 'SYN-BED', 1, ?, ?, 'ACCEPTED_RESERVED', 'https://identity.example.test/realm', "
            + "'client_id', 'synthetic-receiver', ?, ?, 'RESERVED', ?, 1, 2, ?, ?) "
            + "ON CONFLICT (origin_deployment_id, request_operation_id) DO NOTHING",
        id(),
        operationId,
        "c".repeat(64),
        originJourney,
        "SYN-FACILITY-A",
        destinationOrganization,
        destinationFacility,
        timestamp(PERIOD_START),
        timestamp(PERIOD_END),
        timestamp(acceptedAt),
        id(),
        id(),
        timestamp(recordedAt.minusSeconds(60)),
        timestamp(recordedAt));
  }

  private void grant(ActorReference actor, RequestedExecutionScope scope) {
    ExternalReference organization;
    ExternalReference facility = null;
    String kind;
    if (scope instanceof RequestedExecutionScope.Organization org) {
      kind = "ORGANIZATION";
      organization = org.organization();
    } else if (scope instanceof RequestedExecutionScope.Facility f) {
      kind = "FACILITY";
      organization = f.organization();
      facility = f.facility();
    } else {
      throw new IllegalArgumentException("reporting grant must be organization or facility scoped");
    }
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, principal_issuer, "
            + "principal_identifier_type, principal_identifier, authority_code, scope_kind, organization_issuer, "
            + "organization_identifier_type, organization_identifier, facility_issuer, facility_identifier_type, "
            + "facility_identifier, grant_status, created_at, revoked_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
            + "'ACTIVE', CURRENT_TIMESTAMP, NULL)",
        id(),
        actor.kind().name(),
        actor.identity().issuer().value(),
        actor.identity().resourceType(),
        actor.identity().value(),
        TransferAuthorities.reportingSnapshots().code(),
        kind,
        organization.issuer().value(),
        organization.resourceType(),
        organization.value(),
        facility == null ? null : facility.issuer().value(),
        facility == null ? null : facility.resourceType(),
        facility == null ? null : facility.value());
  }

  private static ExecutionContext context(ActorReference actor, RequestedExecutionScope scope) {
    return actor.kind() == ActorKind.SERVICE
        ? CONTEXTS.forIntegrationRequest(actor, scope, CorrelationId.generate())
        : CONTEXTS.forInteractiveRequest(actor, scope, CorrelationId.generate());
  }

  private static RequestedExecutionScope organizationScope(UUID organizationId) {
    return RequestedExecutionScope.organization(
        new ExternalReference(
            new IssuerId("urn:ayuvahak:directory"), "Organization", organizationId.toString()));
  }

  private static RequestedExecutionScope facilityScope(UUID organizationId, UUID facilityId) {
    return RequestedExecutionScope.facility(
        new ExternalReference(
            new IssuerId("urn:ayuvahak:directory"), "Organization", organizationId.toString()),
        new ExternalReference(
            new IssuerId("urn:ayuvahak:directory"), "Facility", facilityId.toString()));
  }

  private static UUID id() {
    return UUID.randomUUID();
  }

  private static java.sql.Timestamp timestamp(Instant instant) {
    return java.sql.Timestamp.from(instant);
  }

  private static final class TimestampValue {
    private static java.sql.Timestamp now() {
      return java.sql.Timestamp.from(Instant.now().minusSeconds(120));
    }
  }
}
