package org.ayuvahak.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.ayuvahak.platform.AyuVahakApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DatabaseConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(AyuVahakApplication.class);

  @Test
  void canonicalApplicationRejectsMissingDatabaseSettingsAtStartup() {
    contextRunner
        .withPropertyValues(
            "ayuvahak.platform.service-name=AyuVahak",
            "ayuvahak.authentication.issuer=https://identity.example.test/realms/test",
            "ayuvahak.authentication.jwk-set-uri=http://127.0.0.1:9/jwks",
            "ayuvahak.authentication.audience=ayuvahak-api",
            "ayuvahak.authentication.service-client-ids=synthetic-worker",
            "ayuvahak.authentication.allow-http=true",
            "ayuvahak.database.url=",
            "ayuvahak.database.username=",
            "ayuvahak.database.password=")
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
}
