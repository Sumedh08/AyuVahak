package org.ayuvahak.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.ayuvahak.platform.AyuVahakApplication;
import org.junit.jupiter.api.Test;
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
            "ayuvahak.matching.owner-read-client-id=ayuvahak-matching-owner-read",
            "ayuvahak.eventing.outbox.delivered-event-retention=PT24H",
            "ayuvahak.database.url=",
            "ayuvahak.database.username=",
            "ayuvahak.database.password=")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage("Failed to determine a suitable driver class");
            });
  }
}
