package org.ayuvahak.platform.authorization.adapter.cli;

import java.util.Map;
import org.ayuvahak.platform.AyuVahakApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** Non-web entry point for the one-shot initial access grant command. */
public final class BootstrapGrantApplication {

  private BootstrapGrantApplication() {}

  public static void main(String[] args) {
    SpringApplication application = application();
    try (var ignored = application.run(args)) {
      // The runner completes one bootstrap attempt; closing the context ends the process mode.
    }
  }

  public static SpringApplication application() {
    SpringApplication application = new SpringApplication(AyuVahakApplication.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setDefaultProperties(
        Map.of(
            "ayuvahak.bootstrap.enabled", "true",
            "spring.main.banner-mode", "off",
            "spring.main.log-startup-info", "false"));
    return application;
  }
}
