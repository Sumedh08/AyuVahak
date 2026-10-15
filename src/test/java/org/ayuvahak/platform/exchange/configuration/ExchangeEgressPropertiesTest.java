package org.ayuvahak.platform.exchange.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ExchangeEgressPropertiesTest {

  @Test
  void omittedPrivateNetworkAllowlistDefaultsToDenyAll() {
    var properties = new ExchangeEgressProperties(Set.of(443), null);

    assertThat(properties.allowedPrivateNetworksByPartner()).isEmpty();
  }
}
