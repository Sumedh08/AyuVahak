package org.ayuvahak.platform;

import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.ayuvahak.platform.configuration.DatabaseProperties;
import org.ayuvahak.platform.configuration.DeploymentModeProperties;
import org.ayuvahak.platform.configuration.PlatformProperties;
import org.ayuvahak.platform.idempotency.configuration.CommandIdempotencyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication(
    scanBasePackages = {
      "org.ayuvahak.platform",
      "org.ayuvahak.directory",
      "org.ayuvahak.capacity",
      "org.ayuvahak.referral",
      "org.ayuvahak.matching",
      "org.ayuvahak.transfer",
      "org.ayuvahak.transport",
      "org.ayuvahak.analytics"
    })
@EnableConfigurationProperties({
  PlatformProperties.class,
  DatabaseProperties.class,
  DeploymentModeProperties.class,
  AuthenticationProperties.class,
  CommandIdempotencyProperties.class
})
public class AyuVahakApplication {

  public static void main(String[] args) {
    ConfigurableApplicationContext context =
        SpringApplication.run(AyuVahakApplication.class, args);
    closeBootstrapContextIfRequested(context);
  }

  static void closeBootstrapContextIfRequested(ConfigurableApplicationContext context) {
    boolean oneShotBootstrapEnabled =
        context.getEnvironment().getProperty("ayuvahak.bootstrap.enabled", Boolean.class, false);
    if (oneShotBootstrapEnabled) {
      context.close();
    }
  }
}
