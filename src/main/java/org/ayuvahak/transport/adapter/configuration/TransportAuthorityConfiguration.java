package org.ayuvahak.transport.adapter.configuration;

import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.transport.domain.TransportAuthorities;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TransportProperties.class)
class TransportAuthorityConfiguration {

  @Bean
  FunctionalAuthority transportRequestAuthority() {
    return TransportAuthorities.request();
  }

  @Bean
  FunctionalAuthority transportDispatchCoordinationAuthority() {
    return TransportAuthorities.dispatchCoordination();
  }

  @Bean
  FunctionalAuthority transportStatusAuthority() {
    return TransportAuthorities.status();
  }
}
