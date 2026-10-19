package org.ayuvahak.transfer.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.exchange.application.FederationOperationFingerprint;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.IdempotencyKeyDigest;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.application.FederatedOriginReceivingAttemptStore;
import org.ayuvahak.transfer.application.TransferStore;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class FederatedReceivingMigrationUpgradeIT {

  private static final String TEST_PASSWORD = "local-test-only";
  private static final Instant RECORDED_AT = Instant.parse("2026-10-18T00:00:00Z");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(TEST_PASSWORD);

  @Test
  void v68ThroughV73PreservesProjectionAndPersistsOriginOperationReceiptAtomically() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("68"))
        .load()
        .migrate();

    UUID oldEventId = UUID.randomUUID();
    UUID oldCursorId = UUID.randomUUID();
    String scopeKey = "a".repeat(64);
    UUID epoch = UUID.randomUUID();
    UUID resourceId = UUID.randomUUID();
    insertProjection(
        jdbc,
        oldEventId,
        oldCursorId,
        scopeKey,
        epoch,
        1,
        "transfer.completed.v1",
        "transfer",
        resourceId);

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("72"))
        .load()
        .migrate();
    InternalId legacyJourneyId = InternalId.generate();
    insertJourney(jdbc, legacyJourneyId, RECORDED_AT);
    UUID legacyAttemptId = UUID.randomUUID();
    UUID legacyOperationId = UUID.randomUUID();
    UUID legacyReceivingRequestId = UUID.randomUUID();
    insertLegacyAcceptedUnreservedAttempt(
        jdbc,
        legacyAttemptId,
        legacyJourneyId.value(),
        legacyOperationId,
        legacyReceivingRequestId,
        RECORDED_AT);

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("73"))
        .load()
        .migrate();

    assertThat(
            jdbc.queryForObject(
                "SELECT resource_type FROM platform_notification.notification_projection "
                    + "WHERE event_id = ?",
                String.class,
                oldEventId))
        .isEqualTo("transfer");
    assertThat(
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .info()
                .current()
                .getVersion()
                .getVersion())
        .isEqualTo("73");
    assertThat(
            jdbc.queryForObject(
                "SELECT operation_state FROM transfer.federated_origin_receiving_attempt "
                    + "WHERE origin_attempt_id = ?",
                String.class,
                legacyAttemptId))
        .isEqualTo("OUTCOME_UNKNOWN");
    assertThat(
            jdbc.queryForObject(
                "SELECT remote_reservation_status FROM transfer.federated_origin_receiving_attempt "
                    + "WHERE origin_attempt_id = ?",
                String.class,
                legacyAttemptId))
        .isEqualTo("UNKNOWN");

    UUID newEventId = UUID.randomUUID();
    UUID newCursorId = UUID.randomUUID();
    insertProjection(
        jdbc,
        newEventId,
        newCursorId,
        scopeKey,
        epoch,
        2,
        "transfer.federated-receiving-request-changed.v1",
        "federated-receiving-request",
        UUID.randomUUID());

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_notification.notification_projection "
                    + "WHERE scope_key = ?",
                Integer.class,
                scopeKey))
        .isEqualTo(2);

    persistOriginAttemptAndReceiptOnPostgres(dataSource, jdbc);
  }

  private static void insertLegacyAcceptedUnreservedAttempt(
      JdbcTemplate jdbc,
      UUID attemptId,
      UUID journeyId,
      UUID operationId,
      UUID receivingRequestId,
      Instant now) {
    jdbc.update(
        "INSERT INTO transfer.federated_origin_receiving_attempt "
            + "(origin_attempt_id, journey_id, attempt_number, origin_deployment_id, "
            + "peer_deployment_id, partner_key, partner_configuration_version, operation_id, "
            + "request_fingerprint, command_fingerprint, request_body, operation_state, "
            + "remote_outcome, remote_receiving_request_id, remote_version, attempt_version, "
            + "created_at, updated_at) "
            + "VALUES (?, ?, 1, 'SYN-DEPLOYMENT-A', 'SYN-DEPLOYMENT-B', 'synthetic-peer-b', 1, "
            + "?, ?, ?, ?, 'RESOLVED', 'ACCEPTED_UNRESERVED', ?, 2, 2, ?, ?)",
        attemptId,
        journeyId,
        operationId,
        "a".repeat(64),
        "b".repeat(64),
        "{\"synthetic\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        receivingRequestId,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  private static void persistOriginAttemptAndReceiptOnPostgres(
      DriverManagerDataSource dataSource, JdbcTemplate jdbc) {
    FederatedOriginReceivingAttemptStore store = new JdbcFederatedOriginReceivingAttemptStore(jdbc);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    Instant now = Instant.parse("2026-10-18T01:00:00Z");
    InternalId journeyId = InternalId.generate();
    insertJourney(jdbc, journeyId, now);
    InternalId attemptId = InternalId.generate();
    InternalId operationId = InternalId.generate();
    byte[] body = "{\"synthetic\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var fingerprint = new FederationOperationFingerprint("a".repeat(64));
    var attempt =
        FederatedOriginReceivingAttempt.pending(
            attemptId,
            journeyId,
            1,
            new DeploymentId("SYN-DEPLOYMENT-A"),
            new DeploymentId("SYN-DEPLOYMENT-B"),
            new PartnerKey("synthetic-peer-b"),
            7,
            operationId,
            fingerprint,
            "b".repeat(64),
            body,
            now);
    CommandReceiptKey receiptKey = receiptKey("origin-create");
    RequestFingerprint commandFingerprint = RequestFingerprint.fromDigest("c".repeat(64));

    transaction.execute(
        ignored -> {
          assertThat(store.claimReceipt(receiptKey, commandFingerprint, now).state())
              .isEqualTo(TransferStore.ReceiptState.OWNER);
          store.insert(attempt);
          store.completeReceipt(receiptKey, 201, attemptId, 1, now);
          return null;
        });

    assertThat(store.findById(attemptId).map(FederatedOriginReceivingAttempt::id))
        .contains(attemptId);
    assertThat(store.lockByOperationId(operationId).map(FederatedOriginReceivingAttempt::id))
        .contains(attemptId);
    assertThat(store.latestAttemptNumber(journeyId)).isEqualTo(1);
    assertThat(store.claimReceipt(receiptKey, commandFingerprint, now).resultReference())
        .contains(attemptId);
    assertThat(
            store
                .claimReceipt(receiptKey, RequestFingerprint.fromDigest("d".repeat(64)), now)
                .state())
        .isEqualTo(TransferStore.ReceiptState.KEY_REUSED);
    FederatedOriginReceivingAttempt sent =
        attempt.withState(FederatedOriginReceivingAttempt.State.SENT, now.plusSeconds(1));
    assertThat(store.update(sent, attempt.version())).isTrue();
    FederatedOriginReceivingAttempt.RemoteAssessmentEvidence clinical =
        new FederatedOriginReceivingAttempt.RemoteAssessmentEvidence(
            FederatedOriginReceivingAttempt.AssessmentOutcome.SUPPORTS,
            "SYNTHETIC_CLINICAL_REVIEW",
            now,
            now.plusSeconds(1));
    FederatedOriginReceivingAttempt.RemoteAssessmentEvidence operational =
        new FederatedOriginReceivingAttempt.RemoteAssessmentEvidence(
            FederatedOriginReceivingAttempt.AssessmentOutcome.SUPPORTS,
            "SYNTHETIC_OPERATIONAL_REVIEW",
            now,
            now.plusSeconds(1));
    FederatedOriginReceivingAttempt unknown =
        sent.withRemoteResult(
            FederatedOriginReceivingAttempt.RemoteOutcome.ACCEPTED_UNRESERVED,
            FederatedOriginReceivingAttempt.RemoteReservationStatus.PENDING,
            InternalId.generate(),
            java.util.Optional.empty(),
            java.util.Optional.of(clinical),
            java.util.Optional.of(operational),
            java.util.Optional.of(now.plusSeconds(1)),
            now.plusSeconds(1),
            2,
            now.plusSeconds(2));
    assertThat(store.update(unknown, sent.version())).isTrue();
    FederatedOriginReceivingAttempt persisted = store.lockByOperationId(operationId).orElseThrow();
    assertThat(persisted.state()).isEqualTo(FederatedOriginReceivingAttempt.State.OUTCOME_UNKNOWN);
    assertThat(persisted.remoteReservationStatus())
        .contains(FederatedOriginReceivingAttempt.RemoteReservationStatus.PENDING);
    assertThat(persisted.remoteClinicalAssessment()).contains(clinical);
    assertThat(persisted.remoteOperationalAssessment()).contains(operational);
    assertThat(persisted.remoteAcceptedAt()).contains(now.plusSeconds(1));
    assertThat(persisted.remoteSourceUpdatedAt()).contains(now.plusSeconds(1));

    InternalId rolledBackJourney = InternalId.generate();
    insertJourney(jdbc, rolledBackJourney, now);
    InternalId rolledBackAttemptId = InternalId.generate();
    InternalId rolledBackOperationId = InternalId.generate();
    CommandReceiptKey rolledBackReceipt = receiptKey("origin-rollback");
    var rolledBackAttempt =
        FederatedOriginReceivingAttempt.pending(
            rolledBackAttemptId,
            rolledBackJourney,
            1,
            new DeploymentId("SYN-DEPLOYMENT-A"),
            new DeploymentId("SYN-DEPLOYMENT-B"),
            new PartnerKey("synthetic-peer-b"),
            7,
            rolledBackOperationId,
            fingerprint,
            "b".repeat(64),
            body,
            now);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored -> {
                      store.claimReceipt(rolledBackReceipt, commandFingerprint, now);
                      store.insert(rolledBackAttempt);
                      throw new IllegalStateException("synthetic transaction rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(store.findById(rolledBackAttemptId)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.federated_origin_receiving_receipt "
                    + "WHERE scope_digest = ? AND idempotency_key_digest = ?",
                Integer.class,
                rolledBackReceipt.scopeDigest(),
                rolledBackReceipt.idempotencyKey().value()))
        .isZero();
  }

  private static void insertJourney(JdbcTemplate jdbc, InternalId journeyId, Instant now) {
    jdbc.update(
        "INSERT INTO transfer.journey (journey_id, referral_id, origin_organization_id, "
            + "origin_facility_id, created_by_kind, created_by_issuer, "
            + "created_by_identifier_type, created_by_identifier, created_at, transport_required, version) "
            + "VALUES (?, ?, ?, ?, 'HUMAN', 'urn:synthetic:identity', 'subject', "
            + "'SYN-OPERATOR-001', ?, false, 1)",
        journeyId.value(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Timestamp.from(now));
  }

  private static CommandReceiptKey receiptKey(String rawKey) {
    return new CommandReceiptKey("e".repeat(64), IdempotencyKeyDigest.from(rawKey));
  }

  private static void insertProjection(
      JdbcTemplate jdbc,
      UUID eventId,
      UUID cursorId,
      String scopeKey,
      UUID epoch,
      long sequence,
      String eventType,
      String resourceType,
      UUID resourceId) {
    Instant now = RECORDED_AT.plusSeconds(sequence);
    jdbc.update(
        "INSERT INTO platform_notification.scope_cursor "
            + "(scope_key, cursor_epoch, current_sequence, current_cursor, updated_at) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (scope_key) DO UPDATE SET "
            + "current_sequence = EXCLUDED.current_sequence, "
            + "current_cursor = EXCLUDED.current_cursor, updated_at = EXCLUDED.updated_at",
        scopeKey,
        epoch,
        sequence,
        cursorId,
        Timestamp.from(now));
    jdbc.update(
        "INSERT INTO platform_notification.cursor_position "
            + "(cursor_id, scope_key, cursor_epoch, sequence_number, created_at) "
            + "VALUES (?, ?, ?, ?, ?)",
        cursorId,
        scopeKey,
        epoch,
        sequence,
        Timestamp.from(now));
    jdbc.update(
        "INSERT INTO platform_notification.notification_projection "
            + "(event_id, cursor_id, scope_key, sequence_number, event_type, resource_type, "
            + "resource_id, owner_version, correlation_id, occurred_at, recorded_at, projected_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'synthetic-migration-test', ?, ?, ?)",
        eventId,
        cursorId,
        scopeKey,
        sequence,
        eventType,
        resourceType,
        resourceId,
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now));
  }
}
