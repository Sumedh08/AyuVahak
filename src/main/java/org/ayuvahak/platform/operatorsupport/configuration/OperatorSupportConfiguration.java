package org.ayuvahak.platform.operatorsupport.configuration;

import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.configuration.OperatorSupportProperties;
import org.ayuvahak.platform.operatorsupport.application.OperatorSupportAuthorities;
import org.ayuvahak.platform.operatorsupport.application.OperatorSupportCorpus;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers only the narrow platform grant and the explicitly approved support corpus. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperatorSupportProperties.class)
public class OperatorSupportConfiguration {

  @Bean
  FunctionalAuthority operatorSupportReadAuthority() {
    return OperatorSupportAuthorities.read();
  }

  @Bean
  OperatorSupportCorpus approvedOperatorSupportCorpus() {
    return OperatorSupportCorpus.approved();
  }
}
