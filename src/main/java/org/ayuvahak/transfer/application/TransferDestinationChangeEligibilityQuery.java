package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.audit.application.AuditPersistenceUnavailableException;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferDestinationChangeOperation;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Transfer-owner eligibility query; returns no patient context or private persistence data. */
@Service
public final class TransferDestinationChangeEligibilityQuery {

  private final TransferStore transfers;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final TransferSettings properties;
  private final Clock clock;

  public TransferDestinationChangeEligibilityQuery(
      TransferStore transfers,
      AuthorizationService authorization,
      AuditWriter audit,
      TransferSettings properties,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.transfers = Objects.requireNonNull(transfers, "Transfer store");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.properties = Objects.requireNonNull(properties, "Transfer properties");
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public TransferDestinationChangeEligibilityResult check(
      ExecutionContext context,
      InternalId transferId,
      InternalId transportRequestId,
      InternalId destinationChangeId,
      ExternalReference oldDestination,
      ExternalReference proposedDestination) {
    if (!isConfiguredTransportWorker(context)) {
      return denied(context, transferId, "SERVICE_IDENTITY_INVALID");
    }
    AuthorizationDecision decision =
        authorization.evaluate(
            context, TransferAuthorities.transportDestinationChangeEligibility().code());
    if (!decision.isAllowed()) {
      String reason =
          decision.denialReason().orElse(null)
                  == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE
              ? "AUTHORIZATION_STORE_UNAVAILABLE"
              : "AUTHORIZATION_DENIED";
      TransferDestinationChangeEligibilityResult result = denied(context, transferId, reason);
      return reason.equals("AUTHORIZATION_STORE_UNAVAILABLE") ? unavailable() : result;
    }
    try {
      Optional<TransferProgressTarget> target = transfers.lockProgressTarget(transferId);
      Optional<TransferDestinationChangeOperation> pending =
          transfers.findUnresolvedDestinationChange(transferId);
      if (target.isEmpty() || pending.isEmpty()) {
        return denied(context, transferId, "DESTINATION_CHANGE_NOT_PENDING");
      }
      TransferProgressTarget progress = target.orElseThrow();
      TransferDestinationChangeOperation operation = pending.orElseThrow();
      boolean eligible =
          progress.transfer().status() == CoordinatedTransfer.Status.IN_TRANSIT
              && progress
                  .originFacility()
                  .filter(context.scope().facility().orElseThrow()::equals)
                  .isPresent()
              && context
                  .scope()
                  .organization()
                  .filter(progress.originOrganization()::equals)
                  .isPresent()
              && transfers.isSelectedTransportRequest(transferId, transportRequestId)
              && operation.transferId().equals(transferId)
              && operation.transportRequestId().equals(transportRequestId)
              && operation.id().equals(destinationChangeId)
              && operation.status()
                  == TransferDestinationChangeOperation.Status.TRANSPORT_ACK_PENDING
              && operation.capacityReservationOutcome()
                  == TransferDestinationChangeOperation.CapacityOutcome.RESERVED
              && operation.oldFacility().equals(progress.receivingFacility())
              && operation.oldFacility().equals(oldDestination)
              && operation.proposedFacility().equals(proposedDestination)
              && transfers
                  .findUnresolvedDestinationChange(transferId)
                  .filter(value -> value.id().equals(operation.id()))
                  .isPresent();
      auditEligibility(context, transferId, eligible);
      return eligible
          ? new TransferDestinationChangeEligibilityResult(
              TransferDestinationChangeEligibilityResult.Outcome.ELIGIBLE, "ELIGIBLE")
          : new TransferDestinationChangeEligibilityResult(
              TransferDestinationChangeEligibilityResult.Outcome.INELIGIBLE,
              "TRANSFER_NOT_ELIGIBLE");
    } catch (DataAccessException unavailable) {
      return unavailable();
    }
  }

  private boolean isConfiguredTransportWorker(ExecutionContext context) {
    return context != null
        && context.actor().kind() == ActorKind.SERVICE
        && context.actor().identity().value().equals(properties.getTransportWorkerClientId())
        && context.authenticationMechanism() == AuthenticationMechanism.CONFIGURED_SERVICE_IDENTITY
        && context.scope().kind() == ExecutionScope.Kind.FACILITY
        && context.scope().organization().isPresent()
        && context.scope().facility().isPresent();
  }

  private TransferDestinationChangeEligibilityResult denied(
      ExecutionContext context, InternalId transferId, String reason) {
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              "TRANSFER_DESTINATION_CHANGE_ELIGIBILITY_DENIED",
              "Transfer",
              Optional.of(transferId),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of(reason),
              AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
              AuditAuthorizationOutcome.DENIED,
              context.correlationId()));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Transfer withheld destination-change eligibility because its audit is unavailable.");
    }
    return new TransferDestinationChangeEligibilityResult(
        TransferDestinationChangeEligibilityResult.Outcome.INELIGIBLE, reason);
  }

  private void auditEligibility(ExecutionContext context, InternalId transferId, boolean eligible) {
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              eligible ? AuditActionKind.SENSITIVE_READ : AuditActionKind.SECURITY_OUTCOME,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              "TRANSFER_DESTINATION_CHANGE_ELIGIBILITY_CHECK",
              "Transfer",
              Optional.of(transferId),
              clock.instant(),
              eligible ? AuditOutcome.SUCCEEDED : AuditOutcome.DENIED,
              Optional.of(eligible ? "ELIGIBLE" : "TRANSFER_NOT_ELIGIBLE"),
              AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
              eligible ? AuditAuthorizationOutcome.ALLOWED : AuditAuthorizationOutcome.DENIED,
              context.correlationId()));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Transfer withheld destination-change eligibility because its audit is unavailable.");
    }
  }

  private static TransferDestinationChangeEligibilityResult unavailable() {
    return new TransferDestinationChangeEligibilityResult(
        TransferDestinationChangeEligibilityResult.Outcome.UNAVAILABLE,
        "TRANSFER_STATE_UNAVAILABLE");
  }
}
