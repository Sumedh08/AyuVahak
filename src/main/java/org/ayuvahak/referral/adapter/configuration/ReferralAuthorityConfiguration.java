package org.ayuvahak.referral.adapter.configuration;

import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.referral.domain.ReferralAuthorities;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers Referral-owned functional authorities with the platform authorization catalog. */
@Configuration(proxyBeanMethods = false)
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
}
