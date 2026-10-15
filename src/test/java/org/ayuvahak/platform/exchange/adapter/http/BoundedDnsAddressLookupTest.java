package org.ayuvahak.platform.exchange.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedDnsAddressLookupTest {

  @Test
  void timesOutAnInjectedResolverAndReturnsAStableFailureClass() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (BoundedDnsAddressLookup lookup =
        new BoundedDnsAddressLookup(
            (hostname, timeout) -> {
              started.countDown();
              try {
                release.await();
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.net.UnknownHostException("synthetic interrupted lookup");
              }
              return new InetAddress[] {
                InetAddress.getByAddress(hostname, new byte[] {8, 8, 8, 8})
              };
            })) {
      assertThatThrownBy(() -> lookup.lookup("partner.example", Duration.ofMillis(50)))
          .isInstanceOfSatisfying(
              DnsResolutionFailure.class,
              failure ->
                  assertThat(failure.reason()).isEqualTo(DnsResolutionFailure.Reason.TIMED_OUT));
      assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
    } finally {
      release.countDown();
    }
  }
}
