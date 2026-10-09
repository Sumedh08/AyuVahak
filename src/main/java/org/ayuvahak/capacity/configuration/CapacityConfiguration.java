package org.ayuvahak.capacity.configuration;

import org.ayuvahak.capacity.application.CapacityReportingGrantTargetScopeValidator;
import org.ayuvahak.capacity.domain.CapacityAuthorities;
import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CapacityConfiguration {

  @Bean
  FunctionalAuthority capacityPolicyAdministrationAuthority() {
    return CapacityAuthorities.policyAdministration();
  }

  @Bean
  FunctionalAuthority capacityReportingAuthority() {
    return CapacityAuthorities.reporting();
  }

  @Bean
  CapacityReportingGrantTargetScopeValidator capacityReportingGrantTargetScopeValidator(
      DirectoryScopeReferenceLookup directoryScopeReferenceLookup) {
    return new CapacityReportingGrantTargetScopeValidator(directoryScopeReferenceLookup);
  }
}
