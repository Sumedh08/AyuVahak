package org.ayuvahak.platform.authorization.configuration;

import java.util.ArrayList;
import java.util.List;
import org.ayuvahak.platform.authorization.adapter.persistence.JdbcAuthorizationGrantLookup;
import org.ayuvahak.platform.authorization.adapter.persistence.JdbcSubjectCapabilityLookup;
import org.ayuvahak.platform.authorization.application.AuthorizationGrantLookup;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.application.SubjectCapabilityLookup;
import org.ayuvahak.platform.authorization.application.SubjectCapabilityQuery;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.authorization.domain.PhaseTwoAuthorities;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScopeOwnerValidator;
import org.ayuvahak.platform.executioncontext.ExecutionScopeValidator;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class AuthorizationConfiguration {

  @Bean
  FunctionalAuthorityCatalog functionalAuthorityCatalog(
      ObjectProvider<org.ayuvahak.platform.authorization.domain.FunctionalAuthority>
          registeredAuthorities) {
    List<org.ayuvahak.platform.authorization.domain.FunctionalAuthority> authorities =
        new ArrayList<>(PhaseTwoAuthorities.catalog().all());
    registeredAuthorities.orderedStream().forEach(authorities::add);
    return new FunctionalAuthorityCatalog(authorities);
  }

  @Bean
  ExecutionScopeValidator platformScopeValidator(
      ObjectProvider<ExecutionScopeOwnerValidator> scopeOwners) {
    return (actor, requestedScope) -> {
      if (requestedScope instanceof RequestedExecutionScope.Platform) {
        return true;
      }
      var owners =
          scopeOwners.orderedStream().filter(owner -> owner.supports(requestedScope)).toList();
      return owners.size() == 1 && owners.getFirst().isValid(requestedScope);
    };
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

  @Bean
  @Lazy
  SubjectCapabilityLookup subjectCapabilityLookup(JdbcTemplate jdbcTemplate) {
    return new JdbcSubjectCapabilityLookup(jdbcTemplate);
  }

  @Bean
  @Lazy
  SubjectCapabilityQuery subjectCapabilityQuery(
      SubjectCapabilityLookup lookup, FunctionalAuthorityCatalog authorities) {
    return new SubjectCapabilityQuery(lookup, authorities);
  }
}
