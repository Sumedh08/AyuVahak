package org.ayuvahak.platform.exchange.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OperatorSupportExecutorIsolationTest {

  @Test
  void createsAnIndependentBoundedExecutorForOptionalProviderCalls() {
    ExchangeHttpConfiguration configuration = new ExchangeHttpConfiguration();
    try (BoundedPartnerOperationExecutor exchange =
            configuration.boundedPartnerOperationExecutor();
        BoundedPartnerOperationExecutor operatorSupport =
            configuration.operatorSupportOperationExecutor()) {
      assertThat(operatorSupport).isNotSameAs(exchange);
      assertThat(
              exchange.execute(() -> "exchange-remains-available", Duration.ofSeconds(1), () -> {}))
          .isEqualTo("exchange-remains-available");
      assertThat(
              operatorSupport.execute(
                  () -> "provider-is-bounded-separately", Duration.ofSeconds(1), () -> {}))
          .isEqualTo("provider-is-bounded-separately");
    }
  }
}
