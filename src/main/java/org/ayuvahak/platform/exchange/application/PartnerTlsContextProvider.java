package org.ayuvahak.platform.exchange.application;

import javax.net.ssl.SSLContext;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;

/** Resolves TLS trust material for an administrator-approved partner profile. */
@FunctionalInterface
public interface PartnerTlsContextProvider {

  SSLContext resolve(PartnerTrustConfiguration configuration);
}
