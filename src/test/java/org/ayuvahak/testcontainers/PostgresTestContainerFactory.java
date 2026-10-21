package org.ayuvahak.testcontainers;

import java.time.Duration;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Creates isolated PostgreSQL test containers with a bounded startup allowance for local PostGIS
 * initialization. The readiness signal and required PostgreSQL log occurrences remain unchanged;
 * this only avoids the upstream container's short default timeout under the full local suite load.
 */
public final class PostgresTestContainerFactory {
  private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);
  private static final String READY_LOG = ".*database system is ready to accept connections.*\\s";

  private PostgresTestContainerFactory() {}

  public static PostgreSQLContainer create(String image) {
    return configure(new PostgreSQLContainer(image));
  }

  public static PostgreSQLContainer create(DockerImageName image) {
    return configure(new PostgreSQLContainer(image));
  }

  private static PostgreSQLContainer configure(PostgreSQLContainer container) {
    container.waitingFor(
        Wait.forLogMessage(READY_LOG, 2).withStartupTimeout(STARTUP_TIMEOUT));
    container.withStartupTimeout(STARTUP_TIMEOUT);
    return container;
  }
}
