package org.ayuvahak.integration;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.sql.DataSource;
import tools.jackson.databind.ObjectMapper;

/** Local-only characterization harness for the two frozen Phase 13 deployment profiles. */
final class Phase13MixedWorkloadBenchmark {

  static final int WARMUP_CYCLES = 2;
  static final int MEASURED_CYCLES = 5;
  static final int CONCURRENCY = 4;
  static final int OPERATIONS_PER_CYCLE = 20;
  static final int MAX_POOL_SIZE_PER_REPLICA = 10;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .proxy(
              new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                  return List.of(Proxy.NO_PROXY);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
              })
          .build();

  private Phase13MixedWorkloadBenchmark() {}

  static Process startApplication(
      String applicationName,
      int httpPort,
      int jmxPort,
      String jdbcUrl,
      String username,
      String password,
      String issuer,
      String jwksUri,
      Map<String, String> profileProperties,
      Path logFile)
      throws Exception {
    Files.createDirectories(logFile.toAbsolutePath().getParent());
    Path jar = Path.of("target", "ayuvahak-platform-0.1.0-SNAPSHOT.jar").toAbsolutePath();
    if (!Files.isRegularFile(jar)) {
      throw new AssertionError("packaged AyuVahak application JAR is missing: " + jar);
    }
    String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    List<String> command =
        new ArrayList<>(
            List.of(
                java,
                "-Duser.timezone=UTC",
                "-Dcom.sun.management.jmxremote",
                "-Dcom.sun.management.jmxremote.port=" + jmxPort,
                "-Dcom.sun.management.jmxremote.rmi.port=" + jmxPort,
                "-Dcom.sun.management.jmxremote.authenticate=false",
                "-Dcom.sun.management.jmxremote.ssl=false",
                "-Dcom.sun.management.jmxremote.local.only=true",
                "-Djava.rmi.server.hostname=127.0.0.1",
                "-jar",
                jar.toString(),
                "--server.address=127.0.0.1",
                "--server.port=" + httpPort,
                "--spring.flyway.enabled=false",
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password,
                "--spring.datasource.hikari.maximum-pool-size=" + MAX_POOL_SIZE_PER_REPLICA,
                "--spring.datasource.hikari.data-source-properties.ApplicationName="
                    + applicationName,
                "--ayuvahak.database.url=" + jdbcUrl,
                "--ayuvahak.database.username=" + username,
                "--ayuvahak.database.password=" + password,
                "--ayuvahak.authentication.issuer=" + issuer,
                "--ayuvahak.authentication.jwk-set-uri=" + jwksUri,
                "--ayuvahak.authentication.audience=ayuvahak-api",
                "--ayuvahak.authentication.allow-http=true",
                "--ayuvahak.authentication.service-client-ids=synthetic-worker,synthetic-integration-service,synthetic-federated-origin-peer,synthetic-federated-capacity-peer,synthetic-federated-directory-peer,ayuvahak-transport-worker,ayuvahak-transfer-owner-event-consumer,synthetic-transport-provider,synthetic-mcsd-worker",
                "--ayuvahak.federation.identity.deployment-id=SYN-DEPLOYMENT-RECEIVER",
                "--ayuvahak.federation.identity.installation-binding=SYNTHETIC-LOCAL-TEST-BINDING-ONLY-DO-NOT-USE-PRODUCTION-001",
                "--ayuvahak.eventing.outbox.dispatcher-enabled=false",
                "--ayuvahak.eventing.outbox.delivered-event-retention=PT24H",
                "--ayuvahak.eventing.outbox.batch-size=10",
                "--ayuvahak.transfer.expiry-polling-enabled=false",
                "--ayuvahak.transfer.reservation-polling-enabled=false",
                "--ayuvahak.capacity.reservation-lifecycle.enabled=false",
                "--management.endpoints.web.exposure.include=health",
                "--logging.level.root=WARN"));
    profileProperties.forEach(
        (key, value) -> command.add("--" + key + "=" + Objects.requireNonNull(value)));
    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile())
            .start();
    awaitHealth(process, httpPort, logFile, Duration.ofSeconds(90));
    return process;
  }

  static void awaitHealth(Process process, int port, Path logFile, Duration timeout)
      throws Exception {
    Instant deadline = Instant.now().plus(timeout);
    URI health = URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness");
    while (Instant.now().isBefore(deadline)) {
      if (!process.isAlive()) {
        throw new AssertionError(
            "packaged AyuVahak process exited during startup; log=" + tail(logFile));
      }
      try {
        HttpResponse<Void> response =
            HTTP.send(
                HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 200) {
          return;
        }
      } catch (IOException starting) {
        // The process is still binding its local HTTP listener.
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "packaged AyuVahak process did not become healthy; log=" + tail(logFile));
  }

  static void stop(Process process) throws InterruptedException {
    if (process == null || !process.isAlive()) {
      return;
    }
    process.destroy();
    if (!process.waitFor(8, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
    }
  }

  static ProfileResult measure(
      String profile,
      List<Replica> replicas,
      List<Operation> weightedMix,
      DataSource database,
      String sourceRevision,
      Map<String, Integer> workloadDimensions,
      Map<String, String> queryPlanFingerprints)
      throws Exception {
    if (replicas.isEmpty() || weightedMix.size() != OPERATIONS_PER_CYCLE) {
      throw new IllegalArgumentException("benchmark profile/mix has an invalid size");
    }
    for (int count : workloadDimensions.values()) {
      if (count <= 0) {
        throw new AssertionError("required synthetic workload dimensions must be non-zero");
      }
    }
    try (Connection connection = database.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
      statement.execute("SELECT pg_stat_statements_reset()");
    }
    List<ReplicaMetric> replicaMetrics =
        replicas.stream().map(Phase13MixedWorkloadBenchmark::attach).toList();
    List<Long> latenciesNanos = new CopyOnWriteArrayList<>();
    Map<String, List<Long>> latenciesByOperation = new java.util.concurrent.ConcurrentHashMap<>();
    List<String> failures = new CopyOnWriteArrayList<>();
    AtomicInteger timeouts = new AtomicInteger();
    AtomicInteger nextReplica = new AtomicInteger();
    AtomicInteger peakDbActive = new AtomicInteger();
    AtomicInteger peakDbConnections = new AtomicInteger();
    long measuredStarted;
    long processCpuBefore;
    ExecutorService workers = Executors.newFixedThreadPool(CONCURRENCY);
    ScheduledExecutorService databaseSampler = Executors.newSingleThreadScheduledExecutor();
    java.util.concurrent.atomic.AtomicLong peakJvmHeapBytes =
        new java.util.concurrent.atomic.AtomicLong();
    ScheduledExecutorService processSampler = Executors.newSingleThreadScheduledExecutor();
    try {
      for (int cycle = 0; cycle < WARMUP_CYCLES; cycle++) {
        runCycle(
            workers,
            weightedMix,
            replicas,
            nextReplica,
            latenciesByOperation,
            null,
            failures,
            timeouts);
      }
      latenciesByOperation.clear();
      resetStatementStatistics(database);
      processCpuBefore = totalCpuNanos(replicaMetrics);
      processSampler.scheduleAtFixedRate(
          () -> {
            try {
              long currentHeap =
                  replicaMetrics.stream().mapToLong(ReplicaMetric::heapUsedBytes).sum();
              peakJvmHeapBytes.accumulateAndGet(currentHeap, Math::max);
            } catch (RuntimeException failure) {
              failures.add("process-sampler:" + failure.getClass().getSimpleName());
            }
          },
          0,
          200,
          TimeUnit.MILLISECONDS);
      databaseSampler.scheduleAtFixedRate(
          () -> {
            try {
              int[] pool = databasePoolCounts(database, replicas);
              peakDbActive.accumulateAndGet(pool[0], Math::max);
              peakDbConnections.accumulateAndGet(pool[1], Math::max);
            } catch (SQLException failure) {
              failures.add("database-sampler:" + failure.getClass().getSimpleName());
            }
          },
          0,
          300,
          TimeUnit.MILLISECONDS);
      measuredStarted = System.nanoTime();
      for (int cycle = 0; cycle < MEASURED_CYCLES; cycle++) {
        runCycle(
            workers,
            weightedMix,
            replicas,
            nextReplica,
            latenciesByOperation,
            latenciesNanos,
            failures,
            timeouts);
      }
      Map<String, Integer> expectedMeasuredOperations = new LinkedHashMap<>();
      for (Operation operation : weightedMix) {
        expectedMeasuredOperations.merge(operation.name(), MEASURED_CYCLES, Integer::sum);
      }
      expectedMeasuredOperations.forEach(
          (operation, expected) -> {
            List<Long> observed = latenciesByOperation.getOrDefault(operation, List.of());
            if (observed.size() != expected) {
              throw new AssertionError(
                  "measured workload did not exercise "
                      + operation
                      + " as declared; expected="
                      + expected
                      + ", observed="
                      + observed.size());
            }
          });
      long measuredElapsedNanos = Math.max(1L, System.nanoTime() - measuredStarted);
      long processCpuAfter = totalCpuNanos(replicaMetrics);
      long processCpu = Math.max(0L, processCpuAfter - processCpuBefore);
      long queryCount = totalStatementCalls(database);
      BacklogSnapshot backlog = backlog(database);
      String databaseVersion = databaseVersion(database);
      double throughput = (double) latenciesNanos.size() * 1_000_000_000d / measuredElapsedNanos;
      ProfileResult result =
          new ProfileResult(
              profile,
              sourceRevision,
              System.getProperty("java.runtime.version"),
              databaseVersion,
              ManagementFactory.getOperatingSystemMXBean().getName(),
              Runtime.getRuntime().availableProcessors(),
              WARMUP_CYCLES,
              MEASURED_CYCLES,
              CONCURRENCY,
              OPERATIONS_PER_CYCLE,
              measuredElapsedNanos,
              latenciesNanos.size(),
              failures.size(),
              timeouts.get(),
              percentileMillis(latenciesNanos, 0.50),
              percentileMillis(latenciesNanos, 0.95),
              percentileMillis(latenciesNanos, 0.99),
              throughput,
              processCpu,
              (double) processCpu / measuredElapsedNanos,
              peakJvmHeapBytes.get(),
              peakDbActive.get(),
              peakDbConnections.get(),
              replicas.size() * MAX_POOL_SIZE_PER_REPLICA,
              queryCount,
              Map.copyOf(workloadDimensions),
              summarizeOperationLatency(latenciesByOperation),
              queryPlanFingerprints,
              backlog,
              replicas.stream().map(Replica::pid).toList(),
              replicas.stream().map(Replica::httpPort).toList());
      System.out.println("PHASE13_PROFILE_BENCHMARK " + JSON.writeValueAsString(result));
      if (!failures.isEmpty()) {
        throw new AssertionError("mixed workload operations failed: " + failures);
      }
      if (timeouts.get() != 0) {
        throw new AssertionError("mixed workload timed out " + timeouts.get() + " times");
      }
      return result;
    } finally {
      databaseSampler.shutdownNow();
      processSampler.shutdownNow();
      workers.shutdownNow();
      workers.awaitTermination(5, TimeUnit.SECONDS);
      replicaMetrics.forEach(ReplicaMetric::close);
    }
  }

  private static void runCycle(
      ExecutorService workers,
      List<Operation> mix,
      List<Replica> replicas,
      AtomicInteger nextReplica,
      Map<String, List<Long>> byOperation,
      List<Long> total,
      List<String> failures,
      AtomicInteger timeouts)
      throws Exception {
    List<Future<OperationResult>> futures = new ArrayList<>(mix.size());
    for (Operation operation : mix) {
      Replica replica = replicas.get(Math.floorMod(nextReplica.getAndIncrement(), replicas.size()));
      futures.add(
          workers.submit(
              () -> {
                long started = System.nanoTime();
                try {
                  operation.action().run(replica);
                  return new OperationResult(
                      operation.name(), Math.max(1L, System.nanoTime() - started), null);
                } catch (Throwable failure) {
                  return new OperationResult(
                      operation.name(), Math.max(1L, System.nanoTime() - started), failure);
                }
              }));
    }
    for (Future<OperationResult> future : futures) {
      OperationResult result =
          future.get(REQUEST_TIMEOUT.toMillis() + 15_000, TimeUnit.MILLISECONDS);
      if (result.failure() != null) {
        Throwable failure = result.failure();
        if (failure.getClass().getSimpleName().toLowerCase().contains("timeout")) {
          timeouts.incrementAndGet();
        }
        failures.add(result.operation() + ":" + failureSummary(failure));
      }
      byOperation
          .computeIfAbsent(result.operation(), ignored -> new CopyOnWriteArrayList<>())
          .add(result.elapsedNanos());
      if (total != null) {
        total.add(result.elapsedNanos());
      }
    }
  }

  static HttpResponse<String> sendJson(
      Replica replica, String path, String token, String scopeHeaders, String body)
      throws Exception {
    return sendJson(replica, path, token, scopeHeaders, body, Optional.empty());
  }

  static HttpResponse<String> sendJson(
      Replica replica,
      String path,
      String token,
      String scopeHeaders,
      String body,
      String idempotencyKey)
      throws Exception {
    return sendJson(
        replica,
        path,
        token,
        scopeHeaders,
        body,
        Optional.of(Objects.requireNonNull(idempotencyKey)));
  }

  private static HttpResponse<String> sendJson(
      Replica replica,
      String path,
      String token,
      String scopeHeaders,
      String body,
      Optional<String> idempotencyKey)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + replica.httpPort() + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json");
    idempotencyKey.ifPresent(key -> request.header("Idempotency-Key", key));
    addScopeHeaders(request, scopeHeaders);
    HttpResponse<String> response =
        HTTP.send(
            request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    requireSuccess(response);
    return response;
  }

  static HttpResponse<String> sendGet(
      Replica replica, String path, String token, String scopeHeaders) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + replica.httpPort() + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + token);
    addScopeHeaders(request, scopeHeaders);
    HttpResponse<String> response =
        HTTP.send(
            request.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    requireSuccess(response);
    return response;
  }

  static void replaySse(Replica replica, String token, String scopeHeaders, String cursor)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + replica.httpPort() + "/api/v1/notifications/stream"))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .header("Last-Event-ID", cursor);
    addScopeHeaders(request, scopeHeaders);
    HttpResponse<java.util.concurrent.Flow.Publisher<List<java.nio.ByteBuffer>>> response =
        HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofPublisher());
    if (response.statusCode() != 200) {
      response
          .body()
          .subscribe(
              new java.util.concurrent.Flow.Subscriber<>() {
                @Override
                public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                  subscription.cancel();
                }

                @Override
                public void onNext(List<java.nio.ByteBuffer> ignored) {}

                @Override
                public void onError(Throwable ignored) {}

                @Override
                public void onComplete() {}
              });
      throw new AssertionError("SSE returned HTTP " + response.statusCode());
    }
    var firstEvent = new java.util.concurrent.CompletableFuture<String>();
    var subscription =
        new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Flow.Subscription>();
    var eventText = new StringBuilder();
    response
        .body()
        .subscribe(
            new java.util.concurrent.Flow.Subscriber<>() {
              @Override
              public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
                subscription.set(value);
                value.request(1);
              }

              @Override
              public void onNext(List<java.nio.ByteBuffer> buffers) {
                synchronized (eventText) {
                  for (java.nio.ByteBuffer buffer : buffers) {
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    eventText.append(new String(bytes, StandardCharsets.UTF_8));
                  }
                  String normalized = eventText.toString().replace("\r\n", "\n");
                  if (normalized.contains("\n\n")) {
                    firstEvent.complete(normalized);
                    subscription.get().cancel();
                    return;
                  }
                }
                subscription.get().request(1);
              }

              @Override
              public void onError(Throwable failure) {
                firstEvent.completeExceptionally(failure);
              }

              @Override
              public void onComplete() {
                firstEvent.completeExceptionally(
                    new java.io.EOFException("SSE stream completed before a hint arrived"));
              }
            });
    String event;
    try {
      event = firstEvent.get(3, TimeUnit.SECONDS);
    } catch (java.util.concurrent.TimeoutException timeout) {
      var activeSubscription = subscription.get();
      if (activeSubscription != null) {
        activeSubscription.cancel();
      }
      throw new AssertionError("SSE replay did not deliver a bounded hint before timeout");
    }
    if (!event.contains("event:") || !event.contains("data:")) {
      throw new AssertionError("SSE replay did not deliver a typed notification hint");
    }
  }

  static String planFingerprint(DataSource source, String query) throws Exception {
    String plan;
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("EXPLAIN (FORMAT JSON) " + query)) {
      if (!result.next()) {
        throw new SQLException("PostgreSQL did not return a query plan");
      }
      plan = result.getString(1);
    }
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(plan.getBytes(StandardCharsets.UTF_8)));
  }

  private static String failureSummary(Throwable failure) {
    String message = Objects.toString(failure.getMessage(), "").replaceAll("\\s+", " ").trim();
    if (message.length() > 180) {
      message = message.substring(0, 180);
    }
    return failure.getClass().getSimpleName() + (message.isEmpty() ? "" : "[" + message + "]");
  }

  private static void requireSuccess(HttpResponse<String> response) {
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new AssertionError(
          "benchmark request returned HTTP "
              + response.statusCode()
              + " responseBody="
              + response.body().substring(0, Math.min(response.body().length(), 180)));
    }
  }

  private static void addScopeHeaders(HttpRequest.Builder request, String headers) {
    if (headers == null || headers.isBlank()) {
      return;
    }
    for (String header : headers.split("\\n")) {
      int separator = header.indexOf(':');
      request.header(header.substring(0, separator), header.substring(separator + 1));
    }
  }

  private static ReplicaMetric attach(Replica replica) {
    try {
      String address = "service:jmx:rmi:///jndi/rmi://127.0.0.1:" + replica.jmxPort() + "/jmxrmi";
      Instant deadline = Instant.now().plusSeconds(15);
      Exception lastFailure = null;
      while (Instant.now().isBefore(deadline)) {
        if (!replica.process().isAlive()) {
          throw new IllegalStateException("packaged process exited before JMX became ready");
        }
        try {
          JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(address));
          return new ReplicaMetric(replica.pid(), connector, connector.getMBeanServerConnection());
        } catch (IOException unavailable) {
          lastFailure = unavailable;
          Thread.sleep(100);
        }
      }
      throw new IllegalStateException("local JMX endpoint did not become ready", lastFailure);
    } catch (Exception failure) {
      throw new IllegalStateException(
          "cannot read local process metrics for PID " + replica.pid(), failure);
    }
  }

  private static long totalCpuNanos(List<ReplicaMetric> replicas) {
    return replicas.stream().mapToLong(ReplicaMetric::processCpuNanos).sum();
  }

  private static void resetStatementStatistics(DataSource dataSource) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SELECT pg_stat_statements_reset()");
    }
  }

  private static int[] databasePoolCounts(DataSource dataSource, List<Replica> replicas)
      throws SQLException {
    String names =
        replicas.stream()
            .map(Replica::applicationName)
            .map(name -> "'" + name.replace("'", "''") + "'")
            .reduce((left, right) -> left + "," + right)
            .orElseThrow();
    String sql =
        "SELECT COUNT(*) FILTER (WHERE state = 'active'), COUNT(*) "
            + "FROM pg_stat_activity WHERE application_name IN ("
            + names
            + ")";
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return new int[] {result.getInt(1), result.getInt(2)};
    }
  }

  private static long totalStatementCalls(DataSource dataSource) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT COALESCE(SUM(calls), 0)::bigint FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database()) AND query NOT ILIKE '%pg_stat_statements%'")) {
      result.next();
      return result.getLong(1);
    }
  }

  private static BacklogSnapshot backlog(DataSource dataSource) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      Duration oldestOutbox =
          queryDuration(
              statement,
              "SELECT COALESCE(EXTRACT(EPOCH FROM (clock_timestamp() - MIN(created_at))), 0) "
                  + "FROM platform_eventing.outbox_delivery "
                  + "WHERE state IN ('PENDING', 'LEASED', 'STALLED', 'UNHANDLED')");
      Duration notificationFreshness =
          queryDuration(
              statement,
              "SELECT COALESCE(EXTRACT(EPOCH FROM (clock_timestamp() - MAX(projected_at))), 0) "
                  + "FROM platform_notification.notification_projection");
      int unresolvedExports;
      Duration oldestExport;
      try (ResultSet result =
          statement.executeQuery(
              "SELECT COUNT(*), COALESCE(EXTRACT(EPOCH FROM (clock_timestamp() - MIN(created_at))), 0) "
                  + "FROM analytics.dhis2_export_operation WHERE operation_state IN "
                  + "('PREPARED','SENDING','OUTCOME_UNKNOWN','RECONCILIATION_REQUIRED')")) {
        result.next();
        unresolvedExports = result.getInt(1);
        oldestExport = Duration.ofMillis(Math.max(0L, (long) (result.getDouble(2) * 1000)));
      }
      return new BacklogSnapshot(
          oldestOutbox.toMillis(),
          notificationFreshness.toMillis(),
          unresolvedExports,
          oldestExport.toMillis());
    }
  }

  private static Duration queryDuration(Statement statement, String sql) throws SQLException {
    try (ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return Duration.ofMillis(Math.max(0L, (long) (result.getDouble(1) * 1000)));
    }
  }

  private static String databaseVersion(DataSource dataSource) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SHOW server_version")) {
      result.next();
      return "PostgreSQL " + result.getString(1);
    }
  }

  private static double percentileMillis(List<Long> sample, double percentile) {
    if (sample.isEmpty()) {
      return 0;
    }
    List<Long> sorted = sample.stream().sorted().toList();
    int index = Math.min(sorted.size() - 1, (int) Math.ceil(percentile * sorted.size()) - 1);
    return sorted.get(index) / 1_000_000d;
  }

  private static Map<String, LatencySummary> summarizeOperationLatency(
      Map<String, List<Long>> samples) {
    Map<String, LatencySummary> result = new LinkedHashMap<>();
    samples.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry ->
                result.put(
                    entry.getKey(),
                    new LatencySummary(
                        entry.getValue().size(),
                        percentileMillis(entry.getValue(), 0.50),
                        percentileMillis(entry.getValue(), 0.95),
                        percentileMillis(entry.getValue(), 0.99))));
    return Map.copyOf(result);
  }

  private static String tail(Path path) {
    try {
      List<String> lines = Files.readAllLines(path);
      int start = Math.max(0, lines.size() - 30);
      return String.join("\n", lines.subList(start, lines.size()));
    } catch (IOException unreadable) {
      return "<unavailable>";
    }
  }

  record Replica(String applicationName, int httpPort, int jmxPort, Process process) {
    long pid() {
      return process.pid();
    }
  }

  record Operation(String name, OperationAction action) {
    Operation {
      Objects.requireNonNull(name);
      Objects.requireNonNull(action);
    }
  }

  @FunctionalInterface
  interface OperationAction {
    void run(Replica selectedReplica) throws Exception;
  }

  record ProfileResult(
      String profile,
      String sourceRevision,
      String javaRuntime,
      String databaseVersion,
      String operatingSystem,
      int availableProcessors,
      int warmupCycles,
      int measuredCycles,
      int concurrency,
      int operationsPerCycle,
      long measurementDurationNanos,
      int measuredOperations,
      int failures,
      int timeouts,
      double p50Millis,
      double p95Millis,
      double p99Millis,
      double throughputOperationsPerSecond,
      long processCpuNanos,
      double processCpuCoreEquivalents,
      long peakJvmHeapBytes,
      int peakActiveDbConnections,
      int peakDbConnections,
      int configuredDbPoolCapacity,
      long postgresStatementCalls,
      Map<String, Integer> workloadDimensions,
      Map<String, LatencySummary> operationLatency,
      Map<String, String> queryPlanFingerprints,
      BacklogSnapshot backlog,
      List<Long> processIds,
      List<Integer> httpPorts) {}

  record LatencySummary(int operations, double p50Millis, double p95Millis, double p99Millis) {}

  record BacklogSnapshot(
      long oldestPendingOutboxAgeMillis,
      long latestNotificationProjectionAgeMillis,
      int unresolvedExportOperations,
      long oldestUnresolvedExportAgeMillis) {}

  private record OperationResult(String operation, long elapsedNanos, Throwable failure) {}

  private record ReplicaMetric(
      long processId, JMXConnector connector, MBeanServerConnection server) {
    long processCpuNanos() {
      try {
        return ((Number)
                server.getAttribute(
                    new ObjectName("java.lang:type=OperatingSystem"), "ProcessCpuTime"))
            .longValue();
      } catch (Exception failure) {
        throw new IllegalStateException("could not read local JVM CPU time", failure);
      }
    }

    long heapUsedBytes() {
      try {
        javax.management.openmbean.CompositeData heap =
            (javax.management.openmbean.CompositeData)
                server.getAttribute(new ObjectName("java.lang:type=Memory"), "HeapMemoryUsage");
        return ((Number) heap.get("used")).longValue();
      } catch (Exception failure) {
        throw new IllegalStateException("could not read local JVM heap use", failure);
      }
    }

    void close() {
      try {
        connector.close();
      } catch (IOException ignored) {
        // The benchmark process is being discarded after measurement.
      }
    }
  }
}
