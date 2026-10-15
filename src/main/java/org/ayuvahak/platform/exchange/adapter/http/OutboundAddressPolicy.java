package org.ayuvahak.platform.exchange.adapter.http;

import java.net.InetAddress;
import java.util.List;
import org.ayuvahak.platform.exchange.domain.PartnerKey;

/** Deployment-specific outbound address decision applied to the complete DNS answer set. */
interface OutboundAddressPolicy {

  void validatePort(PartnerKey partnerKey, int port);

  List<InetAddress> validate(PartnerKey partnerKey, int port, List<InetAddress> answers);
}
