package org.ayuvahak.platform.exchange.application;

import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;

/** Platform Exchange-owned query for an active, explicitly bound inbound partner identity. */
public interface ActiveInboundPartnerProfileLookupPort {

  Optional<PartnerProfileVersion> findActive(ActorReference principal, AllowedOperation operation);
}
