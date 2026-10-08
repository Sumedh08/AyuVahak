package org.ayuvahak.directory.adapter.configuration;

import org.ayuvahak.directory.application.DirectoryGrantTargetScopeValidator;
import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Activates Directory grant-target validation when its owning persistence adapter is available. */
@Configuration(proxyBeanMethods = false)
public class DirectoryGrantScopeConfiguration {

  @Bean
  @ConditionalOnBean(DirectoryScopeReferenceLookup.class)
  @ConditionalOnMissingBean(DirectoryGrantTargetScopeValidator.class)
  DirectoryGrantTargetScopeValidator directoryGrantTargetScopeValidator(
      DirectoryScopeReferenceLookup directoryScopeReferenceLookup) {
    return new DirectoryGrantTargetScopeValidator(directoryScopeReferenceLookup);
  }
}
