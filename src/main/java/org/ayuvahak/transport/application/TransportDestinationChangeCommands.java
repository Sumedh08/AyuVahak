package org.ayuvahak.transport.application;

import java.time.Clock;
import java.time.Instant;
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
import org.ayuvahak.platform.audit.domain.AuditTargetContext;
import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CausationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.OutboxWriter;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transport.domain.TransportAuthorities;
import org.ayuvahak.transport.domain.TransportDestinationChangeOperation;
import org.ayuvahak.transport.domain.TransportProviderSubmission;
import org.ayuvahak.transport.domain.TransportRequest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Transport owner commands for a durable manual destination-change request and acknowledgement. */
@Service
public final class TransportDestinationChangeCommands {

  private static final EventType RESOLVED =
      EventType.parse("transport.destination-change-resolved.v1");
  private static final String ISSUER = "urn:ayuvahak:directory";
  private final TransportRequestStore requests;
  private final TransportDestinationChangeStore changes;
  private final TransportProviderSubmissionStore providerSubmissions;
  private final TransferDestinationChangeEligibilityPort transferEligibility;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final OutboxWriter outbox;
  private final ExecutionContextFactory contexts;
  private final AuthenticationProperties authentication;
  private final TransportSettings properties;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final ObjectMapper mapper;

  public TransportDestinationChangeCommands(
      TransportRequestStore requests,
      TransportDestinationChangeStore changes,
      TransportProviderSubmissionStore providerSubmissions,
      TransferDestinationChangeEligibilityPort transferEligibility,
      AuthorizationService authorization,
      AuditWriter audit,
      OutboxWriter outbox,
      ExecutionContextFactory contexts,
      AuthenticationProperties authentication,
      TransportSettings properties,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock,
      ObjectMapper mapper) {
    this.requests = Objects.requireNonNull(requests, "Transport request store");
    this.changes = Objects.requireNonNull(changes, "destination-change store");
    this.providerSubmissions = Objects.requireNonNull(providerSubmissions, "provider submissions");
    this.transferEligibility = Objects.requireNonNull(transferEligibility, "Transfer eligibility");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.outbox = Objects.requireNonNull(outbox, "outbox writer");
    this.contexts = Objects.requireNonNull(contexts, "execution context factory");
    this.authentication = Objects.requireNonNull(authentication, "authentication properties");
    this.properties = Objects.requireNonNull(properties, "Transport properties");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
    this.mapper = Objects.requireNonNull(mapper, "object mapper");
  }

