package org.ayuvahak.platform.exchange.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.OwnerContext;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Protocol;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Executes one configured side-effecting operation without creating an Exchange business ledger.
 */
@Service
@Lazy
public final class PartnerOutboundOperationService implements PartnerOutboundOperationPort {

  private final PartnerTrustProfileStore profiles;
  private final PartnerAuthorizationPort authorization;
  private final PartnerHttpOperationTransportPort transport;

  public PartnerOutboundOperationService(
      PartnerTrustProfileStore profiles,
      PartnerAuthorizationPort authorization,
      PartnerHttpOperationTransportPort transport) {
    this.profiles = Objects.requireNonNull(profiles, "partner profiles");
    this.authorization = Objects.requireNonNull(authorization, "partner authorization");
    this.transport = Objects.requireNonNull(transport, "partner operation transport");
  }

  @Override
  public PartnerOutboundOperationResponse execute(PartnerOutboundOperationRequest request) {
    Objects.requireNonNull(request, "request");
    PartnerProfileVersion profile = requireActive(request.partnerKey(), request.operation());
    Runnable beforeAttempt = () -> requireSameActiveProfile(profile, request.operation());
    Optional<PartnerBearerToken> token = authorization.bearerToken(profile, beforeAttempt);
    return transport.execute(profile, request, token, beforeAttempt);
  }

  private void requireSameActiveProfile(
      PartnerProfileVersion expected, AllowedOperation operation) {
    PartnerProfileVersion current = requireActive(expected.configuration().partnerKey(), operation);
    if (!expected.id().equals(current.id()) || expected.version() != current.version()) {
      authorization.invalidate(expected.configuration().partnerKey());
      throw new PartnerTransportException(PartnerTransportFailure.PROFILE_NOT_ACTIVE);
    }
  }

  private PartnerProfileVersion requireActive(
      org.ayuvahak.platform.exchange.domain.PartnerKey partnerKey, AllowedOperation operation) {
    if (operation.ownerContext() != OwnerContext.TRANSPORT
        || operation.direction() != Direction.OUTBOUND
        || operation.protocol() != Protocol.JSON) {
      throw new PartnerTransportException(PartnerTransportFailure.PROFILE_NOT_ACTIVE);
    }
    try {
      return profiles
          .findActive(partnerKey, operation)
          .filter(
              profile ->
                  profile.configuration().retryClass()
                      == org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.RetryClass
                          .NEVER)
          .orElseThrow(
              () -> new PartnerTransportException(PartnerTransportFailure.PROFILE_NOT_ACTIVE));
    } catch (DataAccessException unavailable) {
      throw new PartnerTrustLookupUnavailableException(unavailable);
    }
  }
}
