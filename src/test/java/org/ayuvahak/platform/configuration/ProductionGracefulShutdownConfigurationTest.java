package org.ayuvahak.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionGracefulShutdownConfigurationTest {

  @Test
  void productionProfileDrainsRequestsAndHasABoundedWorkerShutdownWindow() {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues("spring.profiles.active=production-single-site")
        .run(
            context -> {
              assertThat(context.getEnvironment().getProperty("server.shutdown"))
                  .isEqualTo("graceful");
              assertThat(
                      context
                          .getEnvironment()
                          .getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
                  .isEqualTo("30s");
              assertThat(
                      context
                          .getEnvironment()
                          .getProperty("ayuvahak.operations.shutdown.worker-timeout"))
                  .isEqualTo("20s");
            });
  }
}
