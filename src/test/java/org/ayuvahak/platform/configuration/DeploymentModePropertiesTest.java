package org.ayuvahak.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

class DeploymentModePropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ConfigurationPropertiesAutoConfiguration.class,
                  ValidationAutoConfiguration.class))
          .withUserConfiguration(DeploymentModeTestConfiguration.class);

  @Test
  void defaultsToEmptyMode() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(DeploymentModeProperties.class).getMode())
              .isEqualTo(DeploymentMode.EMPTY);
        });
  }

  @Test
  void acceptsExplicitEmptyMode() {
    runner
        .withPropertyValues("ayuvahak.deployment.mode=EMPTY")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(DeploymentModeProperties.class).getMode())
                  .isEqualTo(DeploymentMode.EMPTY);
            });
  }

  @Test
  void acceptsExplicitSyntheticDemoModeOutsideProduction() {
    runner
        .withPropertyValues("ayuvahak.deployment.mode=SYNTHETIC_DEMO")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(DeploymentModeProperties.class).getMode())
                  .isEqualTo(DeploymentMode.SYNTHETIC_DEMO);
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(DeploymentModeProperties.class)
  static class DeploymentModeTestConfiguration {}
}
