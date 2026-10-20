package org.ayuvahak.replicaacceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.ayuvahak.platform.AyuVahakApplication;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventEnvelopeJsonCodec;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.DispatchReport;
import org.ayuvahak.platform.eventing.application.EventHandlerRegistry;
import org.ayuvahak.platform.eventing.application.InternalEvent;
import org.ayuvahak.platform.eventing.application.InternalEventHandler;
import org.ayuvahak.platform.eventing.application.OutboxDispatcher;
import org.ayuvahak.platform.eventing.application.OutboxStore;
import org.ayuvahak.platform.eventing.application.OutboxWriter;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.notification.application.NotificationEventProjectionHandler;
import org.ayuvahak.platform.notification.application.NotificationProjectionPage;
import org.ayuvahak.platform.notification.application.NotificationProjectionQuery;
import org.ayuvahak.platform.notification.application.NotificationScope;
import org.ayuvahak.platform.observability.BenchmarkDeclaration;
import org.ayuvahak.platform.observability.BenchmarkObservation;
import org.ayuvahak.platform.observability.BenchmarkRunCondition;
import org.ayuvahak.platform.observability.BenchmarkRunResult;
import org.ayuvahak.platform.observability.BenchmarkWorkloadScale;
import org.ayuvahak.platform.observability.SyntheticBenchmarkRunner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * Starts two independent Spring Boot application replicas with independent PostgreSQL pools. Both
 * use one local disposable database because this is replica acceptance, not federation.
 */
@Testcontainers
class TwoApplicationReplicaWorkerAcceptanceIT {

  private static final String TEST_PASSWORD = "local-replica-test-only";
  private static final Instant SYNTHETIC_TIME = Instant.parse("2026-10-20T00:00:00Z");
  private static final EventType EVENT_TYPE = EventType.parse("referral.cancelled.v1");
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private static volatile ProjectionControl projectionControl;

  @Test
  void separateApplicationProcessesCoordinateAndRecoverAfterProcessTermination() throws Exception {
    projectionControl = new ProjectionControl();
    ConfigurableApplicationContext seedContext = startReplica("SYN-PROCESS-SEEDER");
    Process replicaA = null;
    Process replicaB = null;
    Connection crashGate = null;
    Path logDirectory = Path.of(".scratch", "phase-13-production-hardening-dpg-readiness", "process-acceptance");
    Files.createDirectories(logDirectory);
    try {
      Path applicationJar = Path.of("target", "ayuvahak-platform-0.1.0-SNAPSHOT.jar");
      assertThat(applicationJar).as("packaged application JAR for process acceptance").exists();
      int portA = freePort();
      replicaA = startPackagedReplica("SYN-PHASE13-REPLICA-A", portA, logDirectory.resolve("replica-a.log"));
      awaitProcessHealth(replicaA, portA);
      int portB = freePort();
      replicaB = startPackagedReplica("SYN-PHASE13-REPLICA-B", portB, logDirectory.resolve("replica-b.log"));
      awaitProcessHealth(replicaB, portB);
      assertThat(replicaA.pid()).isNotEqualTo(replicaB.pid());
      EventEnvelope normal = notificationOnlyEvent("SYN-RECEIVING-REQUEST-PROCESS-001", 1);
      record(seedContext, normal);
      awaitOutboxState(seedContext, normal.eventId().toString(), "DELIVERED", Duration.ofSeconds(8));
      assertThat(notificationCount(seedContext, normal.eventId().toString())).isEqualTo(1);

      EventEnvelope crashRecovery = notificationOnlyEvent("SYN-RECEIVING-REQUEST-PROCESS-002", 1);
      crashGate = installProcessCrashGate(seedContext);
      record(seedContext, crashRecovery);
      String pausedReplica = awaitPausedDatabaseSession(seedContext, Duration.ofSeconds(30));
      Process claimedProcess = pausedReplica.equals("SYN-PHASE13-REPLICA-A") ? replicaA : replicaB;
      Process survivingProcess = claimedProcess == replicaA ? replicaB : replicaA;
      claimedProcess.destroyForcibly();
      assertThat(claimedProcess.waitFor(10, TimeUnit.SECONDS)).isTrue();
      releaseProcessCrashGate(crashGate);
      crashGate.close();
      crashGate = null;

      awaitOutboxState(seedContext, crashRecovery.eventId().toString(), "DELIVERED", Duration.ofSeconds(30));
      assertThat(notificationCount(seedContext, crashRecovery.eventId().toString()))
          .as("the surviving application process recovers the leased event exactly once")
          .isEqualTo(1);
      assertThat(survivingProcess.isAlive()).isTrue();
    } finally {
      stopProcess(replicaA);
      stopProcess(replicaB);
      if (crashGate != null) {
        try {
          releaseProcessCrashGate(crashGate);
          crashGate.close();
        } catch (SQLException ignored) {
          // The disposable test database is discarded after this acceptance.
        }
      }
      dropProcessCrashGate(seedContext);
      seedContext.close();
      projectionControl = null;
    }
  }

