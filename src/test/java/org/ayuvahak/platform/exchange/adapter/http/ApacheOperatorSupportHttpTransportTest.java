package org.ayuvahak.platform.exchange.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ApacheOperatorSupportHttpTransportTest {

  @Test
  void rejectsPrivateDnsAnswersBeforeOpeningAConnection() throws Exception {
    AtomicInteger lookups = new AtomicInteger();
    try (BoundedPartnerOperationExecutor operations = new BoundedPartnerOperationExecutor(1, 1)) {
      ApacheOperatorSupportHttpTransport transport =
          new ApacheOperatorSupportHttpTransport(
              new EgressAddressPolicy(Set.of(443), Map.of()),
              (hostname, timeout) -> {
                assertThat(hostname).isEqualTo("provider.example");
                lookups.incrementAndGet();
                return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
              },
              operations);

      assertThatThrownBy(
              () ->
                  transport.postJson(
                      URI.create("https://provider.example/v1/chat/completions"),
                      "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                      "synthetic-only-token".toCharArray(),
                      Duration.ofSeconds(2),
                      4_096))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Configured operator support provider is unavailable.");
    }
    assertThat(lookups).hasValue(1);
  }
}
