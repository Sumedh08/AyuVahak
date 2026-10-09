package org.ayuvahak.capacity.configuration;

import org.ayuvahak.capacity.domain.CapacityAuthorities;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CapacityConfiguration {

  @Bean
  FunctionalAuthority capacityPolicyAdministrationAuthority() {
    return CapacityAuthorities.policyAdministration();
  }
}
