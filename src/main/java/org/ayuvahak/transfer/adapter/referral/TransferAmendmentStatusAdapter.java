package org.ayuvahak.transfer.adapter.referral;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.referral.adapter.configuration.ReferralProperties;
import org.ayuvahak.referral.application.TransferAmendmentStatusPort;
import org.ayuvahak.referral.application.TransferAmendmentStatusUnavailableException;
import org.ayuvahak.transfer.application.TransferAmendmentStatusQuery;
import org.ayuvahak.transfer.application.TransferStore;
import org.springframework.stereotype.Component;

/** Transfer-owned adapter implementing Referral's narrow owner-status port. */
@Component
public final class TransferAmendmentStatusAdapter implements TransferAmendmentStatusPort {

  private final ActorReference serviceActor;
  private final ExecutionContextFactory contexts;
  private final TransferAmendmentStatusQuery transfer;

  public TransferAmendmentStatusAdapter(
      ReferralProperties referralProperties,
      AuthenticationProperties authentication,
      ExecutionContextFactory contexts,
      TransferAmendmentStatusQuery transfer) {
    this.serviceActor =
        ActorReference.service(
            new IssuerId(authentication.issuer()),
            referralProperties.getTransferAmendmentCheckClientId());
    this.contexts = Objects.requireNonNull(contexts, "execution context factory");
    this.transfer = Objects.requireNonNull(transfer, "Transfer amendment-status owner query");
  }

  @Override
  public CoordinationGuard acquireCoordinationGuard(
      ExecutionContext referralRequest, InternalId referralId) {
    Objects.requireNonNull(referralRequest, "Referral request context");
    Objects.requireNonNull(referralId, "Referral id");
    try {
      ExecutionContext ownerContext = contextFor(referralRequest);
      TransferStore.ReferralCoordinationGuard guard =
          transfer.acquireReferralCoordinationGuard(ownerContext, referralId);
      return guard::close;
    } catch (RuntimeException unavailable) {
      throw new TransferAmendmentStatusUnavailableException(unavailable);
    }
  }

  @Override
  public boolean hasActiveCoordination(ExecutionContext referralRequest, InternalId referralId) {
    Objects.requireNonNull(referralRequest, "Referral request context");
    Objects.requireNonNull(referralId, "Referral id");
    try {
      ExecutionContext ownerContext = contextFor(referralRequest);
      return transfer.hasActiveCoordination(ownerContext, referralId);
    } catch (RuntimeException unavailable) {
      throw new TransferAmendmentStatusUnavailableException(unavailable);
    }
  }

  private ExecutionContext contextFor(ExecutionContext requester) {
    ExecutionScope scope = requester.scope();
    if (scope.kind() == ExecutionScope.Kind.FACILITY) {
      return contexts.forBackgroundWork(
          serviceActor,
          RequestedExecutionScope.facility(
              scope.organization().orElseThrow(), scope.facility().orElseThrow()),
          requester.correlationId(),
          Optional.empty());
    }
    if (scope.kind() == ExecutionScope.Kind.ORGANIZATION) {
      return contexts.forBackgroundWork(
          serviceActor,
          RequestedExecutionScope.organization(scope.organization().orElseThrow()),
          requester.correlationId(),
          Optional.empty());
    }
    throw new IllegalArgumentException(
        "Referral amendment requires organization or facility scope");
  }
}
