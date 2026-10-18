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

  @Bean
  FunctionalAuthority analyticsLateAggregateRevisionAuthority() {
    return AnalyticsAuthorities.lateAggregateRevision();
  }

  @Bean
  FunctionalAuthority analyticsAggregateSnapshotFinalizationAuthority() {
    return AnalyticsAuthorities.aggregateSnapshotFinalization();
  }

  @Bean
  FunctionalAuthority analyticsAggregateSnapshotCorrectionAuthority() {
    return AnalyticsAuthorities.aggregateSnapshotCorrection();
  }

  @Bean
  FunctionalAuthority analyticsDisclosurePolicyAdministrationAuthority() {
    return AnalyticsAuthorities.disclosurePolicyAdministration();
  }

  @Bean
  FunctionalAuthority analyticsDisclosureReleaseAuthority() {
    return AnalyticsAuthorities.disclosureRelease();
  }

  @Bean
  FunctionalAuthority analyticsDisclosureReservationLifecycleAuthority() {
    return AnalyticsAuthorities.disclosureReservationLifecycle();
  }

  @Bean
  FunctionalAuthority analyticsDhis2MappingAdministrationAuthority() {
    return AnalyticsAuthorities.dhis2MappingAdministration();
  }

  @Bean
  FunctionalAuthority analyticsDhis2ExportAuthority() {
    return AnalyticsAuthorities.dhis2Export();
  }
}
