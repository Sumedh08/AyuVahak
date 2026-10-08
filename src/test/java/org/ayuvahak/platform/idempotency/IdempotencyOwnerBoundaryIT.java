package org.ayuvahak.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/** Test-only command and consumer owners; their receipt tables are not production schemas. */
@Testcontainers
class IdempotencyOwnerBoundaryIT {

  private static final String LOCAL_TEST_PASSWORD = "local-test-only";
  private static final String FIXTURE_SCHEMA = "phase2_idempotency_fixture";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(LOCAL_TEST_PASSWORD);

  private static JdbcTemplate jdbc;
  private static TransactionTemplate transaction;

  @BeforeAll
  static void createTestOwnerTables() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    jdbc.execute("CREATE SCHEMA " + FIXTURE_SCHEMA);
    jdbc.execute(
        "CREATE TABLE "
            + FIXTURE_SCHEMA
            + ".command_receipt (scope_digest char(64) NOT NULL, key_digest char(64) NOT NULL, "
            + "request_fingerprint char(64) NOT NULL, response_status integer, result_reference uuid, "
            + "recorded_at timestamptz, PRIMARY KEY (scope_digest, key_digest), "
            + "CHECK ((response_status IS NULL AND result_reference IS NULL AND recorded_at IS NULL) "
            + "OR (response_status BETWEEN 200 AND 299 AND result_reference IS NOT NULL AND recorded_at IS NOT NULL)))");
    jdbc.execute(
        "CREATE TABLE "
            + FIXTURE_SCHEMA
            + ".command_effect (result_reference uuid PRIMARY KEY, effect_name text NOT NULL)");
    jdbc.execute(
        "CREATE TABLE "
            + FIXTURE_SCHEMA
            + ".consumer_receipt (consumer_name varchar(64) NOT NULL, event_id uuid NOT NULL, "
            + "recorded_at timestamptz NOT NULL, PRIMARY KEY (consumer_name, event_id))");
    jdbc.execute(
        "CREATE TABLE "
            + FIXTURE_SCHEMA
            + ".consumer_effect (consumer_name varchar(64) NOT NULL, event_id uuid NOT NULL, "
            + "effect_name text NOT NULL, PRIMARY KEY (consumer_name, event_id))");
  }

  @BeforeEach
  void clearTestOwnerState() {
    jdbc.execute("TRUNCATE " + FIXTURE_SCHEMA + ".command_receipt");
    jdbc.execute("TRUNCATE " + FIXTURE_SCHEMA + ".command_effect");
    jdbc.execute("TRUNCATE " + FIXTURE_SCHEMA + ".consumer_receipt");
    jdbc.execute("TRUNCATE " + FIXTURE_SCHEMA + ".consumer_effect");
  }

  @Test
  void commandRetriesReplayOneSafeOutcomeAndConflictingInputDoesNotRepeatEffect() {
    var owner = new TestCommandOwner(jdbc, transaction);
    var context = platformContext("user-1", "corr-1");
    var command = JSON.createObjectNode().put("authority", "AuditReview");

    var first =
        owner.execute(context, "platform-grant.create", "platform-grants", "retry-key", command);
    var retry =
        owner.execute(context, "platform-grant.create", "platform-grants", "retry-key", command);
    var conflict =
        owner.execute(
            context,
            "platform-grant.create",
            "platform-grants",
            "retry-key",
            JSON.createObjectNode().put("authority", "EventDeliveryOperations"));

    assertThat(first.kind()).isEqualTo(ExecutionKind.EXECUTED);
    assertThat(retry.kind()).isEqualTo(ExecutionKind.REPLAYED);
    assertThat(retry.resultReference()).isEqualTo(first.resultReference());
    assertThat(conflict.kind()).isEqualTo(ExecutionKind.KEY_REUSED);
    assertThat(count("command_effect")).isEqualTo(1);
    assertThat(count("command_receipt")).isEqualTo(1);
    String storedReceipt =
        jdbc.queryForObject(
            "SELECT scope_digest || key_digest || request_fingerprint FROM "
                + FIXTURE_SCHEMA
                + ".command_receipt",
            String.class);
    assertThat(storedReceipt)
        .doesNotContain("retry-key", "AuditReview", "user-1", "issuer.example.test");
  }

  @Test
  void concurrentExactRetryWaitsForTheOwnerTransactionAndCreatesOneEffect() throws Exception {
    var owner = new TestCommandOwner(jdbc, transaction);
    var context = platformContext("user-concurrent", "corr-concurrent");
    var command = JSON.createObjectNode().put("authority", "AuditReview");
    var firstReserved = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var secondAtInsert = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<CommandResult> first =
          executor.submit(
              () ->
                  owner.execute(
                      context,
                      "platform-grant.create",
                      "platform-grants",
                      "concurrent-key",
                      command,
                      "first-owner",
                      () -> {
                        firstReserved.countDown();
                        await(releaseFirst);
                      },
                      () -> {}));
      assertThat(firstReserved.await(3, TimeUnit.SECONDS)).isTrue();

      Future<CommandResult> second =
          executor.submit(
              () ->
                  owner.execute(
                      context,
                      "platform-grant.create",
                      "platform-grants",
                      "concurrent-key",
                      command,
                      "second-owner",
                      () -> {},
                      secondAtInsert::countDown));
      assertThat(secondAtInsert.await(3, TimeUnit.SECONDS)).isTrue();
      awaitDatabaseLock("second-owner");
      releaseFirst.countDown();

      assertThat(first.get(5, TimeUnit.SECONDS).kind()).isEqualTo(ExecutionKind.EXECUTED);
      var replay = second.get(5, TimeUnit.SECONDS);
      assertThat(replay.kind()).isEqualTo(ExecutionKind.REPLAYED);
      assertThat(replay.resultReference()).isEqualTo(first.get().resultReference());
      assertThat(count("command_effect")).isEqualTo(1);
    } finally {
      releaseFirst.countDown();
    }
  }

  @Test
  void rolledBackCommandReceiptAndEffectCanBeSafelyRetried() {
    var owner = new TestCommandOwner(jdbc, transaction);
    var context = platformContext("user-rollback", "corr-rollback");
    var command = JSON.createObjectNode().put("authority", "AuditReview");

    assertThatThrownBy(
            () ->
                owner.execute(
                    context,
                    "platform-grant.create",
                    "platform-grants",
                    "rollback-key",
                    command,
                    "rollback-owner",
                    () -> {
                      throw new ExpectedRollback();
                    },
                    () -> {}))
        .isInstanceOf(ExpectedRollback.class);

    var retried =
        owner.execute(context, "platform-grant.create", "platform-grants", "rollback-key", command);

    assertThat(retried.kind()).isEqualTo(ExecutionKind.EXECUTED);
    assertThat(count("command_receipt")).isEqualTo(1);
    assertThat(count("command_effect")).isEqualTo(1);
  }

  @Test
  void consumerReceiptAndEffectAreIndependentPerConsumerAndAtomicOnFailure() {
    var owner = new TestConsumerOwner(jdbc, transaction);
    var eventId = InternalId.generate();
    var auditConsumer = new ConsumerReceiptKey("audit-review-projection", eventId);

    assertThatThrownBy(
            () ->
                owner.applyOnce(
                    auditConsumer,
                    () -> {
                      owner.insertEffect(auditConsumer, "synthetic-audit-effect");
                      throw new ExpectedRollback();
                    }))
        .isInstanceOf(ExpectedRollback.class);
    assertThat(count("consumer_receipt")).isZero();
    assertThat(count("consumer_effect")).isZero();

    assertThat(
            owner.applyOnce(
                auditConsumer, () -> owner.insertEffect(auditConsumer, "synthetic-audit-effect")))
        .isTrue();
    assertThat(
            owner.applyOnce(
                auditConsumer, () -> owner.insertEffect(auditConsumer, "synthetic-audit-effect")))
        .isFalse();
    var separateConsumer = new ConsumerReceiptKey("metric-projection", eventId);
    assertThat(
            owner.applyOnce(
                separateConsumer,
                () -> owner.insertEffect(separateConsumer, "synthetic-metric-effect")))
        .isTrue();

    assertThat(count("consumer_receipt")).isEqualTo(2);
    assertThat(count("consumer_effect")).isEqualTo(2);
  }

  private static ExecutionContext platformContext(String subject, String correlation) {
    var actor = ActorReference.human(new IssuerId("https://issuer.example.test"), subject);
    return new ExecutionContextFactory((ignoredActor, ignoredScope) -> true)
        .forInteractiveRequest(
            actor, RequestedExecutionScope.platform(), new CorrelationId(correlation));
  }

  private static int count(String table) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + FIXTURE_SCHEMA + "." + table, Integer.class);
  }

  private static void awaitDatabaseLock(String applicationName) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      Integer waiters =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM pg_stat_activity WHERE application_name = ? "
                  + "AND wait_event_type = 'Lock'",
              Integer.class,
              applicationName);
      if (waiters != null && waiters > 0) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("second command did not wait on the owner receipt constraint");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(3, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for concurrent command release");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("concurrent command was interrupted", exception);
    }
  }

  private enum ExecutionKind {
    EXECUTED,
    REPLAYED,
    KEY_REUSED
  }

  private record CommandResult(
      ExecutionKind kind, InternalId resultReference, int responseStatus) {}

  private static final class TestCommandOwner {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    private TestCommandOwner(JdbcTemplate jdbc, TransactionTemplate transaction) {
      this.jdbc = jdbc;
      this.transaction = transaction;
    }

    private CommandResult execute(
        ExecutionContext context,
        String operation,
        String target,
        String rawKey,
        tools.jackson.databind.JsonNode normalizedCommand) {
      return execute(
          context,
          operation,
          target,
          rawKey,
          normalizedCommand,
          "command-owner",
          () -> {},
          () -> {});
    }

    private CommandResult execute(
        ExecutionContext context,
        String operation,
        String target,
        String rawKey,
        tools.jackson.databind.JsonNode normalizedCommand,
        String applicationName,
        Runnable afterClaim,
        Runnable beforeInsert) {
      var receiptKey = CommandReceiptKey.from(context, operation, target, rawKey);
      var fingerprint = RequestFingerprint.from(normalizedCommand);
      return transaction.execute(
          status -> {
            jdbc.execute("SET LOCAL lock_timeout = '5000ms'");
            jdbc.execute("SET LOCAL application_name = '" + applicationName + "'");
            beforeInsert.run();
            int inserted =
                jdbc.update(
                    "INSERT INTO "
                        + FIXTURE_SCHEMA
                        + ".command_receipt (scope_digest, key_digest, request_fingerprint) "
                        + "VALUES (?, ?, ?) ON CONFLICT (scope_digest, key_digest) DO NOTHING",
                    receiptKey.scopeDigest(),
                    receiptKey.idempotencyKey().value(),
                    fingerprint.value());
            if (inserted == 0) {
              var row =
                  jdbc.queryForMap(
                      "SELECT request_fingerprint, response_status, result_reference, recorded_at "
                          + "FROM "
                          + FIXTURE_SCHEMA
                          + ".command_receipt WHERE scope_digest = ? AND key_digest = ?",
                      receiptKey.scopeDigest(),
                      receiptKey.idempotencyKey().value());
              var recordedFingerprint =
                  RequestFingerprint.fromDigest(row.get("request_fingerprint").toString().trim());
              var existing =
                  new CommandReceipt(
                      recordedFingerprint,
                      (Integer) row.get("response_status"),
                      new InternalId((UUID) row.get("result_reference")),
                      new RecordedAt(((java.sql.Timestamp) row.get("recorded_at")).toInstant()));
              var decision = CommandReceiptDecision.forRequest(existing, fingerprint);
              return decision instanceof CommandReceiptDecision.Replay replay
                  ? new CommandResult(
                      ExecutionKind.REPLAYED,
                      replay.receipt().resultReference(),
                      replay.receipt().responseStatus())
                  : new CommandResult(ExecutionKind.KEY_REUSED, null, 409);
            }

            var resultReference = InternalId.generate();
            jdbc.update(
                "INSERT INTO " + FIXTURE_SCHEMA + ".command_effect VALUES (?, ?)",
                resultReference.value(),
                "synthetic-grant-effect");
            afterClaim.run();
            var recordedAt = java.time.Instant.parse("2026-01-01T00:00:00Z");
            jdbc.update(
                "UPDATE "
                    + FIXTURE_SCHEMA
                    + ".command_receipt SET response_status = 201, result_reference = ?, "
                    + "recorded_at = ? WHERE scope_digest = ? AND key_digest = ?",
                resultReference.value(),
                java.sql.Timestamp.from(recordedAt),
                receiptKey.scopeDigest(),
                receiptKey.idempotencyKey().value());
            return new CommandResult(ExecutionKind.EXECUTED, resultReference, 201);
          });
    }
  }

  private static final class TestConsumerOwner {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    private TestConsumerOwner(JdbcTemplate jdbc, TransactionTemplate transaction) {
      this.jdbc = jdbc;
      this.transaction = transaction;
    }

    private boolean applyOnce(ConsumerReceiptKey key, Runnable effect) {
      return transaction.execute(
          status -> {
            int inserted =
                jdbc.update(
                    "INSERT INTO "
                        + FIXTURE_SCHEMA
                        + ".consumer_receipt (consumer_name, event_id, recorded_at) "
                        + "VALUES (?, ?, CURRENT_TIMESTAMP) ON CONFLICT (consumer_name, event_id) DO NOTHING",
                    key.consumer(),
                    key.eventId().value());
            if (inserted == 0) {
              return false;
            }
            effect.run();
            return true;
          });
    }

    private void insertEffect(ConsumerReceiptKey key, String effectName) {
      jdbc.update(
          "INSERT INTO "
              + FIXTURE_SCHEMA
              + ".consumer_effect (consumer_name, event_id, effect_name) VALUES (?, ?, ?)",
          key.consumer(),
          key.eventId().value(),
          effectName);
    }
  }

  private static final class ExpectedRollback extends RuntimeException {}
}
