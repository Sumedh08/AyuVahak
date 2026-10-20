package org.ayuvahak.platform.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.ayuvahak.platform.AyuVahakApplication;
import org.ayuvahak.platform.eventing.adapter.persistence.PostgresOutboxRetentionReapplicationStore;
import org.ayuvahak.platform.eventing.application.PlatformOutboxRetentionManifest;
import org.ayuvahak.platform.retention.RetentionReapplicationHealthIndicator;
import org.ayuvahak.platform.retention.RetentionReapplicationProperties;
import org.ayuvahak.referral.adapter.persistence.JdbcReferralRetentionStore;
import org.ayuvahak.referral.application.ReferralRetentionManifest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Local-only PostgreSQL backup/restore acceptance with synthetic state. */
@SpringBootTest(
    classes = AyuVahakApplication.class,
    properties = {
      "management.tracing.sampling.probability=0.0",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.allow-http=true",
      "ayuvahak.authentication.service-client-ids[0]=SYN-RESTORE-SERVICE",
      "ayuvahak.eventing.outbox.dispatcher-enabled=false"
    })
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BackupRestoreAcceptanceIT {

  private static final Logger LOG = LoggerFactory.getLogger(BackupRestoreAcceptanceIT.class);
  private static final String PASSWORD = "synthetic-local-only";
  private static final String ISSUER = "https://identity.example.test/realms/restore";
  private static final String OPERATOR = "SYN-RESTORE-OPERATOR";
  private static final UUID ORGANIZATION_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000013");
  private static final UUID GRANT_ID = UUID.fromString("00000000-0000-4000-8000-000000000023");
  private static final UUID REFERRAL_ID = UUID.fromString("00000000-0000-4000-8000-000000000014");
  private static final UUID EVENT_ID = UUID.fromString("00000000-0000-4000-8000-000000000015");
  private static final UUID SNAPSHOT_ID = UUID.fromString("00000000-0000-4000-8000-000000000016");
  private static final UUID SERIES_ID = UUID.fromString("00000000-0000-4000-8000-000000000017");
  private static final UUID SNAPSHOT_REVISION_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000018");
  private static final UUID EXPORT_OPERATION_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000019");
  private static final UUID REVISION_OPERATION_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000020");
  private static final UUID RESTORE_ID = UUID.fromString("00000000-0000-4000-8000-000000000021");
  private static final UUID RESTRICTION_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000022");
  private static final UUID JOURNEY_ID = UUID.fromString("00000000-0000-4000-8000-000000000026");
  private static final UUID REQUEST_ATTEMPT_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000027");
  private static final UUID TRANSFER_ID = UUID.fromString("00000000-0000-4000-8000-000000000028");
  private static final UUID DECISION_ID = UUID.fromString("00000000-0000-4000-8000-000000000029");
  private static final UUID CLINICAL_ASSESSMENT_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000030");
  private static final UUID OPERATIONAL_ASSESSMENT_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000031");
  private static final UUID TRANSPORT_REQUEST_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000032");
  private static final UUID FEDERATED_ATTEMPT_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000033");
  private static final UUID FEDERATED_OPERATION_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000034");
  private static final UUID HELD_REFERRAL_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000035");
  private static final UUID HOLD_ID = UUID.fromString("00000000-0000-4000-8000-000000000036");
  private static final String INDICATOR_KEY = "SYN-INDICATOR-P13";
  private static final String MAPPING_KEY = "SYN-MAPPING-P13";
  private static final String SYNTHETIC_PRIVATE_MARKER = "SYN-PATIENT-PHASE13-RESTORE-91A2";
  private static final String FINGERPRINT =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final Duration DECLARED_RPO = Duration.ofMinutes(15);
  private static final Duration DECLARED_RTO = Duration.ofMinutes(15);
  private static final DockerImageName POSTGIS_IMAGE =
      DockerImageName.parse(
              "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
          .asCompatibleSubstituteFor("postgres");
  private static final RSAKey SIGNING_KEY = newSigningKey();
  private static final PostgreSQLContainer SOURCE = database();
  private static final PostgreSQLContainer RESTORED = database();

  @Container private static final PostgreSQLContainer SOURCE_CONTAINER = SOURCE;
  @Container private static final PostgreSQLContainer RESTORED_CONTAINER = RESTORED;

  private static HttpServer jwksServer;
  private static PlatformOutboxRetentionManifest platformManifest;
  private static ReferralRetentionManifest referralManifest;
  private static Instant backupCompletedAt;
  private static Instant disasterDeclaredAt;
  private static Duration measuredRpo;
  private static Instant restoreStartedAt;
  private static long restoreStartedNanos;
  private static Path backupFile;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private RetentionReapplicationProperties reapplicationProperties;
  @Autowired private RetentionReapplicationHealthIndicator readiness;
  @Autowired private PostgresOutboxRetentionReapplicationStore platformRetention;
  @Autowired private JdbcReferralRetentionStore referralRetention;
  @Autowired private PlatformTransactionManager transactionManager;

  @BeforeAll
  static void createSyntheticBackupAndRestoreToCleanDatabase() throws Exception {
    JdbcTemplate source = database(SOURCE).jdbc();
    Flyway.configure()
        .dataSource(SOURCE.getJdbcUrl(), SOURCE.getUsername(), PASSWORD)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    seedSource(source);

    Path workspace = Files.createTempDirectory("ayu-phase13-restore-");
    backupFile = workspace.resolve("synthetic-backup.dump");
    String containerDumpPath = "/tmp/ayu-phase13-synthetic.dump";
    runDocker(
        "exec",
        SOURCE.getContainerId(),
        "pg_dump",
        "-Fc",
        "-U",
        SOURCE.getUsername(),
        "-d",
        SOURCE.getDatabaseName(),
        "-f",
        containerDumpPath);
    runDocker("cp", SOURCE.getContainerId() + ":" + containerDumpPath, backupFile.toString());
    backupCompletedAt = Instant.now();

    Instant postBackupActionAt = backupCompletedAt.plusMillis(1);
    source.update(
        "UPDATE directory.organization SET name = 'SYN Late Backup Mutation', updated_at = ? "
            + "WHERE organization_id = ?",
        Timestamp.from(postBackupActionAt),
        ORGANIZATION_ID);
    source.update(
        "INSERT INTO platform_eventing.retention_purge_receipt "
            + "(event_id, delivery_generation, purged_at) VALUES (?, 1, ?)",
        EVENT_ID,
        Timestamp.from(postBackupActionAt));
    source.update("DELETE FROM platform_eventing.outbox_delivery WHERE event_id = ?", EVENT_ID);
    source.update("DELETE FROM platform_eventing.internal_event WHERE event_id = ?", EVENT_ID);
    source.update(
        "INSERT INTO referral.identifier_restriction "
            + "(referral_id, restriction_id, reason_code, restricted_at, restricted_by_kind, "
            + "restricted_by_issuer, restricted_by_identifier_type, restricted_by_identifier) "
            + "VALUES (?, ?, 'RETENTION_REVIEW_COMPLETED', ?, 'HUMAN', ?, 'subject', ?)",
        REFERRAL_ID,
        RESTRICTION_ID,
        Timestamp.from(postBackupActionAt),
        ISSUER,
        OPERATOR);
    source.update(
        "INSERT INTO referral.retention_hold "
            + "(hold_id, referral_id, purpose_code, review_by, created_at, created_by_kind, "
            + "created_by_issuer, created_by_identifier_type, created_by_identifier) "
            + "VALUES (?, ?, 'UNRESOLVED_RECONCILIATION', ?, ?, 'HUMAN', ?, 'subject', ?)",
        HOLD_ID,
        HELD_REFERRAL_ID,
        Timestamp.from(postBackupActionAt.plus(Duration.ofDays(30))),
        Timestamp.from(postBackupActionAt),
        ISSUER,
        OPERATOR);

    UUID restoreId = RESTORE_ID;
    platformManifest =
        new PostgresOutboxRetentionReapplicationStore(source)
            .exportSince(restoreId, backupCompletedAt, postBackupActionAt.plusMillis(1));
    referralManifest =
        new JdbcReferralRetentionStore(source)
            .exportSince(restoreId, backupCompletedAt, postBackupActionAt.plusMillis(1));

    disasterDeclaredAt = Instant.now();
    measuredRpo = Duration.between(backupCompletedAt, disasterDeclaredAt);
    restoreStartedAt = disasterDeclaredAt;
    restoreStartedNanos = System.nanoTime();

    runDocker(
        "exec",
        RESTORED.getContainerId(),
        "psql",
        "-U",
        RESTORED.getUsername(),
        "-d",
        "postgres",
        "-v",
        "ON_ERROR_STOP=1",
        "-c",
        "DROP DATABASE ayuvahak");
    runDocker(
        "exec",
        RESTORED.getContainerId(),
        "psql",
        "-U",
        RESTORED.getUsername(),
        "-d",
        "postgres",
        "-v",
        "ON_ERROR_STOP=1",
        "-c",
        "CREATE DATABASE ayuvahak TEMPLATE template0 OWNER ayuvahak");
    runDocker("cp", backupFile.toString(), RESTORED.getContainerId() + ":" + containerDumpPath);
    runDocker(
        "exec",
        RESTORED.getContainerId(),
        "pg_restore",
        "--no-owner",
        "-U",
        RESTORED.getUsername(),
        "-d",
        RESTORED.getDatabaseName(),
        containerDumpPath);

    jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    jwksServer.createContext(
        "/jwks",
        exchange -> {
          byte[] body =
              new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (var response = exchange.getResponseBody()) {
            response.write(body);
          }
        });
    jwksServer.start();
  }

  @AfterAll
  static void cleanLocalAcceptanceArtifacts() throws Exception {
    if (jwksServer != null) jwksServer.stop(0);
    if (backupFile != null) {
      Files.deleteIfExists(backupFile);
      Files.deleteIfExists(backupFile.getParent());
    }
  }

  @DynamicPropertySource
  static void restoreTargetProperties(DynamicPropertyRegistry registry) {
    registry.add("AYUVAHAK_DATABASE_URL", RESTORED::getJdbcUrl);
    registry.add("AYUVAHAK_DATABASE_USERNAME", RESTORED::getUsername);
    registry.add("AYUVAHAK_DATABASE_PASSWORD", RESTORED::getPassword);
    registry.add("ayuvahak.authentication.issuer", () -> ISSUER);
    registry.add(
        "ayuvahak.authentication.jwk-set-uri",
        () -> "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks");
    registry.add(
        "ayuvahak.retention.reapplication.required-restore-id", () -> RESTORE_ID.toString());
  }

  @Test
  void restoredApplicationVerifiesStateAndReappliesPostBackupRetentionBeforeReadiness()
      throws Exception {
    long restoredOrganizationCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM directory.organization WHERE organization_id = ?",
            Integer.class,
            ORGANIZATION_ID);
    assertThat(restoredOrganizationCount).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT name FROM directory.organization WHERE organization_id = ?",
                String.class,
                ORGANIZATION_ID))
        .isEqualTo("SYN Restore Organization");

    assertThat(readiness.health().getStatus().getCode()).isEqualTo("DOWN");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_eventing.internal_event WHERE event_id = ?",
                Integer.class,
                EVENT_ID))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM referral.identifier_restriction WHERE referral_id = ?",
                Integer.class,
                REFERRAL_ID))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM referral.retention_hold WHERE hold_id = ?",
                Integer.class,
                HOLD_ID))
        .isZero();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              platformRetention.reapply(platformManifest, Instant.now());
              referralRetention.reapply(referralManifest, Instant.now());
            });

    assertThat(readiness.health().getStatus().getCode()).isEqualTo("UP");
    assertThat(platformRetention.hasCompleted(RESTORE_ID)).isTrue();
    assertThat(referralRetention.hasCompleted(RESTORE_ID)).isTrue();

    mockMvc
        .perform(
            get("/api/v1/directory/organizations/{organizationId}", ORGANIZATION_ID)
                .header("Authorization", token(OPERATOR))
                .header("X-AyuVahak-Execution-Scope", "PLATFORM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source.reference").value("SYN-ORG-RESTORE-001"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_eventing.internal_event WHERE event_id = ?",
                Integer.class,
                EVENT_ID))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_eventing.retention_purge_receipt "
                    + "WHERE event_id = ? AND delivery_generation = 1",
                Integer.class,
                EVENT_ID))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM referral.identifier_restriction "
                    + "WHERE referral_id = ? AND restriction_id = ?",
                Integer.class,
                REFERRAL_ID,
                RESTRICTION_ID))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM referral.retention_hold "
                    + "WHERE hold_id = ? AND referral_id = ? AND released_at IS NULL",
                Integer.class,
                HOLD_ID,
                HELD_REFERRAL_ID))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM referral.identifier_restriction WHERE referral_id = ?",
                Integer.class,
                HELD_REFERRAL_ID))
        .isZero();

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer.command_receipt "
                    + "WHERE result_reference = ? AND receipt_state = 'COMPLETED'",
                Integer.class,
                JOURNEY_ID))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM identity_access.grant_command_receipt "
                    + "WHERE result_reference = ? AND receipt_state = 'COMPLETED'",
                Integer.class,
                GRANT_ID))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT transfer_status FROM transfer.transfer_record WHERE transfer_id = ?",
                String.class,
                TRANSFER_ID))
        .isEqualTo("AWAITING_RESERVATION");
    assertThat(
            jdbc.queryForObject(
                "SELECT request_status FROM transport.transport_request "
                    + "WHERE transport_request_id = ?",
                String.class,
                TRANSPORT_REQUEST_ID))
        .isEqualTo("REQUESTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transport.command_receipt "
                    + "WHERE result_reference = ? AND receipt_state = 'COMPLETED'",
                Integer.class,
                TRANSPORT_REQUEST_ID))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT operation_state FROM transfer.federated_origin_receiving_attempt "
                    + "WHERE origin_attempt_id = ? AND operation_id = ?",
                String.class,
                FEDERATED_ATTEMPT_ID,
                FEDERATED_OPERATION_ID))
        .isEqualTo("OUTCOME_UNKNOWN");

    assertThat(
            jdbc.queryForObject(
                "SELECT operation_state FROM analytics.dhis2_export_operation WHERE operation_id = ?",
                String.class,
                EXPORT_OPERATION_ID))
        .isEqualTo("OUTCOME_UNKNOWN");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM analytics.dhis2_export_target_tuple_claim WHERE operation_id = ?",
                Integer.class,
                EXPORT_OPERATION_ID))
        .isEqualTo(1);
    assertThat(
            database(SOURCE)
                .jdbc()
                .queryForObject(
                    "SELECT name FROM directory.organization WHERE organization_id = ?",
                    String.class,
                    ORGANIZATION_ID))
        .isEqualTo("SYN Late Backup Mutation");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record "
                    + "WHERE action_code = 'SYNTHETIC_BACKUP_FIXTURE'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class))
        .isEqualTo(92);
    assertThat(
            jdbc.queryForObject(
                "SELECT MAX(installed_rank) FROM flyway_schema_history", Integer.class))
        .isEqualTo(92);

    Duration recoveryTime = Duration.ofNanos(System.nanoTime() - restoreStartedNanos);
    Instant recoveryCompletedAt = Instant.now();
    assertThat(recoveryTime).isLessThan(DECLARED_RTO);
    assertThat(recoveryTime).isLessThan(Duration.ofSeconds(900));
    assertThat(measuredRpo).isLessThanOrEqualTo(DECLARED_RPO);
    assertThat(backupCompletedAt).isBefore(Instant.now());
    LOG.info(
        "Synthetic restore evidence: backupCompletedAt={} disasterDeclaredAt={} "
            + "restoreStartedAt={} recoveryCompletedAt={} recoveryTime={} measuredRpo={} declaredRpo={} "
            + "declaredRto={} restoredAtBackupPoint=true postBackupMutationLost=true",
        backupCompletedAt,
        disasterDeclaredAt,
        restoreStartedAt,
        recoveryCompletedAt,
        recoveryTime,
        measuredRpo,
        DECLARED_RPO,
        DECLARED_RTO);
    assertThat(platformManifest.purgedEvents()).hasSize(1);
    assertThat(referralManifest.identifierRestrictions()).hasSize(1);
    assertThat(referralManifest.holds()).hasSize(1);
    assertThat(platformManifest.toString()).doesNotContain(SYNTHETIC_PRIVATE_MARKER);
    assertThat(referralManifest.toString()).doesNotContain(SYNTHETIC_PRIVATE_MARKER);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE to_jsonb(audit_record)::text LIKE ?",
                Integer.class,
                "%" + SYNTHETIC_PRIVATE_MARKER + "%"))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_eventing.internal_event "
                    + "WHERE envelope::text LIKE ?",
                Integer.class,
                "%" + SYNTHETIC_PRIVATE_MARKER + "%"))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM analytics.dhis2_export_operation "
                    + "WHERE to_jsonb(dhis2_export_operation)::text LIKE ?",
                Integer.class,
                "%" + SYNTHETIC_PRIVATE_MARKER + "%"))
        .isZero();
  }

  private static void seedSource(JdbcTemplate jdbc) {
    Instant now = Instant.now();
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant "
            + "(grant_id, principal_kind, principal_issuer, principal_identifier_type, "
            + "principal_identifier, authority_code, scope_kind, grant_status, created_at) "
            + "VALUES (?, 'HUMAN', ?, 'subject', ?, 'DirectoryRead', 'PLATFORM', 'ACTIVE', ?)",
        GRANT_ID,
        ISSUER,
        OPERATOR,
        Timestamp.from(now));
    jdbc.update(
        "INSERT INTO identity_access.grant_command_receipt "
            + "(scope_digest, idempotency_key_digest, request_fingerprint, receipt_state, "
            + "response_status, result_reference, recorded_at) "
            + "VALUES (?, ?, ?, 'COMPLETED', 201, ?, ?)",
        "4".repeat(64),
        "5".repeat(64),
        "6".repeat(64),
        GRANT_ID,
        Timestamp.from(now));

    jdbc.update(
        "INSERT INTO directory.organization "
            + "(organization_id, name, source_issuer, source_resource_type, source_reference, "
            + "provenance_method, provenance_observed_at, provenance_recorded_at, "
            + "provenance_actor_kind, provenance_actor_issuer, provenance_actor_identifier_type, "
            + "provenance_actor_identifier, provenance_reason_code, lifecycle_status, version, "
            + "created_at, updated_at) VALUES (?, 'SYN Restore Organization', 'urn:synthetic:registry', "
            + "'Organization', 'SYN-ORG-RESTORE-001', 'SYNTHETIC_FIXTURE', ?, ?, 'HUMAN', ?, "
            + "'subject', ?, 'SYNTHETIC_RESTORE_ACCEPTANCE', 'ACTIVE', 1, ?, ?)",
        ORGANIZATION_ID,
        Timestamp.from(now),
        Timestamp.from(now),
        ISSUER,
        OPERATOR,
        Timestamp.from(now),
        Timestamp.from(now));

    jdbc.update(
        "INSERT INTO audit.audit_record "
            + "(id, action_kind, actor_kind, actor_issuer, actor_reference_type, actor_reference, "
            + "scope_kind, action_code, target_type, target_id, occurred_at, recorded_at, outcome, "
            + "reason_code, authentication_evidence, authorization_outcome, correlation_id) "
            + "VALUES (?, 'PROTECTED_MUTATION', 'HUMAN', ?, 'subject', ?, 'PLATFORM', "
            + "'SYNTHETIC_BACKUP_FIXTURE', 'Organization', ?, ?, ?, 'SUCCEEDED', "
            + "'SYNTHETIC_FIXTURE', 'HUMAN_ACCESS_TOKEN', 'ALLOWED', 'synthetic-restore-fixture-001')",
        UUID.fromString("00000000-0000-4000-8000-000000000024"),
        ISSUER,
        OPERATOR,
        ORGANIZATION_ID,
        Timestamp.from(now),
        Timestamp.from(now));

    jdbc.update(
        "INSERT INTO platform_eventing.internal_event "
            + "(event_id, event_type, producer_context, aggregate_ref, aggregate_sequence, envelope, recorded_at) "
            + "VALUES (?, 'identity-access.grant-created.v1', 'identity-access', NULL, NULL, "
            + "'{\"eventType\":\"identity-access.grant-created.v1\"}'::jsonb, ?)",
        EVENT_ID,
        Timestamp.from(now.minus(Duration.ofDays(2))));
    jdbc.update(
        "INSERT INTO platform_eventing.outbox_delivery "
            + "(event_id, delivery_generation, state, attempt_count, next_attempt_at, created_at, updated_at) "
            + "VALUES (?, 1, 'DELIVERED', 1, ?, ?, ?)",
        EVENT_ID,
        Timestamp.from(now.minus(Duration.ofDays(2))),
        Timestamp.from(now.minus(Duration.ofDays(2))),
        Timestamp.from(now.minus(Duration.ofDays(2))));

    seedReferral(jdbc, now);
    seedHeldReferral(jdbc, now);
    seedTransferTransportAndFederation(jdbc, now);
    seedUnresolvedAnalyticsExport(jdbc, now);
  }

  private static void seedHeldReferral(JdbcTemplate jdbc, Instant now) {
    jdbc.update(
        "INSERT INTO referral.draft "
            + "(referral_id, state, origin_organization_id, patient_issuer, patient_resource_type, "
            + "patient_identifier, created_by_kind, created_by_issuer, created_by_identifier_type, "
            + "created_by_identifier, updated_by_kind, updated_by_issuer, updated_by_identifier_type, "
            + "updated_by_identifier, version, created_at, updated_at) "
            + "VALUES (?, 'RESOLVED', ?, 'urn:synthetic:patient', 'Patient', 'SYN-PATIENT-HELD-001', "
            + "'SERVICE', 'urn:synthetic:restore', 'client_id', 'synthetic-phase13-fixture', "
            + "'SERVICE', 'urn:synthetic:restore', 'client_id', 'synthetic-phase13-fixture', 3, ?, ?)",
        HELD_REFERRAL_ID,
        ORGANIZATION_ID,
        Timestamp.from(now.minus(Duration.ofDays(3))),
        Timestamp.from(now.minus(Duration.ofDays(1))));
    jdbc.update(
        "INSERT INTO referral.resolution "
            + "(referral_id, outcome, reason_code, resolved_by_kind, resolved_by_issuer, "
            + "resolved_by_identifier_type, resolved_by_identifier, referral_version, resolved_at) "
            + "VALUES (?, 'FULFILLED', 'SYNTHETIC_RESTORE_FIXTURE', 'SERVICE', "
            + "'urn:synthetic:restore', 'client_id', 'synthetic-phase13-fixture', 3, ?)",
        HELD_REFERRAL_ID,
        Timestamp.from(now.minus(Duration.ofDays(1))));
  }

  private static void seedTransferTransportAndFederation(JdbcTemplate jdbc, Instant now) {
    UUID recommendationId = UUID.fromString("00000000-0000-4000-8000-000000000037");
    UUID destinationOrganizationId = UUID.fromString("00000000-0000-4000-8000-000000000038");
    UUID originFacilityId = UUID.fromString("00000000-0000-4000-8000-000000000039");
    UUID destinationFacilityId = UUID.fromString("00000000-0000-4000-8000-000000000040");
    jdbc.update(
        "INSERT INTO transfer.journey "
            + "(journey_id, referral_id, origin_organization_id, origin_facility_id, "
            + "created_by_kind, created_by_issuer, created_by_identifier_type, created_by_identifier, "
            + "created_at, version) VALUES (?, ?, ?, ?, 'SERVICE', 'urn:synthetic:restore', "
            + "'client_id', 'synthetic-phase13-fixture', ?, 1)",
        JOURNEY_ID,
        REFERRAL_ID,
        ORGANIZATION_ID,
        originFacilityId,
        Timestamp.from(now.minus(Duration.ofHours(5))));
    jdbc.update(
        "INSERT INTO transfer.request_attempt "
            + "(request_attempt_id, journey_id, attempt_number, referral_id, referral_revision, "
            + "recommendation_id, destination_facility_id, request_status, requested_by_kind, "
            + "requested_by_issuer, requested_by_identifier_type, requested_by_identifier, "
            + "requested_at, expires_at, version) VALUES (?, ?, 1, ?, 3, ?, ?, 'PENDING', 'HUMAN', "
            + "?, 'subject', ?, ?, ?, 1)",
        REQUEST_ATTEMPT_ID,
        JOURNEY_ID,
        REFERRAL_ID,
        recommendationId,
        destinationFacilityId,
        ISSUER,
        OPERATOR,
        Timestamp.from(now.minus(Duration.ofHours(5))),
        Timestamp.from(now.plus(Duration.ofHours(19))));
    jdbc.update(
        "UPDATE transfer.request_attempt SET request_status = 'ACCEPTED', version = 2 "
            + "WHERE request_attempt_id = ?",
        REQUEST_ATTEMPT_ID);
    jdbc.update(
        "INSERT INTO transfer.receiving_assessment "
            + "(assessment_id, request_attempt_id, assessment_kind, outcome, reason_code, "
            + "recorded_by_kind, recorded_by_issuer, recorded_by_identifier_type, "
            + "recorded_by_identifier, authority_code, receiving_organization_id, "
            + "receiving_facility_id, occurred_at, recorded_at, request_version) "
            + "VALUES (?, ?, 'CLINICAL_SUITABILITY', 'SUPPORTS', 'SYNTHETIC_REVIEW', 'HUMAN', ?, "
            + "'subject', ?, 'ClinicalSuitabilityAuthority', ?, ?, ?, ?, 2)",
        CLINICAL_ASSESSMENT_ID,
        REQUEST_ATTEMPT_ID,
        ISSUER,
        OPERATOR,
        destinationOrganizationId,
        destinationFacilityId,
        Timestamp.from(now.minus(Duration.ofHours(4))),
        Timestamp.from(now.minus(Duration.ofHours(4))));
    jdbc.update(
        "INSERT INTO transfer.receiving_assessment "
            + "(assessment_id, request_attempt_id, assessment_kind, outcome, reason_code, "
            + "recorded_by_kind, recorded_by_issuer, recorded_by_identifier_type, "
            + "recorded_by_identifier, authority_code, receiving_organization_id, "
            + "receiving_facility_id, occurred_at, recorded_at, request_version) "
            + "VALUES (?, ?, 'OPERATIONAL_READINESS', 'SUPPORTS', 'SYNTHETIC_REVIEW', 'HUMAN', ?, "
            + "'subject', ?, 'OperationalReadinessAuthority', ?, ?, ?, ?, 2)",
        OPERATIONAL_ASSESSMENT_ID,
        REQUEST_ATTEMPT_ID,
        ISSUER,
        OPERATOR,
        destinationOrganizationId,
        destinationFacilityId,
        Timestamp.from(now.minus(Duration.ofHours(4))),
        Timestamp.from(now.minus(Duration.ofHours(4))));
    jdbc.update(
        "INSERT INTO transfer.transfer_record "
            + "(transfer_id, journey_id, request_attempt_id, transfer_status, created_at, version) "
            + "VALUES (?, ?, ?, 'AWAITING_RESERVATION', ?, 1)",
        TRANSFER_ID,
        JOURNEY_ID,
        REQUEST_ATTEMPT_ID,
        Timestamp.from(now.minus(Duration.ofHours(4))));
    jdbc.update(
        "INSERT INTO transfer.request_decision "
            + "(decision_id, request_attempt_id, decision_kind, transfer_id, recorded_by_kind, "
            + "recorded_by_issuer, recorded_by_identifier_type, recorded_by_identifier, "
            + "authority_code, receiving_organization_id, receiving_facility_id, occurred_at, "
            + "recorded_at, request_version) VALUES (?, ?, 'ACCEPTED', ?, 'HUMAN', ?, 'subject', ?, "
            + "'AcceptanceAuthority', ?, ?, ?, ?, 2)",
        DECISION_ID,
        REQUEST_ATTEMPT_ID,
        TRANSFER_ID,
        ISSUER,
        OPERATOR,
        destinationOrganizationId,
        destinationFacilityId,
        Timestamp.from(now.minus(Duration.ofHours(4))),
        Timestamp.from(now.minus(Duration.ofHours(4))));
    jdbc.update(
        "INSERT INTO transfer.command_receipt "
            + "(scope_digest, idempotency_key_digest, request_fingerprint, receipt_state, "
            + "response_status, result_reference, result_version, recorded_at) "
            + "VALUES (?, ?, ?, 'COMPLETED', 201, ?, 1, ?)",
        "b".repeat(64),
        "c".repeat(64),
        "d".repeat(64),
        JOURNEY_ID,
        Timestamp.from(now.minus(Duration.ofHours(5))));
    jdbc.update(
        "INSERT INTO transport.transport_request "
            + "(transport_request_id, transfer_id, origin_facility_id, destination_facility_id, "
            + "initiated_by_kind, initiated_by_issuer, initiated_by_identifier_type, "
            + "initiated_by_identifier, recorded_by_kind, recorded_by_issuer, "
            + "recorded_by_identifier_type, recorded_by_identifier, request_status, requested_at, "
            + "correlation_id, version) VALUES (?, ?, ?, ?, 'HUMAN', ?, 'subject', ?, 'SERVICE', "
            + "'urn:synthetic:restore', 'client_id', 'synthetic-phase13-fixture', 'REQUESTED', ?, "
            + "'synthetic-restore-transport-001', 1)",
        TRANSPORT_REQUEST_ID,
        TRANSFER_ID,
        originFacilityId,
        destinationFacilityId,
        ISSUER,
        OPERATOR,
        Timestamp.from(now.minus(Duration.ofHours(3))));
    jdbc.update(
        "INSERT INTO transport.command_receipt "
            + "(scope_digest, idempotency_key_digest, request_fingerprint, receipt_state, "
            + "response_status, result_reference, result_version, recorded_at) "
            + "VALUES (?, ?, ?, 'COMPLETED', 201, ?, 1, ?)",
        "e".repeat(64),
        "f".repeat(64),
        "1".repeat(64),
        TRANSPORT_REQUEST_ID,
        Timestamp.from(now.minus(Duration.ofHours(3))));
    byte[] receivingRequest =
        "{\"operationId\":\"SYN-FEDERATION-OP-001\",\"facility\":\"SYN-FACILITY-B\"}"
            .getBytes(StandardCharsets.UTF_8);
    jdbc.update(
        "INSERT INTO transfer.federated_origin_receiving_attempt "
            + "(origin_attempt_id, journey_id, attempt_number, origin_deployment_id, "
            + "peer_deployment_id, partner_key, partner_configuration_version, operation_id, "
            + "request_fingerprint, command_fingerprint, request_body, operation_state, "
            + "remote_outcome, remote_receiving_request_id, remote_capacity_reservation_id, "
            + "remote_version, attempt_version, created_at, updated_at) "
            + "VALUES (?, ?, 1, 'SYN-DEPLOYMENT-A', 'SYN-DEPLOYMENT-B', 'SYN-PARTNER-B', 1, ?, ?, ?, ?, "
            + "'OUTCOME_UNKNOWN', NULL, NULL, NULL, 0, 1, ?, ?)",
        FEDERATED_ATTEMPT_ID,
        JOURNEY_ID,
        FEDERATED_OPERATION_ID,
        "2".repeat(64),
        "3".repeat(64),
        receivingRequest,
        Timestamp.from(now.minus(Duration.ofHours(2))),
        Timestamp.from(now.minus(Duration.ofHours(2))));
  }

  private static void seedReferral(JdbcTemplate jdbc, Instant now) {
    jdbc.update(
        "INSERT INTO referral.draft "
            + "(referral_id, state, origin_organization_id, patient_issuer, patient_resource_type, "
            + "patient_identifier, created_by_kind, created_by_issuer, created_by_identifier_type, "
            + "created_by_identifier, updated_by_kind, updated_by_issuer, updated_by_identifier_type, "
            + "updated_by_identifier, version, created_at, updated_at) "
            + "VALUES (?, 'RESOLVED', ?, 'urn:synthetic:patient', 'Patient', ?, 'SERVICE', "
            + "'urn:synthetic:restore', 'client_id', 'synthetic-phase13-fixture', 'SERVICE', "
            + "'urn:synthetic:restore', 'client_id', 'synthetic-phase13-fixture', 3, ?, ?)",
        REFERRAL_ID,
        ORGANIZATION_ID,
        SYNTHETIC_PRIVATE_MARKER,
        Timestamp.from(now.minus(Duration.ofDays(3))),
        Timestamp.from(now.minus(Duration.ofDays(1))));
    jdbc.update(
        "INSERT INTO referral.resolution "
            + "(referral_id, outcome, reason_code, resolved_by_kind, resolved_by_issuer, "
            + "resolved_by_identifier_type, resolved_by_identifier, referral_version, resolved_at) "
            + "VALUES (?, 'FULFILLED', 'SYNTHETIC_RESTORE_FIXTURE', 'SERVICE', 'urn:synthetic:restore', "
            + "'client_id', 'synthetic-phase13-fixture', 3, ?)",
        REFERRAL_ID,
        Timestamp.from(now.minus(Duration.ofDays(1))));
  }

  private static void seedUnresolvedAnalyticsExport(JdbcTemplate jdbc, Instant now) {
    Instant periodStart = Instant.parse("2026-01-01T00:00:00Z");
    Instant periodEnd = Instant.parse("2026-04-01T00:00:00Z");
    jdbc.update(
        "INSERT INTO analytics.indicator_definition_version "
            + "(indicator_key, definition_version, description, aggregation_formula, "
            + "reporting_time_zone, zero_allowed, definition_fingerprint, owner_approved_purpose, "
            + "effective_from, reporting_calendar, completeness_requirement, sensitivity_classification, "
            + "denominator_definition) VALUES (?, 1, 'Synthetic restore indicator', 'SUM', 'UTC', "
            + "TRUE, ?, 'Synthetic backup and restore acceptance only', ?, 'QUARTERLY', "
            + "'ALL_REQUIRED_SOURCES_COMPLETE_FOR_NUMERIC_VALUE', 'LOW_SENSITIVITY_AGGREGATE', 'NONE')",
        INDICATOR_KEY,
        FINGERPRINT,
        Timestamp.from(now.minus(Duration.ofDays(365))));
    jdbc.update(
        "INSERT INTO analytics.indicator_definition_dimension "
            + "(indicator_key, definition_version, dimension_key) VALUES (?, 1, 'ORGANIZATION')",
        INDICATOR_KEY);
    jdbc.update(
        "INSERT INTO analytics.aggregate_snapshot "
            + "(snapshot_id, computation_request_id, indicator_key, definition_version, "
            + "organization_issuer, organization_resource_type, organization_id, period_start_local, "
            + "period_end_exclusive_local, period_start_utc, period_end_exclusive_utc, time_zone_id, "
            + "time_zone_rules_version, completeness, aggregate_value, computed_at, input_fingerprint) "
            + "VALUES (?, ?, ?, 1, 'urn:synthetic:registry', 'Organization', ?, DATE '2026-01-01', "
            + "DATE '2026-04-01', ?, ?, 'UTC', 'synthetic-rules-v1', 'COMPLETE', 4, ?, ?)",
        SNAPSHOT_ID,
        UUID.fromString("00000000-0000-4000-8000-000000000025"),
        INDICATOR_KEY,
        ORGANIZATION_ID,
        Timestamp.from(periodStart),
        Timestamp.from(periodEnd),
        Timestamp.from(now.minus(Duration.ofDays(1))),
        FINGERPRINT);
    jdbc.update(
        "INSERT INTO analytics.aggregate_snapshot_series "
            + "(series_id, indicator_key, definition_version, organization_issuer, "
            + "organization_resource_type, organization_id, period_start_local, period_end_exclusive_local, "
            + "period_start_utc, period_end_exclusive_utc, time_zone_id, time_zone_rules_version, "
            + "current_revision_number, current_snapshot_id, state, correction_window_ends_at, created_at, updated_at) "
            + "VALUES (?, ?, 1, 'urn:synthetic:registry', 'Organization', ?, DATE '2026-01-01', "
            + "DATE '2026-04-01', ?, ?, 'UTC', 'synthetic-rules-v1', 1, ?, 'PROVISIONAL', ?, ?, ?)",
        SERIES_ID,
        INDICATOR_KEY,
        ORGANIZATION_ID,
        Timestamp.from(periodStart),
        Timestamp.from(periodEnd),
        SNAPSHOT_ID,
        Timestamp.from(now.plus(Duration.ofDays(30))),
        Timestamp.from(now),
        Timestamp.from(now));
    jdbc.update(
        "INSERT INTO analytics.aggregate_snapshot_revision "
            + "(revision_id, series_id, revision_number, snapshot_id, previous_snapshot_id, operation_id, "
            + "operation_fingerprint, cause, authorized_correction, latest_source_cutoff, recorded_at) "
            + "VALUES (?, ?, 1, ?, NULL, ?, ?, 'INITIAL', FALSE, ?, ?)",
        SNAPSHOT_REVISION_ID,
        SERIES_ID,
        SNAPSHOT_ID,
        REVISION_OPERATION_ID,
        FINGERPRINT,
        Timestamp.from(now.minus(Duration.ofDays(1))),
        Timestamp.from(now.minus(Duration.ofDays(1))));
    jdbc.update(
        "INSERT INTO analytics.dhis2_mapping_head "
            + "(mapping_key, active_version, lifecycle_state, updated_at) VALUES (?, 1, 'ACTIVE', ?)",
        MAPPING_KEY,
        Timestamp.from(now));
    jdbc.update(
        "INSERT INTO analytics.dhis2_mapping_version "
            + "(mapping_key, mapping_version, indicator_key, indicator_version, organization_issuer, "
            + "organization_reference_type, organization_id, partner_key, data_element_uid, org_unit_uid, "
            + "period_format, disclosure_policy_key, disclosure_output_key, exclusive_write_authority, "
            + "zero_is_significant, effective_from, mapping_fingerprint, created_at) "
            + "VALUES (?, 1, ?, 1, 'urn:synthetic:registry', 'Organization', ?, 'SYN-PARTNER-P13', "
            + "'SYN-DATAELEMENT-001', 'SYN-ORGUNIT-001', 'QUARTERLY', 'SYN-POLICY-P13', 'SYN-OUTPUT-P13', "
            + "TRUE, TRUE, ?, ?, ?)",
        MAPPING_KEY,
        INDICATOR_KEY,
        ORGANIZATION_ID,
        Timestamp.from(now.minus(Duration.ofDays(365))),
        FINGERPRINT,
        Timestamp.from(now));
    jdbc.update(
        "INSERT INTO analytics.dhis2_export_operation "
            + "(operation_id, operation_fingerprint, snapshot_id, revision_series_id, revision_number, "
            + "mapping_key, mapping_version, partner_key, partner_configuration_version, data_element_uid, "
            + "org_unit_uid, period_code, operation_state, result_class, attempt_count, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 1, ?, 1, 'SYN-PARTNER-P13', 1, 'SYN-DATAELEMENT-001', "
            + "'SYN-ORGUNIT-001', '2026Q1', 'OUTCOME_UNKNOWN', 'OUTCOME_UNKNOWN', 1, ?, ?)",
        EXPORT_OPERATION_ID,
        FINGERPRINT,
        SNAPSHOT_ID,
        SERIES_ID,
        MAPPING_KEY,
        Timestamp.from(now),
        Timestamp.from(now));
    jdbc.update(
        "INSERT INTO analytics.dhis2_export_target_tuple_claim "
            + "(partner_key, data_element_uid, period_code, org_unit_uid, operation_id, claimed_at) "
            + "VALUES ('SYN-PARTNER-P13', 'SYN-DATAELEMENT-001', '2026Q1', 'SYN-ORGUNIT-001', ?, ?)",
        EXPORT_OPERATION_ID,
        Timestamp.from(now));
  }

  private static PostgreSQLContainer database() {
    return new PostgreSQLContainer(POSTGIS_IMAGE)
        .withDatabaseName("ayuvahak")
        .withUsername("ayuvahak")
        .withPassword(PASSWORD)
        .withStartupTimeout(Duration.ofMinutes(3));
  }

  private static Database database(PostgreSQLContainer container) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(container.getJdbcUrl());
    dataSource.setUsername(container.getUsername());
    dataSource.setPassword(container.getPassword());
    return new Database(new JdbcTemplate(dataSource));
  }

  private static void runDocker(String... arguments) throws Exception {
    Process process =
        new ProcessBuilder(concat("docker", arguments)).redirectErrorStream(true).start();
    byte[] output;
    try (var stream = process.getInputStream()) {
      output = stream.readAllBytes();
    }
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IllegalStateException(
          "Local Docker acceptance command failed (exit "
              + exit
              + "): "
              + new String(output, StandardCharsets.UTF_8));
    }
  }

  private static String[] concat(String first, String[] rest) {
    String[] result = new String[rest.length + 1];
    result[0] = first;
    System.arraycopy(rest, 0, result, 1, rest.length);
    return result;
  }

  private String token(String subject) throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject(subject)
            .audience("ayuvahak-api")
            .issueTime(Date.from(now.minusSeconds(5)))
            .notBeforeTime(Date.from(now.minusSeconds(1)))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .claim("typ", "Bearer")
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(SIGNING_KEY.getKeyID())
                .build(),
            claims);
    jwt.sign(new RSASSASigner(SIGNING_KEY));
    return "Bearer " + jwt.serialize();
  }

  private static RSAKey newSigningKey() {
    try {
      return new RSAKeyGenerator(2048).keyID("phase13-restore-fixture").generate();
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private record Database(JdbcTemplate jdbc) {}
}
