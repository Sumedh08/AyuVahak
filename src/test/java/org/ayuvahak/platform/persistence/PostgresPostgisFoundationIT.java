package org.ayuvahak.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.push.PushMeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.platform.AyuVahakApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "management.tracing.sampling.probability=1.0")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
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

  @Autowired private MockMvc mockMvc;

  @Autowired private ApplicationContext applicationContext;

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

  @Test
  void exposesOnlyHealthProbesAndPrometheusMetrics() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist());

    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));

    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));

    String metrics =
        mockMvc
            .perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(metrics)
        .contains(
            "jvm_memory_used_bytes",
            "http_server_requests_seconds_count",
            "hikaricp_connections_active")
        .doesNotContainPattern("(?m)^(patient|referral|capacity|transfer)_");
  }

  @Test
  void doesNotExposeActuatorEndpointsBeyondTheExplicitAllowList() throws Exception {
    mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    mockMvc.perform(get("/actuator/loggers")).andExpect(status().isNotFound());
    mockMvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
    mockMvc.perform(get("/actuator/metrics")).andExpect(status().isNotFound());
    mockMvc.perform(get("/actuator/shutdown")).andExpect(status().isNotFound());
  }

  @Test
  void structuredRequestLogIncludesTraceAndCorrelationIds(CapturedOutput output) throws Exception {
    mockMvc
        .perform(get("/api/v1/system-info").header("X-Correlation-ID", "trace-test-1"))
        .andExpect(status().isOk());

    assertThat(output.getAll())
        .containsPattern(
            "(?m).*\\\"traceId\\\":\\\"[a-f0-9]{32}\\\".*"
                + "\\\"spanId\\\":\\\"[a-f0-9]{16}\\\".*"
                + "\\\"correlationId\\\":\\\"trace-test-1\\\".*");
  }

  @Test
  void doesNotConfigurePushMetricsExportByDefault() {
    assertThat(applicationContext.getBeansOfType(PushMeterRegistry.class)).isEmpty();
  }

  private static final class RollbackProbeException extends RuntimeException {}
}
