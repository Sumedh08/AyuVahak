package org.ayuvahak.platform;

import org.ayuvahak.platform.configuration.PlatformProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PlatformProperties.class)
public class AyuVahakApplication {

  public static void main(String[] args) {
    SpringApplication.run(AyuVahakApplication.class, args);
  }
}
