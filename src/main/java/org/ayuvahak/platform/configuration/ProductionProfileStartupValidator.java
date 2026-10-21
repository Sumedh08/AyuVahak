package org.ayuvahak.platform.configuration;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.springframework.core.env.Environment;

/** Rejects incomplete or unsafe production settings before the application accepts requests. */
final class ProductionProfileStartupValidator {

  private static final Set<String> SAFE_MANAGEMENT_ENDPOINTS = Set.of("health", "prometheus");

  ProductionProfileStartupValidator(
      ProductionProfileProperties properties,
      DeploymentModeProperties deploymentMode,
      AuthenticationProperties authentication,
      Environment environment) {
    List<String> violations = new ArrayList<>();
    validateProfile(properties, environment, violations);
    validateDeploymentMode(deploymentMode, violations);
    validatePublicOrigin(properties.getPublicOrigin(), violations);
    validateAuthentication(authentication, violations);
    validateSecretReference(
        properties.getSecrets().getDatabasePasswordReference(),
        "database password secret reference",
        violations);
    validateSecretReference(
        properties.getSecrets().getTlsCertificateReference(),
        "TLS certificate secret reference",
        violations);
    validateTls(properties, violations);
    validateCors(properties, violations);
    validateManagement(environment, violations);

    if (!violations.isEmpty()) {
      throw new IllegalStateException(
          "Invalid production profile configuration: " + String.join("; ", violations));
    }
  }

  private static void validateDeploymentMode(
      DeploymentModeProperties deploymentMode, List<String> violations) {
    if (deploymentMode.getMode() != DeploymentMode.EMPTY) {
      violations.add("production deployment mode must be EMPTY");
    }
  }

  private static void validateProfile(
      ProductionProfileProperties properties, Environment environment, List<String> violations) {
    boolean singleSite = environment.acceptsProfiles("production-single-site");
    boolean highAvailability = environment.acceptsProfiles("production-ha");
    if (singleSite == highAvailability) {
      violations.add("activate exactly one supported production profile");
      return;
    }
    ProductionProfileProperties.Profile expected =
        singleSite
            ? ProductionProfileProperties.Profile.SINGLE_SITE
            : ProductionProfileProperties.Profile.HIGH_AVAILABILITY;
    if (properties.getProfile() != expected) {
      violations.add("production profile declaration must match the active profile");
    }
  }

  private static void validatePublicOrigin(String value, List<String> violations) {
    if (!isHttpsOrigin(value)) {
      violations.add(
          "public origin must be an absolute HTTPS origin without credentials or a path");
    }
  }

  private static void validateAuthentication(
      AuthenticationProperties authentication, List<String> violations) {
    if (authentication.allowHttp()
        || !isHttpsEndpoint(authentication.issuer())
        || !isHttpsEndpoint(authentication.jwkSetUri())) {
      violations.add(
          "production requires HTTPS identity endpoints and disallows HTTP identity mode");
    }
  }

  private static void validateSecretReference(String value, String label, List<String> violations) {
    try {
      URI reference = new URI(value);
      if (!"secret".equals(reference.getScheme())
          || reference.getHost() == null
          || reference.getHost().isBlank()
          || reference.getUserInfo() != null
          || reference.getPort() != -1
          || reference.getPath() == null
          || reference.getPath().length() < 2
          || reference.getQuery() != null
          || reference.getFragment() != null) {
        violations.add(label + " must use an opaque secret://provider/reference value");
      }
    } catch (IllegalArgumentException | URISyntaxException exception) {
      violations.add(label + " must use an opaque secret://provider/reference value");
    }
  }

  private static void validateTls(ProductionProfileProperties properties, List<String> violations) {
    if (properties.getTls().getTermination()
        != ProductionProfileProperties.TlsTermination.REVERSE_PROXY) {
      violations.add("production TLS termination must be declared as reverse-proxy managed");
    }
  }

  private static void validateCors(
      ProductionProfileProperties properties, List<String> violations) {
    ProductionProfileProperties.Cors cors = properties.getCors();
    List<String> origins = cors.getAllowedOrigins();
    if (!cors.isEnabled() && !origins.isEmpty()) {
      violations.add("CORS origins must be empty when production CORS is disabled");
      return;
    }
    if (cors.isEnabled() && origins.isEmpty()) {
      violations.add("enabled production CORS requires an explicit HTTPS origin allowlist");
      return;
    }
    if (origins.stream().anyMatch(origin -> !isHttpsOrigin(origin))) {
      violations.add("production CORS origins must be explicit HTTPS origins");
    }
  }

  private static void validateManagement(Environment environment, List<String> violations) {
    int applicationPort = environment.getProperty("server.port", Integer.class, 8080);
    Integer managementPort = environment.getProperty("management.server.port", Integer.class);
    String managementAddress = environment.getProperty("management.server.address", "");
    if (managementPort == null
        || managementPort < 1024
        || managementPort > 65535
        || managementPort == applicationPort
        || !Set.of("127.0.0.1", "::1").contains(managementAddress)) {
      violations.add("production management must use a distinct port bound to loopback");
    }

    Set<String> exposed =
        Arrays.stream(
                environment.getProperty("management.endpoints.web.exposure.include", "").split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .collect(java.util.stream.Collectors.toSet());
    if (exposed.isEmpty() || !SAFE_MANAGEMENT_ENDPOINTS.containsAll(exposed)) {
      violations.add("production management may expose only health and read-only prometheus");
    }
    if (!"none".equals(environment.getProperty("management.endpoints.access.default", ""))
        || !"read-only".equals(environment.getProperty("management.endpoint.health.access", ""))
        || !"never".equals(environment.getProperty("management.endpoint.health.show-details", ""))
        || !"never"
            .equals(environment.getProperty("management.endpoint.health.show-components", ""))
        || !environment.getProperty(
            "management.endpoint.health.probes.enabled", Boolean.class, false)
        || (exposed.contains("prometheus")
            && !"read-only"
                .equals(environment.getProperty("management.endpoint.prometheus.access", "")))) {
      violations.add("production management access and health disclosure must remain restricted");
    }
  }

  private static boolean isHttpsOrigin(String value) {
    try {
      URI uri = new URI(value);
      return "https".equalsIgnoreCase(uri.getScheme())
          && uri.getHost() != null
          && uri.getUserInfo() == null
          && (uri.getPort() == -1 || (uri.getPort() >= 1 && uri.getPort() <= 65535))
          && (uri.getPath() == null || uri.getPath().isEmpty())
          && uri.getQuery() == null
          && uri.getFragment() == null
          && !value.contains("*");
    } catch (IllegalArgumentException | URISyntaxException exception) {
      return false;
    }
  }

  private static boolean isHttpsEndpoint(String value) {
    try {
      URI uri = new URI(value);
      return "https".equalsIgnoreCase(uri.getScheme())
          && uri.getHost() != null
          && uri.getUserInfo() == null
          && uri.getFragment() == null;
    } catch (IllegalArgumentException | URISyntaxException exception) {
      return false;
    }
  }
}
