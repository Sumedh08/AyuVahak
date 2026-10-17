package org.ayuvahak.platform.federation.configuration;

import java.time.Instant;
import org.ayuvahak.platform.federation.application.DeploymentIdentityRegistrationService;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the stable deployment identity before the application starts serving requests. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FederationIdentityProperties.class)
public class FederationIdentityConfiguration {

  @Bean
  ApplicationRunner registerFederationDeploymentIdentity(
      FederationIdentityProperties properties, DeploymentIdentityRegistrationService registration) {
    return arguments -> {
      String configuredId = properties.getDeploymentId();
      String installationBinding = properties.getInstallationBinding();
      boolean idConfigured = configuredId != null && !configuredId.isBlank();
      boolean bindingConfigured = installationBinding != null && !installationBinding.isBlank();
      if (!idConfigured && !bindingConfigured) {
        return;
      }
      if (!idConfigured || !bindingConfigured) {
        throw new IllegalStateException(
            "Federation identity requires both deployment ID and installation binding.");
      }
      registration.register(new DeploymentId(configuredId), installationBinding, Instant.now());
    };
  }
}
