package org.ayuvahak.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class PlatformPropertiesTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ConfigurationPropertiesAutoConfiguration.class,
                  ValidationAutoConfiguration.class))
          .withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void bindsConfiguredServiceName() {
    contextRunner
        .withPropertyValues("ayuvahak.platform.service-name=AyuVahak test")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(PlatformProperties.class).serviceName())
                  .isEqualTo("AyuVahak test");
            });
  }

  @Test
  void environmentPropertyOverridesTheConfiguredServiceName() {
    contextRunner
        .withInitializer(
            context ->
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(
                        new SystemEnvironmentPropertySource(
                            "synthetic-environment",
                            Map.of("AYUVAHAK_PLATFORM_SERVICE_NAME", "AyuVahak from environment"))))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(PlatformProperties.class).serviceName())
                  .isEqualTo("AyuVahak from environment");
            });
  }

  @Test
  void rejectsBlankRequiredServiceName() {
    contextRunner
        .withPropertyValues("ayuvahak.platform.service-name=")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(BindValidationException.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PlatformProperties.class)
  static class PropertiesConfiguration {}
}
