package org.ayuvahak.platform.exchange.configuration;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Deployment allowlist for partner ports and narrowly scoped private partner networks. */
@Validated
@ConfigurationProperties("ayuvahak.exchange.egress")
public record ExchangeEgressProperties(
    @NotEmpty Set<Integer> allowedPorts,
    Map<String, List<String>> allowedPrivateNetworksByPartner) {

  public ExchangeEgressProperties {
    allowedPorts = Set.copyOf(allowedPorts);
    allowedPrivateNetworksByPartner =
        allowedPrivateNetworksByPartner == null
            ? Map.of()
            : Map.copyOf(allowedPrivateNetworksByPartner);
  }
}
