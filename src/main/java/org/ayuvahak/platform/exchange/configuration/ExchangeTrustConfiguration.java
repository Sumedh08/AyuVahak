package org.ayuvahak.platform.exchange.configuration;

import java.util.Set;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers platform-scoped authority for audited partner trust administration. */
@Configuration(proxyBeanMethods = false)
public class ExchangeTrustConfiguration {

  @Bean
  FunctionalAuthority exchangeTrustAdministrationAuthority() {
    return new FunctionalAuthority(
        "ExchangeTrustAdministration", Set.of(ExecutionScope.Kind.PLATFORM));
  }
}
