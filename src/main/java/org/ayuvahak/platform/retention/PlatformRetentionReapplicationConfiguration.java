package org.ayuvahak.platform.retention;

import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetentionReapplicationProperties.class)
public class PlatformRetentionReapplicationConfiguration {

  @Bean
  FunctionalAuthority platformRetentionReapplicationAuthority() {
    return PlatformRetentionAuthorities.reapplication();
  }
}