  private static Process startPackagedReplica(String applicationName, int port, Path logFile)
      throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    String jdbcUrl = POSTGRES.getJdbcUrl() + "?ApplicationName=" + applicationName;
    ProcessBuilder builder =
        new ProcessBuilder(
                java,
                "-Duser.timezone=UTC",
                "-jar",
                Path.of("target", "ayuvahak-platform-0.1.0-SNAPSHOT.jar").toAbsolutePath().toString(),
                "--server.port=" + port,
                "--spring.flyway.enabled=false",
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.datasource.hikari.data-source-properties.ApplicationName=" + applicationName,
                "--ayuvahak.database.url=" + jdbcUrl,
                "--ayuvahak.database.username=" + POSTGRES.getUsername(),
                "--ayuvahak.database.password=" + POSTGRES.getPassword(),
                "--ayuvahak.authentication.issuer=https://identity.example.test/realms/test",
                "--ayuvahak.authentication.jwk-set-uri=http://127.0.0.1:9/jwks",
                "--ayuvahak.authentication.audience=ayuvahak-api",
                "--ayuvahak.authentication.service-client-ids=synthetic-worker",
                "--ayuvahak.authentication.allow-http=true",
                "--ayuvahak.transfer.owner-event-consumer-client-id=ayuvahak-transfer-owner-event-consumer",
                "--ayuvahak.eventing.outbox.dispatcher-enabled=true",
                "--ayuvahak.eventing.outbox.poll-interval=PT0.2S",
                "--ayuvahak.eventing.outbox.lease-duration=PT3S",
                "--ayuvahak.eventing.outbox.lease-renewal-interval=PT0.5S",
                "--ayuvahak.eventing.outbox.delivered-event-retention=PT24H",
                "--ayuvahak.eventing.outbox.batch-size=10",
                "--ayuvahak.transfer.expiry-polling-enabled=false",
                "--ayuvahak.transfer.reservation-polling-enabled=false",
                "--ayuvahak.capacity.reservation-lifecycle.enabled=false",
                "--management.endpoints.web.exposure.include=health",
                "--logging.level.root=WARN")
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile());
    return builder.start();
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void awaitProcessHealth(Process process, int port) throws Exception {
    Instant deadline = Instant.now().plusSeconds(90);
    while (Instant.now().isBefore(deadline)) {
      if (!process.isAlive()) {
        throw new AssertionError("packaged AyuVahak process exited during startup: " + process.pid());
      }
      try {
        HttpRequest request =
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/liveness"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        if (HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
          return;
        }
      } catch (Exception notReadyYet) {
        Thread.sleep(250);
      }
    }
    throw new AssertionError("packaged AyuVahak process did not become healthy: " + process.pid());
  }

  private static Connection installProcessCrashGate(ConfigurableApplicationContext context)
      throws SQLException {
    Connection connection = context.getBean(DataSource.class).getConnection();
    try (var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS replica_acceptance");
      statement.execute(
          "CREATE OR REPLACE FUNCTION replica_acceptance.pause_projection() RETURNS trigger "
              + "LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(81313001); "
              + "RETURN NEW; END $$");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS replica_acceptance.blocked_event (event_id uuid PRIMARY KEY)");
      statement.execute(
          "CREATE OR REPLACE TRIGGER pause_projection_before_insert BEFORE INSERT ON "
              + "platform_notification.notification_projection FOR EACH ROW EXECUTE FUNCTION "
              + "replica_acceptance.pause_projection()");
      statement.execute("SELECT pg_advisory_lock(81313001)");
      return connection;
    } catch (SQLException failure) {
      connection.close();
      throw failure;
    }
  }

  private static void releaseProcessCrashGate(Connection gate) throws SQLException {
    try (var statement = gate.createStatement()) {
      statement.execute("SELECT pg_advisory_unlock(81313001)");
    }
  }

  private static String awaitPausedDatabaseSession(
      ConfigurableApplicationContext context, Duration timeout) throws Exception {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      try (Connection connection = context.getBean(DataSource.class).getConnection();
          var statement =
              connection.prepareStatement(
                  "SELECT application_name FROM pg_stat_activity WHERE wait_event = 'advisory' "
                      + "AND application_name IN ('SYN-PHASE13-REPLICA-A', 'SYN-PHASE13-REPLICA-B') LIMIT 1");
          var result = statement.executeQuery()) {
        if (result.next()) {
          return result.getString(1);
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError("no packaged replica entered the controlled PostgreSQL crash gate");
  }

  private static void dropProcessCrashGate(ConfigurableApplicationContext context) {
    if (!context.isActive()) {
      return;
    }
    try (Connection connection = context.getBean(DataSource.class).getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER IF EXISTS pause_projection_before_insert ON platform_notification.notification_projection");
      statement.execute("DROP FUNCTION IF EXISTS replica_acceptance.pause_projection()");
      statement.execute("DROP SCHEMA IF EXISTS replica_acceptance CASCADE");
    } catch (SQLException ignored) {
      // The acceptance database is disposable; cleanup must not mask the original assertion.
    }
  }

  private static void stopProcess(Process process) throws InterruptedException {
    if (process == null || !process.isAlive()) {
      return;
    }
    process.destroy();
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
    }
  }

  private static void awaitOutboxState(
      ConfigurableApplicationContext context, String eventId, String expected, Duration timeout)
      throws Exception {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      try (Connection connection = context.getBean(DataSource.class).getConnection();
          var statement =
              connection.prepareStatement(
                  "SELECT state FROM platform_eventing.outbox_delivery WHERE event_id = ?")) {
        statement.setObject(1, java.util.UUID.fromString(eventId));
        try (var result = statement.executeQuery()) {
          if (result.next() && expected.equals(result.getString(1))) {
            return;
          }
        }
      }
      Thread.sleep(100);
    }
    String diagnostic = "missing";
    try (Connection connection = context.getBean(DataSource.class).getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT state, attempt_count, last_failure_category FROM platform_eventing.outbox_delivery WHERE event_id = ?")) {
      statement.setObject(1, java.util.UUID.fromString(eventId));
      try (var result = statement.executeQuery()) {
        if (result.next()) {
          diagnostic =
              result.getString("state")
                  + "/attempts="
                  + result.getInt("attempt_count")
                  + "/failure="
                  + result.getString("last_failure_category");
        }
      }
    }
    throw new AssertionError(
        "outbox delivery did not reach " + expected + " for " + eventId + ": " + diagnostic);
  }

  private static int notificationCount(ConfigurableApplicationContext context, String eventId)
      throws SQLException {
    try (Connection connection = context.getBean(DataSource.class).getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT count(*) FROM platform_notification.notification_projection WHERE event_id = ?")) {
      statement.setObject(1, java.util.UUID.fromString(eventId));
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak_replica_acceptance")
          .withUsername("ayuvahak")
          .withPassword(TEST_PASSWORD)
          .withStartupTimeout(Duration.ofMinutes(3));

  @Test
  void independentReplicasCoordinateDurableProjectionAndRecoverAfterClaimedNodeShutdown()
      throws Exception {
    projectionControl = new ProjectionControl();
    ConfigurableApplicationContext replicaA = startReplica("SYN-DEPLOYMENT-A");
    ConfigurableApplicationContext replicaB = null;
    try {
      replicaB = startReplica("SYN-DEPLOYMENT-B");
      assertIndependentLocalReplicas(replicaA, replicaB);

      NotificationProjectionQuery query = replicaA.getBean(NotificationProjectionQuery.class);
      ExecutionScope scope = organizationScope("SYN-ORG-001");
      String startingCursor = query.currentCursor(scope);
      EventEnvelope first = event("SYN-REFERRAL-001", 1);
      record(replicaA, first);

      projectionControl.pauseNextProjection();
      try (var worker = Executors.newSingleThreadExecutor()) {
        var firstReplicaDispatch =
            worker.submit(() -> replicaA.getBean(OutboxDispatcher.class).dispatchAvailable());
        assertThat(projectionControl.awaitProjectionStart(Duration.ofSeconds(10)))
            .as("replica A reaches the notification projection handler")
            .isTrue();

        DispatchReport secondReplicaDispatch =
            replicaB.getBean(OutboxDispatcher.class).dispatchAvailable();
        assertThat(secondReplicaDispatch)
            .as("replica B cannot claim A's live PostgreSQL lease")
            .isEqualTo(new DispatchReport(0, 0, 0, 0, 0));

        projectionControl.releaseProjection();
        assertThat(firstReplicaDispatch.get(10, TimeUnit.SECONDS).delivered()).isEqualTo(1);
      } finally {
        projectionControl.releaseProjection();
      }

      assertThat(projectionControl.calls()).isEqualTo(1);
      NotificationProjectionPage firstReplay =
          query.listAfter(scope, Optional.of(startingCursor), 10);
      assertThat(firstReplay.refreshRequired()).isFalse();
      assertThat(firstReplay.hints()).hasSize(1);
      assertThat(firstReplay.hints().getFirst().resourceType()).isEqualTo("referral");

      EventEnvelope recovered = event("SYN-REFERRAL-002", 1);
      record(replicaA, recovered);
      Instant claimTime = Instant.now();
      var abandonedClaim =
          replicaA
              .getBean(OutboxStore.class)
              .claimDue(claimTime, Duration.ofMillis(150), 10, 1)
              .getFirst();
      assertThat(abandonedClaim.event().eventId()).isEqualTo(recovered.eventId());

      int stoppedPort = serverPort(replicaA);
      replicaA.close();
      assertThat(replicaA.isActive()).isFalse();
      assertThat(connectionFails(stoppedPort))
          .as("closing replica A closes its embedded HTTP listener")
          .isTrue();

      Thread.sleep(250);
      DispatchReport recoveredByReplicaB =
          replicaB.getBean(OutboxDispatcher.class).dispatchAvailable();
      assertThat(recoveredByReplicaB.delivered())
          .as("replica B reclaims durable work after A's lease expires")
          .isEqualTo(1);
      assertThat(projectionControl.calls()).isEqualTo(2);

      NotificationProjectionPage afterRecovery =
          replicaB
              .getBean(NotificationProjectionQuery.class)
              .listAfter(scope, Optional.of(startingCursor), 10);
      assertThat(afterRecovery.refreshRequired()).isFalse();
      assertThat(afterRecovery.hints()).hasSize(2);

      ConfigurableApplicationContext restartedA = startReplica("SYN-DEPLOYMENT-A");
      try {
        assertThat(serverPort(restartedA)).isNotEqualTo(serverPort(replicaB));
        assertHealthEndpoint(restartedA);
        assertThat(restartedA.getBean(OutboxDispatcher.class).dispatchAvailable())
            .as("restarted replica does not repeat already delivered work")
            .isEqualTo(new DispatchReport(0, 0, 0, 0, 0));
        assertThat(projectionControl.calls()).isEqualTo(2);
      } finally {
        restartedA.close();
      }
    } finally {
      if (replicaB != null && replicaB.isActive()) {
        replicaB.close();
      }
      if (replicaA.isActive()) {
        replicaA.close();
      }
      projectionControl = null;
    }
  }

  @Test
  void recordsMeasuredSyntheticBaselinesForSingleAndTwoReplicaProfiles() throws Exception {
    projectionControl = new ProjectionControl();
    ConfigurableApplicationContext profileA = null;
    ConfigurableApplicationContext profileBFirst = null;
    ConfigurableApplicationContext profileBSecond = null;
    try {
      profileA = startReplica("SYN-PROFILE-A-SINGLE");
      BenchmarkRunResult singleSite =
          measureNotificationReplayProfile(
              "PROFILE_A_SINGLE_SITE", List.of(profileA), "SYN-ORG-001");

      profileBFirst = startReplica("SYN-PROFILE-B-REPLICA-A");
      profileBSecond = startReplica("SYN-PROFILE-B-REPLICA-B");
      assertIndependentLocalReplicas(profileBFirst, profileBSecond);
      BenchmarkRunResult institutional =
          measureNotificationReplayProfile(
              "PROFILE_B_TWO_REPLICA",
              List.of(profileBFirst, profileBSecond),
              "SYN-ORG-001");

      assertThat(singleSite.failedOperations()).isZero();
      assertThat(institutional.failedOperations()).isZero();
      assertThat(singleSite.measuredOperations()).isEqualTo(150);
      assertThat(institutional.measuredOperations()).isEqualTo(150);
    } finally {
      if (profileBSecond != null && profileBSecond.isActive()) {
        profileBSecond.close();
      }
      if (profileBFirst != null && profileBFirst.isActive()) {
        profileBFirst.close();
      }
      if (profileA != null && profileA.isActive()) {
        profileA.close();
      }
      projectionControl = null;
    }
  }

  private static BenchmarkRunResult measureNotificationReplayProfile(
      String profile, List<ConfigurableApplicationContext> replicas, String organizationId)
      throws Exception {
    ExecutionScope scope = organizationScope(organizationId);
    NotificationProjectionQuery initialQuery =
        replicas.getFirst().getBean(NotificationProjectionQuery.class);
    String cursor = initialQuery.currentCursor(scope);
    EventEnvelopeJsonCodec codec = replicas.getFirst().getBean(EventEnvelopeJsonCodec.class);
    var projection = replicas.getFirst().getBean(NotificationEventProjectionHandler.class);
    for (int index = 0; index < 128; index++) {
      EventEnvelope syntheticEvent = event("SYN-REFERRAL-BENCH-" + index, index + 1L);
      projection.handle(
          new InternalEvent(
              syntheticEvent.eventId(),
              syntheticEvent.eventType(),
              syntheticEvent.producerContext(),
              syntheticEvent.aggregatePosition(),
              codec.encode(syntheticEvent)));
    }

    List<NotificationProjectionQuery> queries =
        replicas.stream()
            .map(context -> context.getBean(NotificationProjectionQuery.class))
            .toList();
    HikariDataSource firstPool = (HikariDataSource) replicas.getFirst().getBean(DataSource.class);
    String databaseVersion;
    try (Connection connection = firstPool.getConnection();
        var statement = connection.createStatement();
        var result = statement.executeQuery("SHOW server_version")) {
      result.next();
      databaseVersion = "PostgreSQL " + result.getString(1);
    }
    String planFingerprint =
        notificationReplayPlanFingerprint(firstPool, NotificationScope.from(scope).key());
    String sourceRevision = localSourceRevision();
    var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
    long memoryMiB =
        operatingSystem instanceof com.sun.management.OperatingSystemMXBean extended
            ? Math.max(1L, extended.getTotalMemorySize() / (1024 * 1024))
            : Math.max(1L, Runtime.getRuntime().maxMemory() / (1024 * 1024));
    BenchmarkDeclaration declaration =
        new BenchmarkDeclaration(
            sourceRevision,
            System.getProperty("java.runtime.version"),
            databaseVersion,
            Math.max(1, Runtime.getRuntime().availableProcessors()),
            memoryMiB,
            new BenchmarkWorkloadScale(1, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            BenchmarkRunCondition.WARM,
            10,
            50,
            3);

    AtomicInteger peakActiveConnections = new AtomicInteger();
    ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
    sampler.scheduleAtFixedRate(
        () -> {
          int active =
              replicas.stream()
                  .map(context -> (HikariDataSource) context.getBean(DataSource.class))
                  .map(HikariDataSource::getHikariPoolMXBean)
                  .filter(java.util.Objects::nonNull)
                  .mapToInt(pool -> pool.getActiveConnections())
                  .sum();
          peakActiveConnections.accumulateAndGet(active, Math::max);
        },
        0,
        1,
        TimeUnit.MILLISECONDS);
    BenchmarkRunResult result;
    try {
      result =
          new SyntheticBenchmarkRunner()
              .run(
                  declaration,
                  (scale, stage, repetition, operationIndex) -> {
                    long cpuBefore = processCpuNanos();
                    long operationStarted = System.nanoTime();
                    NotificationProjectionPage page =
                        queries
                            .get(operationIndex % queries.size())
                            .listAfter(scope, Optional.of(cursor), 25);
                    long elapsedNanos = Math.max(1L, System.nanoTime() - operationStarted);
                    long cpuDelta = Math.max(0L, processCpuNanos() - cpuBefore);
                    double cpuUtilization =
                        Math.min(
                            1.0,
                            (double) cpuDelta
                                / (elapsedNanos * Runtime.getRuntime().availableProcessors()));
                    assertThat(page.refreshRequired()).isFalse();
                    assertThat(page.hints()).hasSize(25);
                    long usedMemory =
                        Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                    return new BenchmarkObservation(
                        peakActiveConnections.get(),
                        4,
                        cpuUtilization,
                        usedMemory,
                        Map.of(),
                        Set.of(planFingerprint));
                  });
    } finally {
      sampler.shutdownNow();
    }
    System.out.println(
        "PHASE13_SYNTHETIC_BASELINE "
            + profile
            + " "
            + new ObjectMapper().writeValueAsString(result));
    return result;
  }

  private static String localSourceRevision() throws Exception {
    Process process =
        new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
    String revision;
    try (var output = process.getInputStream()) {
      revision = new String(output.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
    }
    assertThat(process.waitFor()).isZero();
    assertThat(revision).matches("[0-9a-fA-F]{40}");
    return revision;
  }

  private static long processCpuNanos() {
    return ManagementFactory.getOperatingSystemMXBean()
            instanceof com.sun.management.OperatingSystemMXBean extended
        ? Math.max(0L, extended.getProcessCpuTime())
        : 0L;
  }

  private static String notificationReplayPlanFingerprint(
      HikariDataSource dataSource, String scopeKey) throws SQLException, NoSuchAlgorithmException {
    String sql =
        "EXPLAIN (FORMAT JSON) SELECT event_type, resource_type, resource_id, owner_version, "
            + "sequence_number, cursor_id, correlation_id, occurred_at, recorded_at, projected_at "
            + "FROM platform_notification.notification_projection WHERE scope_key = '"
            + scopeKey
            + "' AND sequence_number > 0 ORDER BY sequence_number LIMIT 26";
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      result.next();
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(result.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
  }

  private static ConfigurableApplicationContext startReplica(String deployment) {
    Map<String, String> properties =
        Map.ofEntries(
            Map.entry("spring.application.name", "ayuvahak-" + deployment.toLowerCase()),
            Map.entry("server.port", "0"),
            Map.entry("spring.datasource.url", POSTGRES.getJdbcUrl()),
            Map.entry("spring.datasource.username", POSTGRES.getUsername()),
            Map.entry("spring.datasource.password", POSTGRES.getPassword()),
            Map.entry("ayuvahak.database.url", POSTGRES.getJdbcUrl()),
            Map.entry("ayuvahak.database.username", POSTGRES.getUsername()),
            Map.entry("ayuvahak.database.password", POSTGRES.getPassword()),
            Map.entry(
                "ayuvahak.authentication.issuer", "https://identity.example.test/realms/test"),
            Map.entry("ayuvahak.authentication.jwk-set-uri", "http://127.0.0.1:9/jwks"),
            Map.entry("ayuvahak.authentication.audience", "ayuvahak-api"),
            Map.entry("ayuvahak.authentication.service-client-ids", "synthetic-worker"),
            Map.entry("ayuvahak.authentication.allow-http", "true"),
            Map.entry("ayuvahak.eventing.outbox.dispatcher-enabled", "false"),
            Map.entry("ayuvahak.eventing.outbox.delivered-event-retention", "PT24H"),
            Map.entry("ayuvahak.eventing.outbox.batch-size", "10"),
            Map.entry("ayuvahak.transfer.expiry-polling-enabled", "false"),
            Map.entry("ayuvahak.transfer.reservation-polling-enabled", "false"),
            Map.entry("ayuvahak.capacity.reservation-lifecycle.enabled", "false"),
            Map.entry("management.endpoints.web.exposure.include", "health"),
            Map.entry("logging.level.root", "WARN"));
    String[] arguments =
        properties.entrySet().stream()
            .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
            .toArray(String[]::new);
    return new SpringApplicationBuilder(AyuVahakApplication.class, ReplicaTestConfiguration.class)
        .web(WebApplicationType.SERVLET)
        .run(arguments);
  }

  private static void assertIndependentLocalReplicas(
      ConfigurableApplicationContext replicaA, ConfigurableApplicationContext replicaB)
      throws Exception {
    int portA = serverPort(replicaA);
    int portB = serverPort(replicaB);
    assertThat(portA).isPositive();
    assertThat(portB).isPositive().isNotEqualTo(portA);
    assertHealthEndpoint(replicaA);
    assertHealthEndpoint(replicaB);

    DataSource dataSourceA = replicaA.getBean(DataSource.class);
    DataSource dataSourceB = replicaB.getBean(DataSource.class);
    assertThat(dataSourceA).isNotSameAs(dataSourceB);
    try (Connection connectionA = dataSourceA.getConnection();
        Connection connectionB = dataSourceB.getConnection()) {
      int backendA = backendPid(connectionA);
      int backendB = backendPid(connectionB);
      String databaseA = databaseName(connectionA);
      String databaseB = databaseName(connectionB);
      assertThat(connectionA.getMetaData().getURL()).isEqualTo(connectionB.getMetaData().getURL());
      assertThat(databaseA).isEqualTo("ayuvahak_replica_acceptance").isEqualTo(databaseB);
      assertThat(backendA).isNotEqualTo(backendB);
    }
  }

  private static int backendPid(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT pg_backend_pid()")) {
      result.next();
      return result.getInt(1);
    }
  }

  private static String databaseName(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT current_database()")) {
      result.next();
      return result.getString(1);
    }
  }

  private static void assertHealthEndpoint(ConfigurableApplicationContext context)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + serverPort(context) + "/actuator/health/liveness"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    HttpResponse<Void> response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(200);
  }

  private static boolean connectionFails(int port) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + port + "/actuator/health/liveness"))
              .timeout(Duration.ofSeconds(2))
              .GET()
              .build();
      return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() != 200;
    } catch (Exception closedListener) {
      return true;
    }
  }

  private static int serverPort(ConfigurableApplicationContext context) {
    return context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
  }

  private static ExecutionScope organizationScope(String id) {
    ExternalReference organization =
        new ExternalReference(new IssuerId("urn:ayuvahak:directory"), "Organization", id);
    return new ExecutionContextFactory((actor, scope) -> true)
        .forInteractiveRequest(
            ActorReference.human(
                new IssuerId("https://identity.example.test/realms/test"), "SYN-USER-001"),
            RequestedExecutionScope.organization(organization),
            new CorrelationId("synthetic-replica-correlation"))
        .scope();
  }

  private static EventEnvelope event(String syntheticReferralId, long sequence) {
    ExternalReference organization =
        new ExternalReference(
            new IssuerId("urn:ayuvahak:directory"), "Organization", "SYN-ORG-001");
    ExecutionContext context =
        new ExecutionContextFactory((actor, scope) -> true)
            .forInteractiveRequest(
                ActorReference.human(
                    new IssuerId("https://identity.example.test/realms/test"), "SYN-USER-001"),
                RequestedExecutionScope.organization(organization),
                new CorrelationId("synthetic-replica-correlation"));
    var payload = new ObjectMapper().createObjectNode();
    payload.put(
        "referralReference",
        java.util.UUID.nameUUIDFromBytes(
                syntheticReferralId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString());
    return new EventEnvelope(
        InternalId.generate(),
        EVENT_TYPE,
        EVENT_TYPE.context(),
        new OccurredAt(SYNTHETIC_TIME),
        new RecordedAt(SYNTHETIC_TIME.plusSeconds(1)),
        context,
        Optional.of(new AggregatePosition("referral/" + java.util.UUID.randomUUID(), sequence)),
        payload);
  }

  private static EventEnvelope notificationOnlyEvent(String syntheticResourceId, long sequence) {
    ExternalReference organization =
        new ExternalReference(
            new IssuerId("urn:ayuvahak:directory"), "Organization", "SYN-ORG-001");
    ExecutionContext context =
        new ExecutionContextFactory((actor, scope) -> true)
            .forInteractiveRequest(
                ActorReference.human(
                    new IssuerId("https://identity.example.test/realms/test"), "SYN-USER-001"),
                RequestedExecutionScope.organization(organization),
                new CorrelationId("synthetic-process-replica-correlation"));
    var payload = new ObjectMapper().createObjectNode();
    payload.put("requestVersion", sequence);
    payload.put("requestState", "ACCEPTED");
    EventType eventType = EventType.parse("transfer.federated-receiving-request-changed.v1");
    return new EventEnvelope(
        InternalId.generate(),
        eventType,
        "transfer",
        new OccurredAt(SYNTHETIC_TIME),
        new RecordedAt(SYNTHETIC_TIME.plusSeconds(1)),
        context,
        Optional.of(
            new AggregatePosition(
                "federated-receiving-request/"
                    + java.util.UUID.nameUUIDFromBytes(
                        syntheticResourceId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                sequence)),
        payload);
  }

  private static void record(ConfigurableApplicationContext context, EventEnvelope event) {
    TransactionTemplate transaction =
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    transaction.executeWithoutResult(status -> context.getBean(OutboxWriter.class).record(event));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ReplicaTestConfiguration {

    @Bean
    ReplicaNotificationHandler replicaNotificationHandler(
        NotificationEventProjectionHandler projection) {
      if (projectionControl == null) {
        throw new IllegalStateException("replica acceptance control was not initialized");
      }
      return new ReplicaNotificationHandler(projection, projectionControl);
    }

    @Bean
    @Primary
    EventHandlerRegistry replicaEventHandlerRegistry(ReplicaNotificationHandler handler) {
      return new EventHandlerRegistry(List.of(handler));
    }
  }

  static final class ReplicaNotificationHandler implements InternalEventHandler {

    private final NotificationEventProjectionHandler projection;
    private final ProjectionControl control;

    ReplicaNotificationHandler(
        NotificationEventProjectionHandler projection, ProjectionControl control) {
      this.projection = projection;
      this.control = control;
    }

    @Override
    public Set<EventType> supportedEventTypes() {
      return Set.of(EVENT_TYPE);
    }

    @Override
    public void handle(InternalEvent event) throws Exception {
      control.beforeProjection();
      projection.handle(event);
    }
  }

  static final class ProjectionControl {

    private final AtomicBoolean pauseNext = new AtomicBoolean();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile CountDownLatch entered = new CountDownLatch(0);
    private volatile CountDownLatch release = new CountDownLatch(0);

    void pauseNextProjection() {
      entered = new CountDownLatch(1);
      release = new CountDownLatch(1);
      pauseNext.set(true);
    }

    void beforeProjection() throws InterruptedException {
      calls.incrementAndGet();
      if (pauseNext.compareAndSet(true, false)) {
        entered.countDown();
        if (!release.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("synthetic projection gate expired");
        }
      }
    }

    boolean awaitProjectionStart(Duration timeout) throws InterruptedException {
      return entered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    void releaseProjection() {
      release.countDown();
    }

    int calls() {
      return calls.get();
    }
  }
}
