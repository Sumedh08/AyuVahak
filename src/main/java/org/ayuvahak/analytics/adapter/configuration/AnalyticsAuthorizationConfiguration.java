package org.ayuvahak.analytics.adapter.configuration;

import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AnalyticsAuthorizationConfiguration {

  @Bean
  FunctionalAuthority analyticsAggregateComputationAuthority() {
    return AnalyticsAuthorities.aggregateComputation();
  }
}
