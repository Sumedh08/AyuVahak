package org.ayuvahak.platform.exchange.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.junit.jupiter.api.Test;

class EgressAddressPolicyTest {

  private static final PartnerKey PARTNER = new PartnerKey("SYNTHETIC-DIRECTORY");
  private final EgressAddressPolicy productionDefaults =
      new EgressAddressPolicy(Set.of(443), Map.of());

  @Test
  void acceptsPublicUnicastAddressOnApprovedPort() {
    assertThat(productionDefaults.validate(PARTNER, 443, List.of(address("8.8.8.8"))))
        .containsExactly(address("8.8.8.8"));
  }

  @Test
  void rejectsLoopbackLinkLocalMetadataMulticastUnspecifiedAndReservedAddresses() {
    for (String value :
        List.of(
            "127.0.0.1",
            "169.254.2.5",
            "169.254.169.254",
            "100.100.100.200",
            "100.64.0.1",
            "224.0.0.1",
            "0.0.0.0",
            "240.0.0.1",
            "192.0.0.10",
            "192.88.99.1",
            "::",
            "::1",
            "64:ff9b::808:808",
            "100:0:0:1::1",
            "2001:1::1",
            "2001:2::1",
            "2001:10::1",
            "2001:db8::1",
            "3fff::1",
            "5f00::1",
            "2002::1",
            "fe80::1",
            "ff02::1",
            "fc00::1")) {
      assertThatThrownBy(() -> productionDefaults.validate(PARTNER, 443, List.of(address(value))))
          .as("must reject %s", value)
          .isInstanceOf(UnsafeOutboundDestinationException.class);
    }
  }

  @Test
  void privateNetworkExceptionIsLimitedToTheNamedPartnerAndExactNetwork() {
    assertThatThrownBy(
            () -> productionDefaults.validate(PARTNER, 443, List.of(address("10.12.4.1"))))
        .isInstanceOf(UnsafeOutboundDestinationException.class);

    EgressAddressPolicy deploymentPolicy =
        new EgressAddressPolicy(Set.of(443), Map.of(PARTNER.value(), List.of("10.12.0.0/16")));

    assertThat(deploymentPolicy.validate(PARTNER, 443, List.of(address("10.12.4.1"))))
        .containsExactly(address("10.12.4.1"));
    assertThatThrownBy(() -> deploymentPolicy.validate(PARTNER, 443, List.of(address("10.13.4.1"))))
        .isInstanceOf(UnsafeOutboundDestinationException.class);
    assertThatThrownBy(
            () ->
                deploymentPolicy.validate(
                    new PartnerKey("SYNTHETIC-OTHER"), 443, List.of(address("10.12.4.1"))))
        .isInstanceOf(UnsafeOutboundDestinationException.class);
  }

  @Test
  void rejectsEntireAnswerSetWhenAnyResolvedAddressIsForbidden() {
    assertThatThrownBy(
            () ->
                productionDefaults.validate(
                    PARTNER, 443, List.of(address("8.8.8.8"), address("127.0.0.1"))))
        .isInstanceOf(UnsafeOutboundDestinationException.class);
  }

  @Test
  void rejectsEmptyAnswersAndUnapprovedPorts() {
    assertThatThrownBy(() -> productionDefaults.validate(PARTNER, 443, List.of()))
        .isInstanceOf(UnsafeOutboundDestinationException.class);
    assertThatThrownBy(
            () -> productionDefaults.validate(PARTNER, 8443, List.of(address("8.8.8.8"))))
        .isInstanceOf(UnsafeOutboundDestinationException.class);
  }

  @Test
  void configuredPrivateNetworkNeverOverridesLoopbackAndLinkLocalDenials() {
    EgressAddressPolicy deploymentPolicy =
        new EgressAddressPolicy(
            Set.of(443), Map.of(PARTNER.value(), List.of("127.0.0.0/8", "192.168.0.0/16")));

    assertThat(deploymentPolicy.validate(PARTNER, 443, List.of(address("192.168.10.12"))))
        .containsExactly(address("192.168.10.12"));
    assertThatThrownBy(() -> deploymentPolicy.validate(PARTNER, 443, List.of(address("127.0.0.1"))))
        .isInstanceOf(UnsafeOutboundDestinationException.class);
    assertThatThrownBy(
            () -> deploymentPolicy.validate(PARTNER, 443, List.of(address("169.254.4.2"))))
        .isInstanceOf(UnsafeOutboundDestinationException.class);
  }

  @Test
  void rejectsDuplicatePartnerNetworkConfiguration() {
    assertThatThrownBy(
            () ->
                new EgressAddressPolicy(
                    Set.of(443), Map.of(PARTNER.value(), List.of("10.12.0.0/16", "10.12.0.0/16"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static InetAddress address(String value) {
    try {
      return InetAddress.getByName(value);
    } catch (Exception exception) {
      throw new AssertionError("test fixture is not an IP literal", exception);
    }
  }
}
