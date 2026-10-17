package org.ayuvahak.matching.adapter.configuration;

import org.ayuvahak.matching.application.MatchingOwnerReadContexts;
import org.ayuvahak.matching.application.MatchingPolicyCatalog;
import org.ayuvahak.matching.domain.MatchingAuthorities;
import org.ayuvahak.matching.domain.RemoteCapacityEvidenceProjector;
import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  MatchingProperties.class,
  MatchingPolicyProperties.class,
  MatchingFederationPeerProperties.class
})
class MatchingAuthorityConfiguration {

  @Bean
  FunctionalAuthority recommendationRequestAuthority() {
    return MatchingAuthorities.requestRecommendation();
  }

  @Bean
  FunctionalAuthority recommendationReadAuthority() {
    return MatchingAuthorities.readRecommendation();
  }

  @Bean
  MatchingOwnerReadContexts matchingOwnerReadContexts(
      MatchingProperties properties,
      AuthenticationProperties authentication,
      ExecutionContextFactory contextFactory) {
    return new MatchingOwnerReadContexts(
        ActorReference.service(
            new IssuerId(authentication.issuer()), properties.ownerReadClientId()),
        contextFactory);
  }

  @Bean
  MatchingPolicyCatalog matchingPolicyCatalog(MatchingPolicyProperties properties) {
    return properties.catalog();
  }

  @Bean
  RemoteCapacityEvidenceProjector remoteCapacityEvidenceProjector() {
    return new RemoteCapacityEvidenceProjector();
  }
}
