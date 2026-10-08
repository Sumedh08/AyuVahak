package org.ayuvahak.platform.idempotency.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class CommandIdempotencyPropertiesTest {

  @Test
  void defaultsReceiptWaitToFiveSeconds() {
    assertThat(new CommandIdempotencyProperties().getReceiptLockWait())
        .isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void acceptsAValidOverrideAndRejectsUnboundedValues() {
    CommandIdempotencyProperties properties = new CommandIdempotencyProperties();
    properties.setReceiptLockWait(Duration.ofSeconds(3));
    assertThat(properties.isValid()).isTrue();

    properties.setReceiptLockWait(Duration.ofSeconds(31));
    assertThat(properties.isValid()).isFalse();
    properties.setReceiptLockWait(Duration.ZERO);
    assertThat(properties.isValid()).isFalse();
  }

  @Test
  void invalidBoundariesFailConfigurationBinding() {
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
        .withPropertyValues("ayuvahak.idempotency.receipt-lock-wait=PT31S")
        .run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(CommandIdempotencyProperties.class)
  static class PropertiesConfiguration {}
}
