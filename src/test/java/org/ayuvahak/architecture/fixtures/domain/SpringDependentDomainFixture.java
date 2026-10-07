package org.ayuvahak.architecture.fixtures.domain;

import org.springframework.context.ApplicationContext;

/**
 * Test-only fixture proving that a domain type depending on Spring violates the domain boundary.
 */
public final class SpringDependentDomainFixture {

  private final ApplicationContext applicationContext;

  public SpringDependentDomainFixture(ApplicationContext applicationContext) {
    this.applicationContext = applicationContext;
  }

  public ApplicationContext applicationContext() {
    return applicationContext;
  }
}
