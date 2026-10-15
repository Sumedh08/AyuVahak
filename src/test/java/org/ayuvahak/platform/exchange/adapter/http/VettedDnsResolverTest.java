package org.ayuvahak.platform.exchange.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.junit.jupiter.api.Test;

class VettedDnsResolverTest {

  @Test
  void resolvesConfiguredHostnameOnceAndReturnsOnlyVettedAddresses() throws Exception {
    AtomicInteger lookups = new AtomicInteger();
    VettedDnsResolver resolver =
        new VettedDnsResolver(
            "partner.example",
            new PartnerKey("SYNTHETIC-DIRECTORY"),
            443,
            java.time.Duration.ofSeconds(1),
            new EgressAddressPolicy(Set.of(443), java.util.Map.of()),
            (hostname, timeout) -> {
              assertThat(hostname).isEqualTo("partner.example");
              lookups.incrementAndGet();
              return new InetAddress[] {address("8.8.8.8"), address("1.1.1.1")};
            });

    assertThat(resolver.resolve("partner.example"))
        .containsExactly(address("8.8.8.8"), address("1.1.1.1"));
    assertThat(resolver.resolve("partner.example"))
        .containsExactly(address("8.8.8.8"), address("1.1.1.1"));
    assertThat(lookups).hasValue(1);
  }

  @Test
  void refusesToResolveAHostnameOtherThanTheConfiguredPartner() throws Exception {
    AtomicInteger lookups = new AtomicInteger();
    VettedDnsResolver resolver =
        new VettedDnsResolver(
            "partner.example",
            new PartnerKey("SYNTHETIC-DIRECTORY"),
            443,
            java.time.Duration.ofSeconds(1),
            new EgressAddressPolicy(Set.of(443), java.util.Map.of()),
            (hostname, timeout) -> {
              lookups.incrementAndGet();
              return new InetAddress[] {address("8.8.8.8")};
            });

    assertThatThrownBy(() -> resolver.resolve("attacker.example"))
        .isInstanceOf(UnknownHostException.class);
    assertThat(lookups).hasValue(0);
  }

  @Test
  void checksPortBeforePerformingDnsLookup() {
    AtomicInteger lookups = new AtomicInteger();
    VettedDnsResolver resolver =
        new VettedDnsResolver(
            "partner.example",
            new PartnerKey("SYNTHETIC-DIRECTORY"),
            8443,
            java.time.Duration.ofSeconds(1),
            new EgressAddressPolicy(Set.of(443), java.util.Map.of()),
            (hostname, timeout) -> {
              lookups.incrementAndGet();
              return new InetAddress[] {address("8.8.8.8")};
            });

    assertThatThrownBy(() -> resolver.resolve("partner.example"))
        .isInstanceOfSatisfying(
            DnsResolutionFailure.class,
            failure ->
                assertThat(failure.reason())
                    .isEqualTo(DnsResolutionFailure.Reason.DESTINATION_REJECTED));
    assertThat(lookups).hasValue(0);
  }

  @Test
  void rejectsMixedAnswerSetAndNeverReturnsTheAllowedSubset() {
    VettedDnsResolver resolver =
        new VettedDnsResolver(
            "partner.example",
            new PartnerKey("SYNTHETIC-DIRECTORY"),
            443,
            java.time.Duration.ofSeconds(1),
            new EgressAddressPolicy(Set.of(443), java.util.Map.of()),
            (hostname, timeout) -> new InetAddress[] {address("8.8.8.8"), address("127.0.0.1")});

    assertThatThrownBy(() -> resolver.resolve("partner.example"))
        .isInstanceOf(UnknownHostException.class);
  }

  @Test
  void recognizesPeerOnlyWhenItMatchesOneOfTheVettedAddresses() throws Exception {
    VettedDnsResolver resolver =
        new VettedDnsResolver(
            "partner.example",
            new PartnerKey("SYNTHETIC-DIRECTORY"),
            443,
            java.time.Duration.ofSeconds(1),
            new EgressAddressPolicy(Set.of(443), java.util.Map.of()),
            (hostname, timeout) -> new InetAddress[] {address("8.8.8.8")});
    resolver.resolve("partner.example");

    assertThat(resolver.isVettedPeer(address("8.8.8.8"))).isTrue();
    assertThat(resolver.isVettedPeer(address("1.1.1.1"))).isFalse();
  }

  private static InetAddress address(String value) {
    try {
      return InetAddress.getByName(value);
    } catch (UnknownHostException exception) {
      throw new AssertionError("test fixture is not an IP literal", exception);
    }
  }
}
