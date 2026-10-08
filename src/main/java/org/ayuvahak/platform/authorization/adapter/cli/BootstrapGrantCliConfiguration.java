package org.ayuvahak.platform.authorization.adapter.cli;

import java.util.Objects;
import org.ayuvahak.platform.authorization.application.BootstrapGrantProperties;
import org.ayuvahak.platform.authorization.application.BootstrapGrantService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ayuvahak.bootstrap.enabled", havingValue = "true")
@EnableConfigurationProperties(BootstrapGrantProperties.class)
public class BootstrapGrantCliConfiguration {

  @Bean
  ApplicationRunner oneShotGrantBootstrap(
      BootstrapGrantProperties properties, BootstrapGrantService service) {
    Objects.requireNonNull(properties, "properties");
    Objects.requireNonNull(service, "service");
    return arguments ->
        service.provisionFirstGrant(properties, System.getProperty("user.name", "unavailable"));
  }
}
