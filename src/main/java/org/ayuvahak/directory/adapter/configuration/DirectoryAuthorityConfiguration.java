package org.ayuvahak.directory.adapter.configuration;

import org.ayuvahak.directory.domain.DirectoryAuthorities;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers Directory-owned permissions without making Identity & Access depend on Directory. */
@Configuration(proxyBeanMethods = false)
public class DirectoryAuthorityConfiguration {

  @Bean
  FunctionalAuthority directoryReadAuthority() {
    return DirectoryAuthorities.read();
  }

  @Bean
  FunctionalAuthority directoryCurationAuthority() {
    return DirectoryAuthorities.curation();
  }

  @Bean
  FunctionalAuthority directoryVocabularyAdministrationAuthority() {
    return DirectoryAuthorities.vocabularyAdministration();
  }

  @Bean
  FunctionalAuthority directoryCapacityLifecycleOrganizationReadAuthority() {
    return DirectoryAuthorities.capacityLifecycleOrganizationRead();
  }
}
