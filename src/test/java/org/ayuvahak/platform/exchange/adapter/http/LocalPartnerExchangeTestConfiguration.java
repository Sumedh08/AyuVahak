package org.ayuvahak.platform.exchange.adapter.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.ayuvahak.platform.exchange.application.PartnerTlsContextProvider;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test-only egress trust for one synthetic HTTPS server bound to loopback. */
@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "ayuvahak.test.partner.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class LocalPartnerExchangeTestConfiguration {

  private static final PartnerKey TEST_PARTNER = new PartnerKey("synthetic-directory-feed");
  private static final byte[] LOOPBACK = {127, 0, 0, 1};

  @Bean
  @Primary
  OutboundAddressPolicy testOnlyLoopbackAddressPolicy(
      @Value("${ayuvahak.test.partner.port}") int localPartnerPort) {
    return new OutboundAddressPolicy() {
      @Override
      public void validatePort(PartnerKey partnerKey, int port) {
        if (!TEST_PARTNER.equals(partnerKey) || port != localPartnerPort) {
          throw new UnsafeOutboundDestinationException(
              UnsafeOutboundDestinationException.Reason.PORT_NOT_ALLOWED);
        }
      }

      @Override
      public List<InetAddress> validate(
          PartnerKey partnerKey, int port, List<InetAddress> answers) {
        validatePort(partnerKey, port);
        if (answers == null
            || answers.isEmpty()
            || answers.stream()
                .anyMatch(address -> !Arrays.equals(address.getAddress(), LOOPBACK))) {
          throw new UnsafeOutboundDestinationException(
              UnsafeOutboundDestinationException.Reason.FORBIDDEN_ADDRESS);
        }
        return List.copyOf(answers);
      }
    };
  }

  @Bean
  @Primary
  VettedDnsResolver.AddressLookup testOnlyLocalPartnerAddressLookup() {
    return (hostname, timeout) -> {
      if (!"partner.test".equals(hostname)) {
        throw new UnknownHostException("Test exchange permits only its synthetic local partner.");
      }
      return new InetAddress[] {InetAddress.getByAddress(hostname, LOOPBACK)};
    };
  }

  @Bean
  @Primary
  PartnerTlsContextProvider testOnlySyntheticPartnerTlsContextProvider() throws Exception {
    KeyStore partnerKeyStore = KeyStore.getInstance("PKCS12");
    char[] password = "local-test-only".toCharArray();
    try (var input =
        LocalPartnerExchangeTestConfiguration.class.getResourceAsStream(
            "/exchange/synthetic-partner.p12")) {
      if (input == null) {
        throw new IllegalStateException("Synthetic local TLS fixture is missing.");
      }
      partnerKeyStore.load(input, password);
    }
    KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustStore.load(null, null);
    trustStore.setCertificateEntry(
        "synthetic-partner", partnerKeyStore.getCertificate("synthetic-partner"));
    TrustManagerFactory trustManagers =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trustStore);
    SSLContext clientContext = SSLContext.getInstance("TLS");
    clientContext.init(null, trustManagers.getTrustManagers(), null);
    return configuration -> {
      if (configuration.tlsPolicy() != PartnerTrustConfiguration.TlsPolicy.SYSTEM_CA) {
        throw new org.ayuvahak.platform.exchange.application.PartnerTransportException(
            org.ayuvahak.platform.exchange.application.PartnerTransportFailure.TLS_FAILURE);
      }
      return clientContext;
    };
  }
}
