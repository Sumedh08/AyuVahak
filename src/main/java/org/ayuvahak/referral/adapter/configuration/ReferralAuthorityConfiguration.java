package org.ayuvahak.referral.adapter.configuration;

import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.referral.application.ReferralGrantTargetScopeValidator;
import org.ayuvahak.referral.domain.ReferralAuthorities;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers Referral-owned functional authorities with the platform authorization catalog. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReferralProperties.class)
public class ReferralAuthorityConfiguration {

  @Bean
  FunctionalAuthority referralCreateAuthority() {
    return ReferralAuthorities.create();
  }

  @Bean
  FunctionalAuthority referralEditAuthority() {
    return ReferralAuthorities.edit();
  }

  @Bean
  FunctionalAuthority referralSubmitAuthority() {
    return ReferralAuthorities.submit();
  }

  @Bean
  FunctionalAuthority referralAmendAuthority() {
    return ReferralAuthorities.amend();
  }

  @Bean
  FunctionalAuthority referralResolveAuthority() {
    return ReferralAuthorities.resolve();
  }

  @Bean
  FunctionalAuthority referralCancelAuthority() {
    return ReferralAuthorities.cancel();
  }

  @Bean
  FunctionalAuthority referralReadAuthority() {
    return ReferralAuthorities.read();
  }

  @Bean
  FunctionalAuthority referralPatientContextDisclosureAuthority() {
    return ReferralAuthorities.patientContextDisclosure();
  }

  @Bean
  FunctionalAuthority referralReportingSnapshotAuthority() {
    return ReferralAuthorities.reportingSnapshot();
  }

  @Bean
  @ConditionalOnBean(DirectoryScopeReferenceLookup.class)
  ReferralGrantTargetScopeValidator referralGrantTargetScopeValidator(
      DirectoryScopeReferenceLookup directoryScopeReferenceLookup) {
    return new ReferralGrantTargetScopeValidator(directoryScopeReferenceLookup);
  }
}
