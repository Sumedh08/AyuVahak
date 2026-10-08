package org.ayuvahak.platform.authorization.configuration;

import org.ayuvahak.platform.authorization.adapter.persistence.JdbcAuthorizationGrantLookup;
import org.ayuvahak.platform.authorization.application.AuthorizationGrantLookup;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.authorization.domain.PhaseTwoAuthorities;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class AuthorizationConfiguration {

  @Bean
  FunctionalAuthorityCatalog functionalAuthorityCatalog() {
    return PhaseTwoAuthorities.catalog();
  }

  @Bean
  AuthorizationGrantLookup authorizationGrantLookup(JdbcTemplate jdbcTemplate) {
    return new JdbcAuthorizationGrantLookup(jdbcTemplate);
  }

  @Bean
  AuthorizationService authorizationService(
      AuthorizationGrantLookup grantLookup, FunctionalAuthorityCatalog authorities) {
    return new AuthorizationService(grantLookup, authorities);
  }
}