  public TransportDestinationChangeResult request(
      ExecutionContext serviceContext,
      InternalId operationId,
      InternalId transferDestinationChangeId,
      InternalId transferId,
      InternalId transportRequestId,
      ExternalReference oldDestination,
      ExternalReference proposedDestination,
      String reasonCode,
      ActorReference initiatingActor) {
    requireServiceContext(serviceContext);
    requireFacility(oldDestination);
    requireFacility(proposedDestination);
    if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
      throw invalid("A coded destination-change reason is required.");
    }
    authorize(serviceContext, operationId);
    TransferDestinationChangeEligibility eligibility =
        checkTransferEligibility(
            serviceContext,
            transferId,
            transportRequestId,
            transferDestinationChangeId,
            oldDestination,
            proposedDestination);
    if (eligibility.outcome() != TransferDestinationChangeEligibility.Outcome.ELIGIBLE) {
      throw eligibilityProblem(eligibility);
    }
    TransportDestinationChangeOperation proposed =
        new TransportDestinationChangeOperation(
            operationId,
            transferDestinationChangeId,
            transferId,
            transportRequestId,
            InternalId.parse(oldDestination.value()),
            InternalId.parse(proposedDestination.value()),
            reasonCode,
            TransportDestinationChangeOperation.State.PENDING,
            Optional.empty(),
            initiatingActor,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            serviceContext.correlationId(),
            1,
            clock.instant(),
            clock.instant());
    try {
      return Objects.requireNonNull(
          transaction.execute(
              status -> {
                Optional<TransportDestinationChangeOperation> existing =
                    changes.findByTransferOperation(transferDestinationChangeId);
                if (existing.isPresent()) {
                  TransportDestinationChangeOperation found = existing.orElseThrow();
                  if (!sameRequest(found, proposed)) {
                    throw conflict("The Transfer destination-change identity was reused.");
                  }
                  return new TransportDestinationChangeResult(found, true);
                }
                if (!changes.insert(
                    proposed,
                    initiatingActor.identity().issuer().value(),
                    initiatingActor.identity().resourceType())) {
                  throw conflict("A concurrent Transport destination-change request exists.");
                }
                auditMutation(serviceContext, proposed, "TRANSPORT_DESTINATION_CHANGE_REQUESTED");
                return new TransportDestinationChangeResult(proposed, false);
              }),
          "Transport destination-change result");
    } catch (CannotAcquireLockException busy) {
      throw conflict("A matching Transport destination-change request is still processing.");
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  public TransportDestinationChangeResult resolve(
      ExecutionContext operatorContext,
      InternalId operationId,
      TransportDestinationChangeOperation.Outcome outcome,
      String sourceNamespace,
      String sourceObservationId,
      Instant sourceOccurredAt,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(operatorContext, "operator context");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(fingerprint, "resolution fingerprint");
    if ((operatorContext.actor().kind() != ActorKind.HUMAN
            && operatorContext.actor().kind() != ActorKind.SERVICE)
        || operatorContext.scope().kind() != ExecutionScope.Kind.FACILITY) {
      throw invalidScope();
    }
    requireSource(sourceNamespace, sourceObservationId, sourceOccurredAt);
    authorize(operatorContext, operationId);
    try {
      return Objects.requireNonNull(
          transaction.execute(
              status -> {
                TransportDestinationChangeOperation current =
                    changes
                        .lock(operationId)
                        .orElseThrow(TransportDestinationChangeCommands::notFound);
                TransportRequest request =
                    requests
                        .find(current.transportRequestId())
                        .orElseThrow(TransportDestinationChangeCommands::unavailable);
                if (!request.id().equals(current.transportRequestId())
                    || !request.transferId().equals(current.transferId())) {
                  throw conflict(
                      "The Transport destination-change operation is not linked to its request.");
                }
                if (operatorContext
                    .scope()
                    .facility()
                    .filter(request.originFacility()::equals)
                    .isEmpty()) {
                  throw invalidScope();
                }
                if (current.state() == TransportDestinationChangeOperation.State.RESOLVED) {
                  if (current.outcome().filter(outcome::equals).isPresent()
                      && current
                          .resolutionFingerprint()
                          .filter(fingerprint.value()::equals)
                          .isPresent()) {
                    return new TransportDestinationChangeResult(current, true);
                  }
                  throw conflict("A resolved destination-change operation cannot be changed.");
                }
                Instant now = clock.instant();
                InternalId eventId = InternalId.generate();
                if (!changes.resolve(
                    current.id(),
                    current.version(),
                    outcome,
                    operatorContext.actor(),
                    sourceNamespace,
                    sourceObservationId,
                    fingerprint.value(),
                    sourceOccurredAt,
                    now,
                    eventId)) {
                  throw conflict("The Transport destination-change outcome changed concurrently.");
                }
                TransportDestinationChangeOperation resolved =
                    new TransportDestinationChangeOperation(
                        current.id(),
                        current.transferDestinationChangeId(),
                        current.transferId(),
                        current.transportRequestId(),
                        current.oldFacilityId(),
                        current.proposedFacilityId(),
                        current.reasonCode(),
                        TransportDestinationChangeOperation.State.RESOLVED,
                        Optional.of(outcome),
                        current.initiatedBy(),
                        Optional.of(operatorContext.actor()),
                        Optional.of(sourceNamespace),
                        Optional.of(sourceObservationId),
                        Optional.of(fingerprint.value()),
                        Optional.of(sourceOccurredAt),
                        Optional.of(now),
                        Optional.of(eventId),
                        operatorContext.correlationId(),
                        current.version() + 1,
                        current.createdAt(),
                        now);
                auditMutation(operatorContext, resolved, "TRANSPORT_DESTINATION_CHANGE_RESOLVED");
                recordResolvedEvent(operatorContext, request, resolved, now);
                return new TransportDestinationChangeResult(resolved, false);
              }),
          "Transport destination-change result");
    } catch (CannotAcquireLockException busy) {
      throw conflict("A matching Transport destination-change command is still processing.");
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  /** Resolves an inbound partner outcome only for the partner recorded on its outbound request. */
  public TransportDestinationChangeResult resolveFromPartner(
      ExecutionContext context,
      InternalId operationId,
      TransportDestinationChangeOperation.Outcome outcome,
      String sourceNamespace,
      String sourceObservationId,
      Instant sourceOccurredAt,
      RequestFingerprint fingerprint,
      String partnerKey) {
    Objects.requireNonNull(context, "operator context");
    if (context.scope().kind() != ExecutionScope.Kind.FACILITY) {
      throw invalidScope();
    }
    authorize(context, operationId);
    requireProviderSubmission(context, operationId, partnerKey);
    return resolve(
        context,
        operationId,
        outcome,
        sourceNamespace,
        sourceObservationId,
        sourceOccurredAt,
        fingerprint);
  }

  private void requireProviderSubmission(
      ExecutionContext context, InternalId operationId, String partnerKey) {
    try {
      TransportDestinationChangeOperation operation =
          changes.find(operationId).orElseThrow(TransportDestinationChangeCommands::notFound);
      TransportProviderSubmission submission =
          providerSubmissions
              .find(TransportProviderSubmission.OperationKind.DESTINATION_CHANGE, operationId)
              .orElse(null);
      if (submission != null
          && submission.partnerKey().equals(partnerKey)
          && submission.transportRequestId().equals(operation.transportRequestId())) {
        return;
      }
      String reason = submission == null ? "PROVIDER_SUBMISSION_MISSING" : "PARTNER_MISMATCH";
      try {
        audit.recordNonMutating(
            new AuditEvidence(
                AuditActionKind.SECURITY_OUTCOME,
                Optional.of(context.actor()),
                Optional.of(context.scope()),
                "TRANSPORT_PROVIDER_OUTCOME_DENIED",
                "TransportProviderSubmission",
                Optional.of(operationId),
                clock.instant(),
                AuditOutcome.DENIED,
                Optional.of(reason),
                authenticationEvidence(context),
                AuditAuthorizationOutcome.DENIED,
                context.correlationId()));
      } catch (AuditPersistenceUnavailableException auditUnavailable) {
        throw problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            PlatformErrorCode.AUDIT_UNAVAILABLE,
            "The provider outcome remains denied because its audit is unavailable.");
      }
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.AUTHORIZATION_DENIED,
          "The authenticated partner is not linked to this Transport operation.");
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private TransferDestinationChangeEligibility checkTransferEligibility(
      ExecutionContext context,
      InternalId transferId,
      InternalId requestId,
      InternalId operationId,
      ExternalReference oldDestination,
      ExternalReference proposedDestination) {
    try {
      return transferEligibility.check(
          context, transferId, requestId, operationId, oldDestination, proposedDestination);
    } catch (RuntimeException unavailable) {
      return new TransferDestinationChangeEligibility(
          TransferDestinationChangeEligibility.Outcome.UNAVAILABLE, "TRANSFER_UNAVAILABLE");
    }
  }

  private ExecutionContext serviceContext(
      ExecutionContext operatorContext, TransportRequest request, InternalId operationId) {
    ActorReference worker =
        ActorReference.service(
            new IssuerId(authentication.issuer()), properties.getWorkerClientId());
    return contexts.forBackgroundWork(
        worker,
        RequestedExecutionScope.facility(
            operatorContext.scope().organization().orElseThrow(), request.originFacility()),
        operatorContext.correlationId(),
        Optional.of(new CausationId(operationId.toString())));
  }

  private void requireServiceContext(ExecutionContext context) {
    if (context == null
        || context.actor().kind() != ActorKind.SERVICE
        || !properties.getWorkerClientId().equals(context.actor().identity().value())
        || context.authenticationMechanism() != AuthenticationMechanism.CONFIGURED_SERVICE_IDENTITY
        || context.scope().kind() != ExecutionScope.Kind.FACILITY) {
      throw invalidScope();
    }
  }

  private void authorize(ExecutionContext context, InternalId operationId) {
    AuthorizationDecision decision =
        authorization.evaluate(context, TransportAuthorities.destinationChange().code());
    if (decision.isAllowed()) return;
    boolean unavailable =
        decision.denialReason().orElse(null)
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              "TRANSPORT_DESTINATION_CHANGE_DENIED",
              "TransportDestinationChangeOperation",
              Optional.of(operationId),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of(unavailable ? "AUTHORIZATION_STORE_UNAVAILABLE" : "AUTHORIZATION_DENIED"),
              authenticationEvidence(context),
              AuditAuthorizationOutcome.DENIED,
              context.correlationId()));
    } catch (AuditPersistenceUnavailableException auditUnavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The destination-change action remains denied because its audit is unavailable.");
    }
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        unavailable
            ? "Transport authorization is temporarily unavailable."
            : "The actor is not authorized to coordinate this destination change.");
  }

  private void auditMutation(
      ExecutionContext context, TransportDestinationChangeOperation operation, String action) {
    try {
      audit.recordMutation(
          new AuditEvidence(
              AuditActionKind.PROTECTED_MUTATION,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              action,
              "TransportDestinationChangeOperation",
              Optional.of(operation.id()),
              clock.instant(),
              AuditOutcome.SUCCEEDED,
              operation.outcome().map(Enum::name),
              authenticationEvidence(context),
              AuditAuthorizationOutcome.ALLOWED,
              context.correlationId(),
              Optional.of(
                  new AuditTargetContext(
                      context.scope().kind(),
                      context.scope().organization(),
                      context.scope().facility()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The destination-change operation and required audit could not be committed.");
    }
  }

  private void recordResolvedEvent(
      ExecutionContext context,
      TransportRequest request,
      TransportDestinationChangeOperation operation,
      Instant recordedAt) {
    var payload = mapper.createObjectNode();
    payload.put("transferReference", operation.transferId().toString());
    payload.put("transportRequestReference", operation.transportRequestId().toString());
    payload.put(
        "transferDestinationChangeReference", operation.transferDestinationChangeId().toString());
    payload.put("transportDestinationChangeReference", operation.id().toString());
    payload.put("oldDestinationFacilityReference", operation.oldFacilityId().toString());
    payload.put("proposedDestinationFacilityReference", operation.proposedFacilityId().toString());
    payload.put("outcome", operation.outcome().orElseThrow().name());
    payload.put("sourceNamespace", operation.sourceNamespace().orElseThrow());
    payload.put("sourceObservationId", operation.sourceObservationId().orElseThrow());
    outbox.record(
        new EventEnvelope(
            operation.resolutionEventId().orElseThrow(),
            RESOLVED,
            "transport",
            new OccurredAt(operation.sourceOccurredAt().orElseThrow()),
            new RecordedAt(recordedAt),
            context,
            Optional.of(
                new AggregatePosition(
                    "transport-destination-change/" + operation.id(), operation.version())),
            payload));
  }

  private static boolean sameRequest(
      TransportDestinationChangeOperation left, TransportDestinationChangeOperation right) {
    return left.id().equals(right.id())
        && left.transferId().equals(right.transferId())
        && left.transportRequestId().equals(right.transportRequestId())
        && left.oldFacilityId().equals(right.oldFacilityId())
        && left.proposedFacilityId().equals(right.proposedFacilityId())
        && left.reasonCode().equals(right.reasonCode());
  }

  private static void requireSource(String namespace, String observationId, Instant occurredAt) {
    if (namespace == null
        || !namespace.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")
        || observationId == null
        || !observationId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        || occurredAt == null) {
      throw invalid("A bounded source identity and source time are required.");
    }
  }

  private static void requireFacility(ExternalReference facility) {
    if (facility == null
        || !ISSUER.equals(facility.issuer().value())
        || !"Facility".equals(facility.resourceType())) {
      throw invalid("A Directory Facility reference is required.");
    }
    InternalId.parse(facility.value());
  }

  private static AuditAuthenticationEvidence authenticationEvidence(ExecutionContext context) {
    return switch (context.authenticationMechanism()) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }

  private static PlatformProblemException eligibilityProblem(
      TransferDestinationChangeEligibility eligibility) {
    return problem(
        eligibility.outcome() == TransferDestinationChangeEligibility.Outcome.UNAVAILABLE
            ? HttpStatus.SERVICE_UNAVAILABLE
            : HttpStatus.CONFLICT,
        eligibility.outcome() == TransferDestinationChangeEligibility.Outcome.UNAVAILABLE
            ? PlatformErrorCode.TRANSFER_UNAVAILABLE
            : PlatformErrorCode.TRANSPORT_NOT_ELIGIBLE,
        "Transfer did not confirm that this destination change is currently eligible.");
  }

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.TRANSPORT_STATE_CONFLICT,
        "The Transport destination-change operation was not found.");
  }

  private static PlatformProblemException invalidScope() {
    return problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "Destination-change coordination requires the configured Transport worker or an authorized Facility operator.");
  }

  private static PlatformProblemException invalid(String detail) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.TRANSPORT_EVIDENCE_INVALID, detail);
  }

  private static PlatformProblemException conflict(String detail) {
    return problem(HttpStatus.CONFLICT, PlatformErrorCode.TRANSPORT_STATE_CONFLICT, detail);
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSFER_UNAVAILABLE,
        "Transport could not commit the destination-change operation safely.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
