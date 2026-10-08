package org.ayuvahak.platform.eventing.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OutboxPropertiesTest {

  @Test
  void usesTheApprovedBoundedRetryAndLeaseDefaults() {
    OutboxProperties properties = new OutboxProperties();
    properties.setDeliveredEventRetention(Duration.ofDays(30));

    assertThat(properties.getLeaseDuration()).isEqualTo(Duration.ofSeconds(60));
    assertThat(properties.getLeaseRenewalInterval()).isEqualTo(Duration.ofSeconds(20));
    assertThat(properties.getRetryBaseDelay()).isEqualTo(Duration.ofSeconds(1));
    assertThat(properties.getRetryMaximumDelay()).isEqualTo(Duration.ofMinutes(5));
    assertThat(properties.getDeliveredEventRetention()).isEqualTo(Duration.ofDays(30));
    assertThat(properties.getMaximumAttempts()).isEqualTo(10);
    assertThat(properties.isValid()).isTrue();
  }

  @Test
  void rejectsInvalidLeaseRetryAndPollingBounds() {
    OutboxProperties properties = new OutboxProperties();
    properties.setLeaseRenewalInterval(Duration.ofSeconds(60));
    properties.setRetryBaseDelay(Duration.ofMinutes(6));
    properties.setMaximumAttempts(0);
    properties.setBatchSize(1001);
    properties.setDeliveredEventRetention(Duration.ZERO);

    assertThat(properties.isValid()).isFalse();
  }

  @Test
  void acceptsAnExplicitBoundedOverride() {
    OutboxProperties properties = new OutboxProperties();
    properties.setLeaseDuration(Duration.ofSeconds(90));
    properties.setLeaseRenewalInterval(Duration.ofSeconds(15));
    properties.setRetryBaseDelay(Duration.ofSeconds(2));
    properties.setRetryMaximumDelay(Duration.ofMinutes(2));
    properties.setMaximumAttempts(8);
    properties.setBatchSize(5);
    properties.setDeliveredEventRetention(Duration.ofDays(7));

    assertThat(properties.isValid()).isTrue();
  }

  @Test
  void invalidBoundariesFailConfigurationBinding() {
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
        .withPropertyValues(
            "ayuvahak.eventing.outbox.lease-duration=PT60S",
            "ayuvahak.eventing.outbox.lease-renewal-interval=PT60S")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("outbox timing and batch settings");
            });
  }

  @Test
  void deliveredEventRetentionMustBeExplicitAndPositive() {
    OutboxProperties properties = new OutboxProperties();

    assertThat(properties.isValid()).isFalse();

    properties.setDeliveredEventRetention(Duration.ofHours(24));

    assertThat(properties.isValid()).isTrue();
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(OutboxProperties.class)
  static class PropertiesConfiguration {}
}
