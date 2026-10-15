package org.ayuvahak.platform.exchange.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Resolves only an active profile whose inbound principal and named operation both match. */
@Service
public final class ActiveInboundPartnerProfileLookup
    implements ActiveInboundPartnerProfileLookupPort {

  private final PartnerTrustProfileStore store;

  public ActiveInboundPartnerProfileLookup(PartnerTrustProfileStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public Optional<PartnerProfileVersion> findActive(
      ActorReference principal, AllowedOperation operation) {
    Objects.requireNonNull(principal, "principal");
    Objects.requireNonNull(operation, "operation");
    try {
      return store.findActiveInbound(principal, operation);
    } catch (DataAccessException unavailable) {
      throw new PartnerTrustLookupUnavailableException(unavailable);
    }
  }
}
