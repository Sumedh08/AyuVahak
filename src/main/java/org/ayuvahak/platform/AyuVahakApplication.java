package org.ayuvahak.platform;

import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.ayuvahak.platform.configuration.DatabaseProperties;
import org.ayuvahak.platform.configuration.PlatformProperties;
import org.ayuvahak.platform.idempotency.configuration.CommandIdempotencyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
  PlatformProperties.class,
  DatabaseProperties.class,
  AuthenticationProperties.class,
  CommandIdempotencyProperties.class
})
public class AyuVahakApplication {

  public static void main(String[] args) {
    SpringApplication.run(AyuVahakApplication.class, args);
  }
}
