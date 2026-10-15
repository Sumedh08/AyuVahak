package org.ayuvahak.platform.exchange.application;

import java.util.Optional;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;

/** Resolves partner-scoped credentials only after an active trust profile is selected. */
public interface PartnerAuthorizationPort {

  Optional<PartnerBearerToken> bearerToken(PartnerProfileVersion activeProfile);

  /** Acquires credentials only after a fresh trust check at the external token endpoint. */
  default Optional<PartnerBearerToken> bearerToken(
      PartnerProfileVersion activeProfile, Runnable beforeAttempt) {
    beforeAttempt.run();
    return bearerToken(activeProfile);
  }

  void invalidate(PartnerKey partnerKey);
}
