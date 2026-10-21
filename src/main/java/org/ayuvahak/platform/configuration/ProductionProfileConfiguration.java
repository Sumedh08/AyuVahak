package org.ayuvahak.platform.configuration;

import java.util.List;
import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.ayuvahak.platform.observability.ServiceLevelPlan;
import org.ayuvahak.platform.observability.ServiceLevelPlanProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Production-only configuration checks and the explicitly allowlisted browser-origin boundary. */
@Configuration(proxyBeanMethods = false)
@Profile({"production-single-site", "production-ha"})
@EnableConfigurationProperties({
  ProductionProfileProperties.class,
  DeploymentModeProperties.class,
  ProductionMigrationProperties.class,
  AuthenticationProperties.class,
  ServiceLevelPlanProperties.class
})
public class ProductionProfileConfiguration {

  @Bean
  ProductionProfileStartupValidator productionProfileStartupValidator(
      ProductionProfileProperties properties,
      DeploymentModeProperties deploymentMode,
      AuthenticationProperties authentication,
      Environment environment) {
    return new ProductionProfileStartupValidator(
        properties, deploymentMode, authentication, environment);
  }

  @Bean
  ServiceLevelPlan productionServiceLevelPlan(ServiceLevelPlanProperties properties) {
    return properties.toPlan();
  }

  @Bean
  @ConditionalOnBean({JdbcTemplate.class, PlatformTransactionManager.class})
  RequestBudgetStore productionRequestBudgetStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    return new JdbcRequestBudgetStore(jdbc, transactionManager);
  }

  @Bean
  CorsConfigurationSource productionCorsConfigurationSource(
      ProductionProfileProperties properties) {
    CorsConfiguration cors = new CorsConfiguration();
    cors.setAllowedOrigins(
        properties.getCors().isEnabled() ? properties.getCors().getAllowedOrigins() : List.of());
    cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(
        List.of("Authorization", "Content-Type", "Idempotency-Key", "If-Match", "If-None-Match"));
    cors.setAllowCredentials(false);
    cors.setMaxAge(600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cors);
    return source;
  }

  @Bean
  FlywayMigrationStrategy productionFlywayMigrationStrategy(
      ProductionMigrationProperties properties) {
    return new ProductionFlywayMigrationStrategy(properties);
  }
}
