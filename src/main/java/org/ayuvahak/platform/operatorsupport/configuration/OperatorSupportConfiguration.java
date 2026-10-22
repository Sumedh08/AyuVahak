package org.ayuvahak.platform.operatorsupport.configuration;

import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.configuration.OperatorSupportProperties;
import org.ayuvahak.platform.exchange.application.PartnerSecretProvider;
import org.ayuvahak.platform.operatorsupport.application.OperatorSupportAuthorities;
import org.ayuvahak.platform.operatorsupport.application.OperatorSupportCorpus;
import org.ayuvahak.platform.operatorsupport.application.DeterministicLocalOperatorSupportProvider;
import org.ayuvahak.platform.operatorsupport.application.OpenAiCompatibleOperatorSupportProvider;
import org.ayuvahak.platform.operatorsupport.application.OperatorSupportHttpTransport;
import org.ayuvahak.platform.operatorsupport.application.OperatorSupportProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

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

  @Bean
  DeterministicLocalOperatorSupportProvider deterministicLocalOperatorSupportProvider() {
    return new DeterministicLocalOperatorSupportProvider();
  }

  @Bean
  OperatorSupportProvider operatorSupportProvider(
      OperatorSupportProperties properties,
      DeterministicLocalOperatorSupportProvider deterministic,
      org.springframework.beans.factory.ObjectProvider<OperatorSupportHttpTransport> transport,
      PartnerSecretProvider secrets,
      ObjectMapper mapper) {
    if (!properties.isEnabled()
        || properties.getProvider() == OperatorSupportProperties.Provider.DETERMINISTIC_LOCAL) {
      return deterministic;
    }
    properties.validate();
    return new OpenAiCompatibleOperatorSupportProvider(
        properties, transport.getObject(), secrets, mapper);
  }
}
