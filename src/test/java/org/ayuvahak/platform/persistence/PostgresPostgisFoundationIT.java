package org.ayuvahak.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.platform.AyuVahakApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class PostgresPostgisFoundationIT {

  private static final String LOCAL_TEST_PASSWORD = "local-test-only";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(LOCAL_TEST_PASSWORD);

  @DynamicPropertySource
  static void configureDatabase(DynamicPropertyRegistry registry) {
    registry.add("AYUVAHAK_DATABASE_URL", POSTGRES::getJdbcUrl);
    registry.add("AYUVAHAK_DATABASE_USERNAME", POSTGRES::getUsername);
    registry.add("AYUVAHAK_DATABASE_PASSWORD", POSTGRES::getPassword);
  }

  @Autowired private AyuVahakApplication application;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private Flyway flyway;

  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void startsTheCanonicalApplicationAndConnectsToPostgres() {
    assertThat(application).isNotNull();
    assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class))
        .isEqualTo("ayuvahak");
  }

  @Test
  void appliesOnlyThePlatformMigrationAndRecordsItsHistory() {
    assertThat(flyway.info().applied()).hasSize(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' "
                    + "AND table_type = 'BASE TABLE' AND table_name NOT IN "
                    + "('flyway_schema_history', 'spatial_ref_sys')",
                Integer.class))
        .isZero();
  }

  @Test
  void executesPostgisAndSpringJdbcQueriesAgainstTheRealDatabase() {
    String postgisVersion =
        jdbcTemplate.queryForObject("SELECT PostGIS_Full_Version()", String.class);
    Integer jdbcResult = jdbcTemplate.queryForObject("SELECT 6 * 7", Integer.class);

    assertThat(postgisVersion).contains("POSTGIS=\"3.6.4");
    assertThat(jdbcResult).isEqualTo(42);
  }

  @Test
  void rollsBackJdbcWorkThroughSpringTransactionInfrastructure() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    AtomicReference<String> transactionId = new AtomicReference<>();

    assertThatThrownBy(
            () ->
                transaction.execute(
                    status -> {
                      transactionId.set(
                          jdbcTemplate.queryForObject(
                              "SELECT pg_current_xact_id()::text", String.class));
                      throw new RollbackProbeException();
                    }))
        .isInstanceOf(RollbackProbeException.class);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT pg_xact_status(?::xid8)", String.class, transactionId.get()))
        .isEqualTo("aborted");
  }

  private static final class RollbackProbeException extends RuntimeException {}
}
