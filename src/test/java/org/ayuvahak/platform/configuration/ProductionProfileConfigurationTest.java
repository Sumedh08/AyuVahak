package org.ayuvahak.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.ayuvahak.platform.observability.ServiceLevelIndicator;
import org.ayuvahak.platform.observability.ServiceLevelPlan;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class ProductionProfileConfigurationTest {

  private final ApplicationContextRunner productionRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ConfigurationPropertiesAutoConfiguration.class,
                  ValidationAutoConfiguration.class))
          .withUserConfiguration(ProductionProfileConfiguration.class)
          .withPropertyValues(
              "spring.profiles.active=production-single-site",
              "ayuvahak.deployment.mode=EMPTY",
              "server.port=8080",
              "management.server.port=9091",
              "management.server.address=127.0.0.1",
              "management.endpoints.access.default=none",
              "management.endpoints.web.exposure.include=health,prometheus",
              "management.endpoint.health.access=read-only",
              "management.endpoint.health.show-details=never",
              "management.endpoint.health.show-components=never",
              "management.endpoint.health.probes.enabled=true",
              "management.endpoint.prometheus.access=read-only",
              "ayuvahak.authentication.issuer=https://identity.example.test/issuer",
              "ayuvahak.authentication.jwk-set-uri=https://identity.example.test/jwks",
              "ayuvahak.authentication.audience=ayuvahak-api",
              "ayuvahak.authentication.service-client-ids=synthetic-worker",
              "ayuvahak.authentication.allow-http=false",
              "ayuvahak.production.profile=SINGLE_SITE",
              "ayuvahak.production.public-origin=https://coordination.example.test",
              "ayuvahak.production.tls.termination=REVERSE_PROXY",
              "ayuvahak.production.secrets.tls-certificate-reference=secret://deployment/ingress/tls",
              "ayuvahak.production.secrets.database-password-reference=secret://deployment/database/password",
              "ayuvahak.production.cors.enabled=true",
              "ayuvahak.production.cors.allowed-origins[0]=https://operator.example.test",
              "ayuvahak.production.limits.principal-requests-per-minute=1200",
              "ayuvahak.production.limits.scope-requests-per-minute=3000",
              "ayuvahak.production.limits.partner-requests-per-minute=1200",
              "ayuvahak.production.limits.anonymous-requests-per-minute=30",
              "ayuvahak.production.limits.authenticated-read-requests-per-minute=600",
              "ayuvahak.production.limits.sensitive-write-requests-per-minute=60",
              "ayuvahak.production.limits.admin-requests-per-minute=30",
              "ayuvahak.production.limits.sse-connections-per-principal=3",
              "ayuvahak.production.limits.sse-connections-total=1000",
              "ayuvahak.production.limits.sse-subscriptions-per-minute=120",
              "ayuvahak.production.limits.federation-requests-per-minute=120",
              "ayuvahak.production.limits.exchange-requests-per-minute=120",
              "ayuvahak.production.retention.schedule-id=synthetic-schedule-2026-01",
              "ayuvahak.production.retention.reviewed-at=2026-10-20")
          .withPropertyValues(completeRetentionRegisterProperties())
          .withInitializer(ProductionProfileConfigurationTest::applySyntheticServiceLevelPlan);

  @Test
  void acceptsCompletePortableProfileAndAllowsOnlyConfiguredCorsOrigin() {
    productionRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(ProductionProfileProperties.class).getProfile())
              .isEqualTo(ProductionProfileProperties.Profile.SINGLE_SITE);
          assertThat(context.getBean(DeploymentModeProperties.class).getMode())
              .isEqualTo(DeploymentMode.EMPTY);
          assertThat(context.getBean(ServiceLevelPlan.class).objectives()).hasSize(1);

          CorsConfigurationSource source = context.getBean(CorsConfigurationSource.class);
          MockHttpServletRequest allowedRequest =
              new MockHttpServletRequest("GET", "/api/v1/referrals");
          allowedRequest.addHeader("Origin", "https://operator.example.test");
          CorsConfiguration allowed = source.getCorsConfiguration(allowedRequest);
          assertThat(allowed).isNotNull();
          assertThat(allowed.checkOrigin("https://operator.example.test"))
              .isEqualTo("https://operator.example.test");
          assertThat(allowed.getAllowCredentials()).isFalse();

          MockHttpServletRequest deniedRequest =
              new MockHttpServletRequest("GET", "/api/v1/referrals");
          deniedRequest.addHeader("Origin", "https://untrusted.example.test");
          assertThat(
                  source
                      .getCorsConfiguration(deniedRequest)
                      .checkOrigin("https://untrusted.example.test"))
              .isNull();
        });
  }

  @Test
  void rejectsSyntheticDemoModeForProductionProfiles() {
    productionRunner
        .withPropertyValues("ayuvahak.deployment.mode=SYNTHETIC_DEMO")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure().toString())
                  .contains("production deployment mode must be EMPTY");
            });
  }

  @Test
  void rejectsMissingRequiredSecretReferenceWithoutEchoingCredentialValues() {
    productionRunner
        .withPropertyValues("ayuvahak.production.secrets.database-password-reference=")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure().toString())
                  .doesNotContain("synthetic-secret-value");
            });
  }

  @Test
  void rejectsHttpIdentityEndpointsEvenWhenOtherProfileSettingsAreValid() {
    productionRunner
        .withPropertyValues(
            "ayuvahak.authentication.issuer=http://identity.example.test/issuer",
            "ayuvahak.authentication.jwk-set-uri=http://identity.example.test/jwks",
            "ayuvahak.authentication.allow-http=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure().toString())
                  .contains("production requires HTTPS identity endpoints");
            });
  }

  @Test
  void rejectsPublicOriginThatContainsAPath() {
    productionRunner
        .withPropertyValues(
            "ayuvahak.production.public-origin=https://coordination.example.test/path")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure().toString())
                  .contains("public origin must be an absolute HTTPS origin");
            });
  }

  @Test
  void rejectsManagementExposureOnApplicationPortOrUntrustedInterface() {
    productionRunner
        .withPropertyValues("management.server.port=8080", "management.server.address=0.0.0.0")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure().toString())
                  .contains("production management must use a distinct port bound to loopback");
            });
  }

  @Test
  void rejectsWildcardCors() {
    productionRunner
        .withPropertyValues("ayuvahak.production.cors.allowed-origins[0]=*")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure().toString())
                  .contains("production CORS origins must be explicit HTTPS origins");
            });
  }

  @Test
  void rejectsMissingRetentionScheduleRegistration() {
    productionRunner
        .withPropertyValues("ayuvahak.production.retention.schedule-id=")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure().toString())
                  .contains("Could not bind properties to 'ProductionProfileProperties'");
            });
  }

  @Test
  void rejectsAProductionRegisterWithAnIncompleteOwnerCategory() {
    productionRunner
        .withPropertyValues(
            "ayuvahak.production.retention.register[PHASE_12_ANALYTICS].verification-method=")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure().toString())
                  .contains("Could not bind properties to 'ProductionProfileProperties'");
            });
  }

  @Test
  void loadsTheNamedSingleSiteProfileWithLoopbackOnlyManagementDefaults() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
        .withUserConfiguration(ProductionProfileConfiguration.class)
        .withInitializer(
            context -> {
              new ConfigDataApplicationContextInitializer().initialize(context);
              applySyntheticServiceLevelPlan(context);
            })
        .withPropertyValues(
            "spring.profiles.active=production-single-site",
            "ayuvahak.authentication.issuer=https://identity.example.test/issuer",
            "ayuvahak.authentication.jwk-set-uri=https://identity.example.test/jwks",
            "ayuvahak.authentication.audience=ayuvahak-api",
            "ayuvahak.authentication.service-client-ids=synthetic-worker",
            "ayuvahak.production.public-origin=https://coordination.example.test",
            "ayuvahak.production.secrets.database-password-reference=secret://deployment/database/password",
            "ayuvahak.production.secrets.tls-certificate-reference=secret://deployment/ingress/tls",
            "ayuvahak.production.limits.principal-requests-per-minute=1200",
            "ayuvahak.production.limits.scope-requests-per-minute=3000",
            "ayuvahak.production.limits.partner-requests-per-minute=1200",
            "ayuvahak.production.limits.anonymous-requests-per-minute=30",
            "ayuvahak.production.limits.authenticated-read-requests-per-minute=600",
            "ayuvahak.production.limits.sensitive-write-requests-per-minute=60",
            "ayuvahak.production.limits.admin-requests-per-minute=30",
            "ayuvahak.production.limits.sse-connections-per-principal=3",
            "ayuvahak.production.limits.sse-connections-total=1000",
            "ayuvahak.production.limits.sse-subscriptions-per-minute=120",
            "ayuvahak.production.limits.federation-requests-per-minute=120",
            "ayuvahak.production.limits.exchange-requests-per-minute=120",
            "ayuvahak.production.retention.schedule-id=synthetic-schedule-2026-01",
            "ayuvahak.production.retention.reviewed-at=2026-10-20")
        .withPropertyValues(completeRetentionRegisterProperties())
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getEnvironment().getProperty("management.server.address"))
                  .isEqualTo("127.0.0.1");
              assertThat(context.getEnvironment().getProperty("management.server.port"))
                  .isEqualTo("9091");
              assertThat(
                      context
                          .getEnvironment()
                          .getProperty("management.endpoints.web.exposure.include"))
                  .isEqualTo("health,prometheus");
            });
  }

  private static void applySyntheticServiceLevelPlan(ConfigurableApplicationContext context) {
    Map<String, String> values = new LinkedHashMap<>();
    String prefix = "ayuvahak.production.sli.";
    values.put(prefix + "objectives[0].indicator", "API_AVAILABILITY");
    values.put(prefix + "objectives[0].target", "0.995");
    values.put(prefix + "objectives[0].window", "P30D");
    values.put(prefix + "objectives[0].service-scope", "API");
    values.put(
        prefix + "objectives[0].failure-definition",
        "Eligible synthetic API requests returning 5xx.");
    values.put(prefix + "objectives[0].alert-threshold", "0.98");
    values.put(prefix + "objectives[0].accountable-owner", "Synthetic operations team");
    values.put(prefix + "objectives[0].response", "Follow the synthetic API incident runbook.");
    values.put(
        prefix + "objectives[0].exclusions[0]",
        "Synthetic client cancellation before request admission.");
    for (ServiceLevelIndicator indicator : ServiceLevelIndicator.values()) {
      if (indicator != ServiceLevelIndicator.API_AVAILABILITY) {
        values.put(
            prefix + "omitted-rationales." + indicator.name(),
            "Synthetic deployment has not selected this indicator as an objective.");
      }
    }
    TestPropertyValues.of(values).applyTo(context);
  }

  private static String[] completeRetentionRegisterProperties() {
    List<String> properties = new ArrayList<>();
    properties.add("ayuvahak.production.retention.accountable-authority=SYN-RECORDS-AUTHORITY");
    for (ProductionProfileProperties.RetentionCategory category :
        ProductionProfileProperties.RetentionCategory.values()) {
      String prefix = "ayuvahak.production.retention.register[" + category.name() + "]";
      properties.add(prefix + ".owner=" + category.owner().name());
      properties.add(prefix + ".purpose=Synthetic deployment purpose");
      properties.add(prefix + ".data-class=SYNTHETIC_OPERATIONAL");
      properties.add(prefix + ".access-policy=OWNER_RESTRICTED");
      properties.add(prefix + ".lifecycle-trigger=OWNER_DEFINED_CLOSED_OR_DELIVERED");
      properties.add(prefix + ".retention-period=PT2160H");
      properties.add(prefix + ".governance-basis=SYN-APPROVED-POLICY-001");
      properties.add(prefix + ".hold-review-authority=SYN-REVIEWER-001");
      properties.add(prefix + ".expiry-action=OWNER_REVIEW");
      properties.add(prefix + ".evidence-dependencies=UNRESOLVED_WORK_AND_AUDIT");
      properties.add(prefix + ".backup-propagation=REAPPLY_BEFORE_READINESS");
      properties.add(prefix + ".verification-method=SYNTHETIC_OWNER_ACCEPTANCE");
      properties.add(prefix + ".block-while-held=true");
      properties.add(prefix + ".block-while-unresolved=true");
      properties.add(prefix + ".reapply-completed-actions-before-serving=true");
    }
    return properties.toArray(String[]::new);
  }
}
