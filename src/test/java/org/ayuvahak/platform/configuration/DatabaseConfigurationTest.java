package org.ayuvahak.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

class DatabaseConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ConfigurationPropertiesAutoConfiguration.class,
                  ValidationAutoConfiguration.class))
          .withUserConfiguration(DatabasePropertiesConfiguration.class);

  @Test
  void rejectsMissingDatabaseSettingsInsteadOfUsingLocalCredentials() {
    contextRunner
        .withPropertyValues(
            "ayuvahak.database.url=", "ayuvahak.database.username=", "ayuvahak.database.password=")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(BindValidationException.class)
                  .hasStackTraceContaining("url")
                  .hasStackTraceContaining("username")
                  .hasStackTraceContaining("password");
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(DatabaseProperties.class)
  static class DatabasePropertiesConfiguration {}
}
