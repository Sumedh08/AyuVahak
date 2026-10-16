package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.capacity.domain.CapacityReservationOutcome;
import org.ayuvahak.capacity.domain.CapacityReservationRequest;
import org.ayuvahak.capacity.domain.CapacityReservationResult;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.audit.domain.AuditTargetContext;
import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CausationId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.transfer.adapter.configuration.TransferProperties;
import org.ayuvahak.transfer.domain.DestinationChangeReservationPlan;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferDestinationChangeOperation;
import org.ayuvahak.transfer.domain.TransferDestinationChangeOperation.CapacityOutcome;
import org.ayuvahak.transfer.domain.TransferDestinationChangeOperation.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Coordinates Transfer's durable reroute with owner-held Capacity and Transport operations. */
@Service
public final class TransferDestinationChangeCoordinator {

  private static final Logger logger =
      LoggerFactory.getLogger(TransferDestinationChangeCoordinator.class);
  private final TransferStore transfers;
  private final CapacityReservationPort capacity;
  private final TransportDestinationChangePort transport;
  private final ExecutionContextFactory contexts;
  private final AuthenticationProperties authentication;
  private final TransferProperties properties;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public TransferDestinationChangeCoordinator(
      TransferStore transfers,
      CapacityReservationPort capacity,
      TransportDestinationChangePort transport,
      ExecutionContextFactory contexts,
      AuthenticationProperties authentication,
      TransferProperties properties,
      AuthorizationService authorization,
      AuditWriter audit,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.transfers = Objects.requireNonNull(transfers, "Transfer store");
    this.capacity = Objects.requireNonNull(capacity, "Capacity owner port");
    this.transport = Objects.requireNonNull(transport, "Transport owner port");
    this.contexts = Objects.requireNonNull(contexts, "execution context factory");
    this.authentication = Objects.requireNonNull(authentication, "authentication properties");
    this.properties = Objects.requireNonNull(properties, "Transfer properties");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public TransferDestinationChangeOperation resumeAfterAcceptance(
      TransferDestinationChangeOperation operation) {
    Objects.requireNonNull(operation, "destination-change operation");
    TransferDestinationChangeOperation current = load(operation.id());
    if (current.status() == Status.CAPACITY_RESERVATION_PENDING
        || current.status() == Status.CAPACITY_RESERVATION_UNKNOWN) {
      reserveProposedDestination(current);
      current = load(current.id());
    }
    if (current.status() == Status.TRANSPORT_ACK_PENDING
        || current.status() == Status.TRANSPORT_OUTCOME_UNKNOWN) {
      requestTransportChange(current);
    }
    return load(operation.id());
  }

  private void reserveProposedDestination(TransferDestinationChangeOperation operation) {
    ExecutionContext context = capacityContext(operation);
    if (!authorization
        .evaluate(context, TransferAuthorities.reservationReconciliation().code())
        .isAllowed()) {
      logger.warn(
          "Destination-change Capacity call deferred correlationId={} reason=authorization",
          operation.correlationId());
      return;
    }
    Optional<DestinationChangeReservationPlan> plan = operation.reservationPlan();
    if (plan.isEmpty()) {
      persistCapacityState(
          operation,
          Status.RECONCILIATION_REQUIRED,
          CapacityOutcome.UNCONFIGURED,
          "TRANSFER_DESTINATION_CHANGE_CAPACITY_UNCONFIGURED");
      return;
    }
    DestinationChangeReservationPlan request = plan.orElseThrow();
    CapacityReservationResult result;
    try {
      result =
          capacity.reserve(
              context,
              new CapacityReservationRequest(
                  operation.capacityReservationOperationId(),
                  operation.transferId(),
                  operation.proposedFacility(),
                  request.dimensionCode(),
                  request.unit(),
                  request.quantity(),
                  request.intervalStart(),
                  request.intervalEnd(),
                  request.intervalEnd()),
              "transfer-capacity-" + operation.capacityReservationOperationId());
      if (!result.operationId().equals(operation.capacityReservationOperationId())) {
        persistCapacityState(
            operation,
            Status.CAPACITY_RESERVATION_UNKNOWN,
            CapacityOutcome.UNKNOWN,
            "TRANSFER_DESTINATION_CHANGE_CAPACITY_REFERENCE_MISMATCH");
        return;
      }
      if (result.outcome() == CapacityReservationResult.Outcome.RESERVED) {
        CapacityReservationOutcome confirmed =
            capacity.getReservationOutcome(
                context, operation.capacityReservationOperationId(), operation.transferId());
        if (confirmed.operationId().equals(operation.capacityReservationOperationId())
            && confirmed.transferId().equals(operation.transferId())
            && confirmed.status() == CapacityReservationOutcome.Status.RESERVED
            && confirmed.expiresAt().filter(clock.instant()::isBefore).isPresent()) {
          persistCapacityState(
              operation,
              Status.TRANSPORT_ACK_PENDING,
              CapacityOutcome.RESERVED,
              "TRANSFER_DESTINATION_CHANGE_CAPACITY_RESERVED");
          return;
        }
        persistCapacityState(
            operation,
            Status.CAPACITY_RESERVATION_UNKNOWN,
            CapacityOutcome.UNKNOWN,
            "TRANSFER_DESTINATION_CHANGE_CAPACITY_NOT_CONFIRMED");
        return;
      }
      Status next =
          result.outcome() == CapacityReservationResult.Outcome.UNKNOWN
              ? Status.CAPACITY_RESERVATION_UNKNOWN
              : Status.RECONCILIATION_REQUIRED;
      CapacityOutcome outcome =
          switch (result.outcome()) {
            case RESERVED -> CapacityOutcome.RESERVED;
            case CONFLICT -> CapacityOutcome.CONFLICT;
            case UNKNOWN -> CapacityOutcome.UNKNOWN;
            case INVALID -> CapacityOutcome.INVALID;
          };
      persistCapacityState(
          operation, next, outcome, "TRANSFER_DESTINATION_CHANGE_CAPACITY_" + outcome.name());
    } catch (RuntimeException unavailable) {
      logger.warn(
          "Destination-change Capacity outcome remains recoverable correlationId={} failureType={}",
          operation.correlationId(),
          unavailable.getClass().getSimpleName());
      try {
        persistCapacityState(
            operation,
            Status.CAPACITY_RESERVATION_UNKNOWN,
            CapacityOutcome.UNAVAILABLE,
            "TRANSFER_DESTINATION_CHANGE_CAPACITY_UNAVAILABLE");
      } catch (RuntimeException auditOrStoreUnavailable) {
        logger.warn(
            "Destination-change Capacity state remains pending correlationId={} failureType={}",
            operation.correlationId(),
            auditOrStoreUnavailable.getClass().getSimpleName());
      }
    }
  }

  private void requestTransportChange(TransferDestinationChangeOperation operation) {
    ExecutionContext context =
        contexts.forBackgroundWork(
            ActorReference.service(
                new IssuerId(authentication.issuer()), properties.getTransportWorkerClientId()),
            RequestedExecutionScope.facility(
                operation.originOrganization(), operation.originFacility()),
            operation.correlationId(),
            Optional.of(new CausationId(operation.id().toString())));
    try {
      transport.request(
          context,
          operation.transportOperationId(),
          operation.id(),
          operation.transferId(),
          operation.transportRequestId(),
          operation.oldFacility(),
          operation.proposedFacility(),
          operation.reasonCode(),
          operation.initiatedBy());
    } catch (RuntimeException unavailable) {
      logger.warn(
          "Destination-change Transport request remains recoverable correlationId={} failureType={}",
          operation.correlationId(),
          unavailable.getClass().getSimpleName());
    }
  }

  private void persistCapacityState(
      TransferDestinationChangeOperation initial,
      Status nextStatus,
      CapacityOutcome outcome,
      String action) {
    ExecutionContext context = capacityContext(initial);
    transaction.executeWithoutResult(
        ignored -> {
          TransferDestinationChangeOperation current =
              transfers.lockDestinationChange(initial.id()).orElseThrow();
          if (current.status() != Status.CAPACITY_RESERVATION_PENDING
              && current.status() != Status.CAPACITY_RESERVATION_UNKNOWN) {
            if (current.status() == nextStatus && current.capacityReservationOutcome() == outcome) {
              return;
            }
            return;
          }
          TransferDestinationChangeOperation updated = copy(current, nextStatus, outcome);
          if (!transfers.updateDestinationChange(updated, current.version())) {
            throw new IllegalStateException(
                "Transfer destination-change state changed concurrently");
          }
          audit.recordMutation(
              new AuditEvidence(
                  AuditActionKind.PROTECTED_MUTATION,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  action,
                  "TransferDestinationChange",
                  Optional.of(current.id()),
                  clock.instant(),
                  AuditOutcome.SUCCEEDED,
                  Optional.of(outcome.name()),
                  AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
                  AuditAuthorizationOutcome.ALLOWED,
                  context.correlationId(),
                  Optional.of(
                      new AuditTargetContext(
                          context.scope().kind(),
                          context.scope().organization(),
                          context.scope().facility()))));
        });
  }

  private ExecutionContext capacityContext(TransferDestinationChangeOperation operation) {
    return contexts.forBackgroundWork(
        ActorReference.service(
            new IssuerId(authentication.issuer()), properties.getReservationWorkerClientId()),
        RequestedExecutionScope.facility(
            operation.proposedOrganization(), operation.proposedFacility()),
        operation.correlationId(),
        Optional.of(new CausationId(operation.id().toString())));
  }

  private TransferDestinationChangeOperation load(org.ayuvahak.platform.core.InternalId id) {
    return transfers.findDestinationChange(id).orElseThrow();
  }

  private TransferDestinationChangeOperation copy(
      TransferDestinationChangeOperation current, Status status, CapacityOutcome capacityOutcome) {
    return new TransferDestinationChangeOperation(
        current.id(),
        current.transferId(),
        current.transportRequestId(),
        current.oldOrganization(),
        current.oldFacility(),
        current.proposedOrganization(),
        current.proposedFacility(),
        current.recommendationId(),
        current.referralRevision(),
        current.reasonCode(),
        current.reservationPlan(),
        status,
        current.capacityReservationOperationId(),
        capacityOutcome,
        current.oldReservationOperationId(),
        current.oldHoldReleaseOperationId(),
        current.proposedHoldReleaseOperationId(),
        current.transportOperationId(),
        current.providerOutcome(),
        current.initiatedBy(),
        current.originOrganization(),
        current.originFacility(),
        current.correlationId(),
        current.version() + 1,
        current.createdAt(),
        clock.instant());
  }
}
