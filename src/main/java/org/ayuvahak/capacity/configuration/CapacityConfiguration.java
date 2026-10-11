package org.ayuvahak.capacity.configuration;

import org.ayuvahak.capacity.application.CapacityReportingGrantTargetScopeValidator;
import org.ayuvahak.capacity.application.CapacityReservationLifecycleProperties;
import org.ayuvahak.capacity.domain.CapacityAuthorities;
import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CapacityReservationLifecycleProperties.class)
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
  FunctionalAuthority capacityEvidenceReviewAuthority() {
    return CapacityAuthorities.evidenceReview();
  }

  @Bean
  FunctionalAuthority capacityReservationAuthority() {
    return CapacityAuthorities.reservation();
  }

  @Bean
  CapacityReportingGrantTargetScopeValidator capacityReportingGrantTargetScopeValidator(
      DirectoryScopeReferenceLookup directoryScopeReferenceLookup) {
    return new CapacityReportingGrantTargetScopeValidator(directoryScopeReferenceLookup);
  }
}
