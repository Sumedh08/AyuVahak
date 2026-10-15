package org.ayuvahak.platform.exchange.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.RetryClass;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** Resolves only active owner-approved operations before delegating to technical transport. */
@Service
@Lazy
public final class PartnerOutboundReadService implements PartnerOutboundReadPort {

  private final PartnerTrustProfileStore profiles;
  private final PartnerAuthorizationPort authorization;
  private final PartnerHttpTransportPort transport;

  public PartnerOutboundReadService(
      PartnerTrustProfileStore profiles,
      PartnerAuthorizationPort authorization,
      PartnerHttpTransportPort transport) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.transport = Objects.requireNonNull(transport, "transport");
  }

  @Override
  public void requireActive(PartnerKey partnerKey, AllowedOperation operation) {
    requireActiveProfile(partnerKey, operation);
  }

  @Override
  public PartnerOutboundReadResponse read(PartnerOutboundReadRequest request) {
    Objects.requireNonNull(request, "request");
    PartnerProfileVersion profile = requireActiveProfile(request.partnerKey(), request.operation());
    int maxAttempts =
        profile.configuration().retryClass() == RetryClass.IDEMPOTENT_READ
            ? profile.configuration().limits().maxAttempts()
            : 1;
    long totalBudgetNanos =
        java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
            (long) profile.configuration().limits().operationTimeoutMillis() * maxAttempts);
    long deadline = System.nanoTime() + totalBudgetNanos;
    for (int attempt = 1; ; attempt++) {
      try {
        Runnable beforeAttempt = () -> requireSameActiveProfile(profile, request.operation());
        Optional<PartnerBearerToken> bearerToken =
            authorization.bearerToken(profile, beforeAttempt);
        return transport.execute(profile, request, bearerToken, beforeAttempt);
      } catch (PartnerTransportException failure) {
        if (attempt >= maxAttempts || !PartnerReadRetryPolicy.isRetryable(failure.failure())) {
          throw failure;
        }
        java.time.Duration delay = PartnerReadRetryPolicy.delay(attempt, failure.retryAfter());
        if (deadline - System.nanoTime() <= delay.toNanos()) {
          throw failure;
        }
        PartnerReadRetryPolicy.pause(delay);
        requireSameActiveProfile(profile, request.operation());
      }
    }
  }

  private void requireSameActiveProfile(
      PartnerProfileVersion expected, AllowedOperation operation) {
    PartnerProfileVersion current =
        requireActiveProfile(expected.configuration().partnerKey(), operation);
    if (!expected.id().equals(current.id()) || expected.version() != current.version()) {
      authorization.invalidate(expected.configuration().partnerKey());
      throw new PartnerTransportException(PartnerTransportFailure.PROFILE_NOT_ACTIVE);
    }
  }

  private PartnerProfileVersion requireActiveProfile(
      PartnerKey partnerKey, AllowedOperation operation) {
    Objects.requireNonNull(partnerKey, "partnerKey");
    Objects.requireNonNull(operation, "operation");
    return profiles
        .findActive(partnerKey, operation)
        .orElseGet(
            () -> {
              authorization.invalidate(partnerKey);
              throw new PartnerTransportException(PartnerTransportFailure.PROFILE_NOT_ACTIVE);
            });
  }
}
