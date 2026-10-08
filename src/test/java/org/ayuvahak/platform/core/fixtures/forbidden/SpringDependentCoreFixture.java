package org.ayuvahak.platform.core.fixtures.forbidden;

import java.net.http.HttpClient;
import java.sql.Connection;
import org.springframework.context.ApplicationContext;

/** Deliberate test-only violation proving the platform core dependency rule is executable. */
public final class SpringDependentCoreFixture {

  private ApplicationContext applicationContext;
  private Connection connection;
  private HttpClient httpClient;
}
