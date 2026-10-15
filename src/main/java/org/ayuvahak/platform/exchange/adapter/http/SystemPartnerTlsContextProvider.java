package org.ayuvahak.platform.exchange.adapter.http;

import javax.net.ssl.SSLContext;
import org.ayuvahak.platform.exchange.application.PartnerTlsContextProvider;
import org.ayuvahak.platform.exchange.application.PartnerTransportException;
import org.ayuvahak.platform.exchange.application.PartnerTransportFailure;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.springframework.stereotype.Component;

/** Uses platform system trust; partner-specific CA resolution is added with P9-03 secrets. */
@Component
final class SystemPartnerTlsContextProvider implements PartnerTlsContextProvider {

  @Override
  public SSLContext resolve(PartnerTrustConfiguration configuration) {
    if (configuration.tlsPolicy() != PartnerTrustConfiguration.TlsPolicy.SYSTEM_CA) {
      throw new PartnerTransportException(PartnerTransportFailure.TLS_FAILURE);
    }
    try {
      return SSLContext.getDefault();
    } catch (Exception exception) {
      throw new PartnerTransportException(PartnerTransportFailure.TLS_FAILURE);
    }
  }
}
