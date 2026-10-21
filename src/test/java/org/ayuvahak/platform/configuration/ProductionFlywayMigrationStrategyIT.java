package org.ayuvahak.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ProductionFlywayMigrationStrategyIT {

  private static final String LOCAL_PASSWORD = "synthetic-local-only";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      org.ayuvahak.testcontainers.PostgresTestContainerFactory.create(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withStartupTimeout(Duration.ofMinutes(3))
          .withImagePullPolicy(image -> false)
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(LOCAL_PASSWORD);

  @Test
  void appliesSupportedForwardUpgradeAfterPreUpgradeBackupEvidenceAndPreservesExistingRows() {
    String schema = schemaName();
    Flyway v1 =
        flyway(schema, "db/phase13-upgrade").target(MigrationVersion.fromVersion("1")).load();
    v1.migrate();
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO "
                    + schema
                    + ".synthetic_record (record_id, record_value) VALUES (?, ?)")) {
      statement.setString(1, "SYN-MIGRATION-001");
      statement.setString(2, "preserve-me");
      statement.executeUpdate();
    } catch (Exception exception) {
      throw new AssertionError("synthetic pre-upgrade record could not be inserted", exception);
    }

    Flyway latest = flyway(schema, "db/phase13-upgrade").load();
    ProductionMigrationProperties properties = validBackupEvidence();

    new ProductionFlywayMigrationStrategy(properties).migrate(latest);

    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT record_value, migration_marker FROM "
                    + schema
                    + ".synthetic_record WHERE record_id = ?")) {
      statement.setString(1, "SYN-MIGRATION-001");
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString("record_value")).isEqualTo("preserve-me");
        assertThat(result.getString("migration_marker")).isEqualTo("V2_APPLIED");
      }
    } catch (Exception exception) {
      throw new AssertionError("synthetic record could not be verified after upgrade", exception);
    }
    assertThat(latest.info().pending()).isEmpty();
    assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("2");
  }

  @Test
  void blocksPendingProductionMigrationWithoutBackupEvidenceBeforeCreatingHistory() {
    String schema = schemaName();
    Flyway flyway = flyway(schema, "db/phase13-upgrade").load();
    ProductionMigrationProperties properties = new ProductionMigrationProperties();

    assertThatThrownBy(() -> new ProductionFlywayMigrationStrategy(properties).migrate(flyway))
        .isInstanceOf(IllegalStateException.class)
        .satisfies(
            exception -> {
              assertThat(exception.getMessage())
                  .contains("pre-upgrade backup evidence is required")
                  .doesNotContain("synthetic-secret");
            });

    assertThat(schemaTableExists(schema, "flyway_schema_history")).isFalse();
    assertThat(schemaTableExists(schema, "synthetic_record")).isFalse();
  }

  @Test
  void serializesConcurrentReplicaStartupAndAppliesMigrationOnce() throws Exception {
    String schema = schemaName();
    Flyway first = flyway(schema, "db/phase13-concurrent").load();
    Flyway second = flyway(schema, "db/phase13-concurrent").load();
    ProductionMigrationProperties properties = validBackupEvidence();
    ProductionFlywayMigrationStrategy firstStrategy =
        new ProductionFlywayMigrationStrategy(properties);
    ProductionFlywayMigrationStrategy secondStrategy =
        new ProductionFlywayMigrationStrategy(properties);
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<String> firstResult = workers.submit(() -> runMigration(start, firstStrategy, first));
      Future<String> secondResult =
          workers.submit(() -> runMigration(start, secondStrategy, second));
      start.countDown();
      List<String> results = List.of(firstResult.get(), secondResult.get());

      assertThat(results).containsExactlyInAnyOrder("MIGRATED", "BLOCKED");
    }

    assertThat(schemaTableExists(schema, "synthetic_migration_lock_probe")).isTrue();
    assertThat(appliedSqlMigrationCount(schema)).isEqualTo(1);
    assertThat(syntheticMigrationProbeCount(schema)).isEqualTo(1);
  }

  @Test
  void failedMigrationPreventsInitializerCompletionAndReleasesMigratorLock() throws Exception {
    String schema = schemaName();
    Flyway flyway = flyway(schema, "db/phase13-failure").load();
    ProductionFlywayMigrationStrategy strategy =
        new ProductionFlywayMigrationStrategy(validBackupEvidence());
    FlywayMigrationInitializer initializer = new FlywayMigrationInitializer(flyway, strategy);

    assertThatThrownBy(initializer::afterPropertiesSet).isInstanceOf(Exception.class);
    assertThat(schemaTableExists(schema, "synthetic_failure_probe")).isFalse();

    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT pg_try_advisory_lock(1096379734, 1296648018)");
        var result = statement.executeQuery()) {
      assertThat(result.next()).isTrue();
      assertThat(result.getBoolean(1)).isTrue();
    }
  }

  private static String runMigration(
      CountDownLatch start, ProductionFlywayMigrationStrategy strategy, Flyway flyway) {
    try {
      start.await();
      strategy.migrate(flyway);
      return "MIGRATED";
    } catch (IllegalStateException exception) {
      if (exception.getMessage() != null
          && exception.getMessage().contains("another production migrator is active")) {
        return "BLOCKED";
      }
      throw exception;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("synthetic migration race was interrupted", exception);
    }
  }

  private static ProductionMigrationProperties validBackupEvidence() {
    ProductionMigrationProperties properties = new ProductionMigrationProperties();
    properties.setBackupEvidenceReference("SYN-BACKUP-EVIDENCE-001");
    properties.setBackupCompletedAt(Instant.now().minusSeconds(60).toString());
    return properties;
  }

  private static FluentConfiguration flyway(String schema, String location) {
    return Flyway.configure()
        .dataSource(dataSource())
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:" + location);
  }

  private static DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Connection connection() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static boolean schemaTableExists(String schema, String table) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?)")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getBoolean(1);
      }
    } catch (Exception exception) {
      throw new AssertionError("synthetic schema state could not be inspected", exception);
    }
  }

  private static int appliedSqlMigrationCount(String schema) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM "
                    + schema
                    + ".flyway_schema_history WHERE success AND type = 'SQL'")) {
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    } catch (Exception exception) {
      throw new AssertionError("synthetic migration history could not be inspected", exception);
    }
  }

  private static int syntheticMigrationProbeCount(String schema) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM " + schema + ".synthetic_migration_lock_probe")) {
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    } catch (Exception exception) {
      throw new AssertionError("synthetic migration effect could not be inspected", exception);
    }
  }

  private static String schemaName() {
    return "p13_" + UUID.randomUUID().toString().replace("-", "");
  }
}
