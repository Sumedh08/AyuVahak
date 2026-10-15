package org.ayuvahak.platform.exchange.adapter.http;

import org.ayuvahak.platform.exchange.configuration.ExchangeEgressProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds bounded exchange transport infrastructure with production-safe defaults. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExchangeEgressProperties.class)
class ExchangeHttpConfiguration {

  @Bean
  OutboundAddressPolicy outboundAddressPolicy(ExchangeEgressProperties properties) {
    return new EgressAddressPolicy(
        properties.allowedPorts(), properties.allowedPrivateNetworksByPartner());
  }

  @Bean(destroyMethod = "close")
  BoundedDnsAddressLookup boundedDnsAddressLookup() {
    return new BoundedDnsAddressLookup();
  }

  @Bean(destroyMethod = "close")
  BoundedPartnerOperationExecutor boundedPartnerOperationExecutor() {
    return new BoundedPartnerOperationExecutor(8, 32);
  }
}
