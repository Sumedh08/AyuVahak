package org.ayuvahak.platform.authorization.configuration;

import org.ayuvahak.platform.authorization.adapter.persistence.JdbcAuthorizationGrantLookup;
import org.ayuvahak.platform.authorization.application.AuthorizationGrantLookup;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.authorization.domain.PhaseTwoAuthorities;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScopeValidator;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class AuthorizationConfiguration {

  @Bean
  FunctionalAuthorityCatalog functionalAuthorityCatalog() {
    return PhaseTwoAuthorities.catalog();
  }

  @Bean
  ExecutionScopeValidator platformScopeValidator() {
    return (actor, requestedScope) -> requestedScope instanceof RequestedExecutionScope.Platform;
  }

  @Bean
  ExecutionContextFactory executionContextFactory(ExecutionScopeValidator scopeValidator) {
    return new ExecutionContextFactory(scopeValidator);
  }

  @Bean
  @Lazy
  AuthorizationGrantLookup authorizationGrantLookup(JdbcTemplate jdbcTemplate) {
    return new JdbcAuthorizationGrantLookup(jdbcTemplate);
  }

  @Bean
  @Lazy
  AuthorizationService authorizationService(
      AuthorizationGrantLookup grantLookup, FunctionalAuthorityCatalog authorities) {
    return new AuthorizationService(grantLookup, authorities);
  }
}
