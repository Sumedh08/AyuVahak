package org.ayuvahak.matching.domain.fixture;

import java.sql.Connection;
import org.springframework.context.ApplicationContext;

/** Test-only fixture proving that Matching's dependency rule detects technical coupling. */
public final class InfrastructureCoupledMatchingFixture {

  private final ApplicationContext applicationContext;
  private final Connection connection;

  public InfrastructureCoupledMatchingFixture(
      ApplicationContext applicationContext, Connection connection) {
    this.applicationContext = applicationContext;
    this.connection = connection;
  }

  public ApplicationContext applicationContext() {
    return applicationContext;
  }

  public Connection connection() {
    return connection;
  }
}
