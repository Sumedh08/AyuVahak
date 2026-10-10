package org.ayuvahak.transfer.adapter.configuration;

import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TransferProperties.class)
class TransferAuthorityConfiguration {

  @Bean
  FunctionalAuthority transferRequestAuthority() {
    return TransferAuthorities.request();
  }

  @Bean
  FunctionalAuthority transferRequestExpiryAuthority() {
    return TransferAuthorities.requestExpiry();
  }

  @Bean
  FunctionalAuthority clinicalSuitabilityAuthority() {
    return TransferAuthorities.clinicalSuitability();
  }

  @Bean
  FunctionalAuthority operationalReadinessAuthority() {
    return TransferAuthorities.operationalReadiness();
  }

  @Bean
  FunctionalAuthority transferAcceptanceAuthority() {
    return TransferAuthorities.acceptance();
  }

  @Bean
  FunctionalAuthority transferReservationReconciliationAuthority() {
    return TransferAuthorities.reservationReconciliation();
  }

  @Bean
  FunctionalAuthority transferProgressAuthority() {
    return TransferAuthorities.progress();
  }

  @Bean
  FunctionalAuthority transferHandoverAuthority() {
    return TransferAuthorities.handover();
  }

  @Bean
  FunctionalAuthority transferCompletionAuthority() {
    return TransferAuthorities.completion();
  }
}
