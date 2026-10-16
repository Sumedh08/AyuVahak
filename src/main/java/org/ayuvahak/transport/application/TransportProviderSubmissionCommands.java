package org.ayuvahak.transport.application;

import java.time.Clock;
import java.time.Duration;
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
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.transport.domain.TransportAuthorities;
import org.ayuvahak.transport.domain.TransportCancellationOperation;
import org.ayuvahak.transport.domain.TransportDestinationChangeOperation;
import org.ayuvahak.transport.domain.TransportProviderSubmission;
import org.ayuvahak.transport.domain.TransportRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One-attempt, Transport-owned provider submissions through the Phase 9 technical boundary. */
@Service
public final class TransportProviderSubmissionCommands {

  // Phase 9 bounds partner operations to five minutes; this extra minute permits clean timeout
  // completion before a persisted ATTEMPTING row is classified as an unknown outcome.
  private static final Duration STALE_ATTEMPT_AFTER = Duration.ofMinutes(6);
  private static final int MAX_ATTEMPTS = 3;

  private final TransportRequestStore requests;
  private final TransportCancellationStore cancellations;
  private final TransportDestinationChangeStore destinationChanges;
  private final TransportProviderSubmissionStore submissions;
  private final TransportProviderSubmissionPort provider;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final TransportSettings properties;

  public TransportProviderSubmissionCommands(
      TransportRequestStore requests,
      TransportCancellationStore cancellations,
      TransportDestinationChangeStore destinationChanges,
      TransportProviderSubmissionStore submissions,
      TransportProviderSubmissionPort provider,
      AuthorizationService authorization,
      AuditWriter audit,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock,
      TransportSettings properties) {
    this.requests = Objects.requireNonNull(requests, "Transport requests");
    this.cancellations = Objects.requireNonNull(cancellations, "Transport cancellations");
    this.destinationChanges =
        Objects.requireNonNull(destinationChanges, "Transport destination changes");
    this.submissions = Objects.requireNonNull(submissions, "Transport provider submissions");
    this.provider = Objects.requireNonNull(provider, "Transport provider port");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
    this.properties = Objects.requireNonNull(properties, "Transport properties");
  }

  /**
   * Returns empty when no deployment-selected provider exists; manual operation remains available.
   */
  public Optional<SubmissionResult> submit(
      ExecutionContext context,
      InternalId requestId,
      TransportProviderSubmission.OperationKind kind,
      InternalId operationReference) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(requestId, "Transport request id");
    Objects.requireNonNull(kind, "provider operation kind");
    Objects.requireNonNull(operationReference, "operation reference");
    if (!properties.hasProviderPartner()) return Optional.empty();
    requireScope(context);
    requireAuthority(context, kind, operationReference);

    Prepared prepared;
    try {
      prepared =
          transaction.execute(
              ignored -> {
                TransportRequest request =
                    requests
                        .lock(requestId)
                        .orElseThrow(TransportProviderSubmissionCommands::notFound);
                requireOriginScope(context, request);
                TransportProviderSubmission existing =
                    submissions.findForUpdate(kind, operationReference).orElse(null);
                if (existing != null) {
                  requireSameTarget(existing, requestId);
                  Instant now = clock.instant();
                  if (isStaleAttempt(existing, now)) {
                    String failureCode = "ATTEMPT_EXPIRED_UNKNOWN";
                    if (!submissions.transition(
                        operationReference,
                        kind,
                        existing.version(),
                        TransportProviderSubmission.State.OUTCOME_UNKNOWN,
                        existing.configurationVersion(),
                        Optional.empty(),
                        Optional.of(failureCode),
                        now)) {
                      throw unavailable();
                    }
                    TransportProviderSubmission unknown =
                        new TransportProviderSubmission(
                            existing.operationReference(),
                            existing.transportRequestId(),
                            existing.operationKind(),
                            existing.partnerKey(),
                            TransportProviderSubmission.State.OUTCOME_UNKNOWN,
                            existing.attemptCount(),
                            existing.configurationVersion(),
                            Optional.empty(),
                            Optional.of(failureCode),
                            existing.createdAt(),
                            now,
                            existing.version() + 1);
                    auditMutation(
                        context, unknown, "TRANSPORT_PROVIDER_SUBMISSION_OUTCOME_UNKNOWN");
                    return new Prepared(unknown, Optional.empty(), false);
                  }
                  if (existing.state() == TransportProviderSubmission.State.RETRY_PENDING) {
                    if (existing.attemptCount() >= MAX_ATTEMPTS) {
                      String failureCode = "TRUST_LOOKUP_RETRIES_EXHAUSTED";
                      if (!submissions.transition(
                          operationReference,
                          kind,
                          existing.version(),
                          TransportProviderSubmission.State.REJECTED,
                          existing.configurationVersion(),
                          Optional.empty(),
                          Optional.of(failureCode),
                          now)) {
                        throw unavailable();
                      }
                      TransportProviderSubmission rejected =
                          new TransportProviderSubmission(
                              existing.operationReference(),
                              existing.transportRequestId(),
                              existing.operationKind(),
                              existing.partnerKey(),
                              TransportProviderSubmission.State.REJECTED,
                              existing.attemptCount(),
                              existing.configurationVersion(),
                              Optional.empty(),
                              Optional.of(failureCode),
                              existing.createdAt(),
                              now,
                              existing.version() + 1);
                      auditMutation(context, rejected, "TRANSPORT_PROVIDER_SUBMISSION_REJECTED");
                      return new Prepared(rejected, Optional.empty(), false);
                    }
                    TransportProviderSubmissionRequest providerRequest =
                        buildRequest(
                            context, request, kind, operationReference, existing.partnerKey());
                    if (!submissions.claimRetry(
                        operationReference, kind, existing.version(), now)) {
                      throw unavailable();
                    }
                    TransportProviderSubmission attempting =
                        new TransportProviderSubmission(
                            existing.operationReference(),
                            existing.transportRequestId(),
                            existing.operationKind(),
                            existing.partnerKey(),
                            TransportProviderSubmission.State.ATTEMPTING,
                            existing.attemptCount() + 1,
                            existing.configurationVersion(),
                            Optional.empty(),
                            Optional.empty(),
                            existing.createdAt(),
                            now,
                            existing.version() + 1);
                    auditMutation(
                        context, attempting, "TRANSPORT_PROVIDER_SUBMISSION_RETRY_STARTED");
                    return new Prepared(attempting, Optional.of(providerRequest), true);
                  }
                  return new Prepared(existing, Optional.empty(), false);
                }
                TransportProviderSubmissionRequest providerRequest =
                    buildRequest(
                        context,
                        request,
                        kind,
                        operationReference,
                        properties.getProviderPartnerKey());
                Instant now = clock.instant();
                TransportProviderSubmission attempting =
                    new TransportProviderSubmission(
                        operationReference,
                        requestId,
                        kind,
                        properties.getProviderPartnerKey(),
                        TransportProviderSubmission.State.ATTEMPTING,
                        1,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        now,
                        now,
                        1);
                if (!submissions.insert(attempting)) {
                  TransportProviderSubmission raced =
                      submissions
                          .find(kind, operationReference)
                          .orElseThrow(TransportProviderSubmissionCommands::unavailable);
                  requireSameTarget(raced, requestId);
                  return new Prepared(raced, Optional.empty(), false);
                }
                auditMutation(context, attempting, "TRANSPORT_PROVIDER_SUBMISSION_STARTED");
                return new Prepared(attempting, Optional.of(providerRequest), true);
              });
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
    if (!prepared.firstAttempt()) {
      return Optional.of(new SubmissionResult(prepared.submission(), true));
    }

    TransportProviderSubmissionResult submitted;
    try {
      submitted = provider.submit(prepared.request().orElseThrow());
    } catch (RuntimeException uncertain) {
      submitted =
          new TransportProviderSubmissionResult(
              TransportProviderSubmission.State.OUTCOME_UNKNOWN,
              Optional.empty(),
              Optional.empty(),
              Optional.of("PROVIDER_OPERATION_FAILURE"));
    }
    final TransportProviderSubmissionResult outcome = submitted;

    try {
      TransportProviderSubmission completed =
          transaction.execute(
              ignored -> {
                TransportProviderSubmission current =
                    submissions
                        .find(kind, operationReference)
                        .orElseThrow(TransportProviderSubmissionCommands::unavailable);
                if (current.state() != TransportProviderSubmission.State.ATTEMPTING) {
                  return current;
                }
                Instant now = clock.instant();
                TransportProviderSubmission.State finalState = outcome.state();
                Optional<String> finalFailureCode = outcome.failureCode();
                if (outcome.state() == TransportProviderSubmission.State.RETRY_PENDING
                    && current.attemptCount() >= MAX_ATTEMPTS) {
                  finalState = TransportProviderSubmission.State.REJECTED;
                  finalFailureCode = Optional.of("TRUST_LOOKUP_RETRIES_EXHAUSTED");
                }
                if (!submissions.transition(
                    operationReference,
                    kind,
                    current.version(),
                    finalState,
                    outcome.configurationVersion(),
                    outcome.responseStatus(),
                    finalFailureCode,
                    now)) {
                  throw unavailable();
                }
                if (finalState == TransportProviderSubmission.State.OUTCOME_UNKNOWN) {
                  recordBusinessUncertainty(context, kind, operationReference);
                }
                TransportProviderSubmission result =
                    new TransportProviderSubmission(
                        current.operationReference(),
                        current.transportRequestId(),
                        current.operationKind(),
                        current.partnerKey(),
                        finalState,
                        current.attemptCount(),
                        outcome.configurationVersion(),
                        outcome.responseStatus(),
                        finalFailureCode,
                        current.createdAt(),
                        now,
                        current.version() + 1);
                auditMutation(context, result, "TRANSPORT_PROVIDER_SUBMISSION_" + result.state());
                return result;
              });
      return Optional.of(new SubmissionResult(completed, false));
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private TransportProviderSubmissionRequest buildRequest(
      ExecutionContext context,
      TransportRequest request,
      TransportProviderSubmission.OperationKind kind,
      InternalId operationReference,
      String partnerKey) {
    Optional<String> reason = Optional.empty();
    Optional<InternalId> proposedDestination = Optional.empty();
    switch (kind) {
      case DISPATCH -> {
        if (!request.id().equals(operationReference)
            || request.status() != TransportRequest.Status.REQUESTED) {
          throw conflict("Only an unsubmitted REQUESTED Transport request may be dispatched.");
        }
      }
      case CANCELLATION -> {
        TransportCancellationOperation operation =
            cancellations
                .lock(operationReference)
                .orElseThrow(TransportProviderSubmissionCommands::notFound);
        if (!operation.transportRequestId().equals(request.id())
            || operation.state() != TransportCancellationOperation.State.PENDING) {
          throw conflict("Only the linked pending cancellation operation may be submitted.");
        }
        reason = Optional.of(operation.reasonCode());
      }
      case DESTINATION_CHANGE -> {
        TransportDestinationChangeOperation operation =
            destinationChanges
                .lock(operationReference)
                .orElseThrow(TransportProviderSubmissionCommands::notFound);
        if (!operation.transportRequestId().equals(request.id())
            || operation.state() != TransportDestinationChangeOperation.State.PENDING) {
          throw conflict("Only the linked pending destination-change operation may be submitted.");
        }
        reason = Optional.of(operation.reasonCode());
        proposedDestination = Optional.of(operation.proposedFacilityId());
      }
    }
    return new TransportProviderSubmissionRequest(
        partnerKey,
        kind,
        operationReference,
        request.id(),
        request.transferId(),
        request.originFacility(),
        request.destinationFacility(),
        reason,
        proposedDestination,
        context.correlationId());
  }

  private static boolean isStaleAttempt(TransportProviderSubmission submission, Instant now) {
    return submission.state() == TransportProviderSubmission.State.ATTEMPTING
        && !now.isBefore(submission.updatedAt().plus(STALE_ATTEMPT_AFTER));
  }

  private void recordBusinessUncertainty(
      ExecutionContext context,
      TransportProviderSubmission.OperationKind kind,
      InternalId operationReference) {
    if (kind == TransportProviderSubmission.OperationKind.CANCELLATION) {
      TransportCancellationOperation current =
          cancellations
              .lock(operationReference)
              .orElseThrow(TransportProviderSubmissionCommands::notFound);
      if (current.state() == TransportCancellationOperation.State.PENDING
          && !cancellations.markState(
              operationReference,
              current.version(),
              TransportCancellationOperation.State.OUTCOME_UNKNOWN)) {
        throw unavailable();
      }
      auditBusinessOperation(context, operationReference, "TRANSPORT_CANCELLATION_OUTCOME_UNKNOWN");
    } else if (kind == TransportProviderSubmission.OperationKind.DESTINATION_CHANGE) {
      TransportDestinationChangeOperation current =
          destinationChanges
              .lock(operationReference)
              .orElseThrow(TransportProviderSubmissionCommands::notFound);
      if (current.state() == TransportDestinationChangeOperation.State.PENDING
          && !destinationChanges.markUncertain(
              operationReference,
              current.version(),
              TransportDestinationChangeOperation.State.OUTCOME_UNKNOWN,
              clock.instant())) {
        throw unavailable();
      }
      auditBusinessOperation(
          context, operationReference, "TRANSPORT_DESTINATION_CHANGE_OUTCOME_UNKNOWN");
    }
  }

  private void requireAuthority(
      ExecutionContext context,
      TransportProviderSubmission.OperationKind kind,
      InternalId operationReference) {
    String authority =
        switch (kind) {
          case DISPATCH -> TransportAuthorities.dispatchCoordination().code();
          case CANCELLATION -> TransportAuthorities.cancellation().code();
          case DESTINATION_CHANGE -> TransportAuthorities.destinationChange().code();
        };
    var decision = authorization.evaluate(context, authority);
    if (decision.isAllowed()) return;
    boolean unavailable =
        decision.denialReason().orElse(null)
            == org.ayuvahak.platform.authorization.domain.AuthorizationDecision.DenialReason
                .GRANT_STORE_UNAVAILABLE;
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              "TRANSPORT_PROVIDER_SUBMISSION_DENIED",
              "TransportProviderSubmission",
              Optional.of(operationReference),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of(unavailable ? "AUTHORIZATION_STORE_UNAVAILABLE" : "AUTHORIZATION_DENIED"),
              authenticationEvidence(context),
              AuditAuthorizationOutcome.DENIED,
              context.correlationId()));
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Provider submission remains denied because its denial audit is unavailable.");
    }
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        "The caller is not authorized to submit this Transport operation to a provider.");
  }

  private void requireScope(ExecutionContext context) {
    if (context.scope().kind() != ExecutionScope.Kind.FACILITY
        || context.scope().facility().isEmpty()
        || context.scope().organization().isEmpty()
        || context.actor().kind() == ActorKind.HUMAN
            && context.authenticationMechanism() != AuthenticationMechanism.HUMAN_ACCESS_TOKEN) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Provider submission requires an authorized Facility execution context.");
    }
  }

  private static void requireOriginScope(ExecutionContext context, TransportRequest request) {
    if (context.scope().facility().filter(request.originFacility()::equals).isEmpty()) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Provider submission requires the request's origin Facility scope.");
    }
  }

  private void auditMutation(
      ExecutionContext context, TransportProviderSubmission submission, String action) {
    try {
      audit.recordMutation(
          new AuditEvidence(
              AuditActionKind.PROTECTED_MUTATION,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              action,
              "TransportProviderSubmission",
              Optional.of(submission.operationReference()),
              submission.updatedAt(),
              AuditOutcome.SUCCEEDED,
              Optional.of(submission.state().name()),
              authenticationEvidence(context),
              AuditAuthorizationOutcome.ALLOWED,
              context.correlationId(),
              Optional.of(
                  new AuditTargetContext(
                      context.scope().kind(),
                      context.scope().organization(),
                      context.scope().facility()))));
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Provider submission state and required audit could not be committed.");
    }
  }

  private void auditBusinessOperation(ExecutionContext context, InternalId id, String action) {
    try {
      audit.recordMutation(
          new AuditEvidence(
              AuditActionKind.PROTECTED_MUTATION,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              action,
              "TransportOperation",
              Optional.of(id),
              clock.instant(),
              AuditOutcome.SUCCEEDED,
              Optional.of("OUTCOME_UNKNOWN"),
              authenticationEvidence(context),
              AuditAuthorizationOutcome.ALLOWED,
              context.correlationId(),
              Optional.of(
                  new AuditTargetContext(
                      context.scope().kind(),
                      context.scope().organization(),
                      context.scope().facility()))));
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The uncertain provider outcome and required audit could not be committed.");
    }
  }

  private static void requireSameTarget(
      TransportProviderSubmission existing, InternalId requestId) {
    if (!existing.transportRequestId().equals(requestId)) {
      throw conflict("The provider operation identity belongs to a different Transport request.");
    }
  }

  private static AuditAuthenticationEvidence authenticationEvidence(ExecutionContext context) {
    return switch (context.authenticationMechanism()) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.NOT_FOUND,
        "The linked Transport provider operation was not found.");
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSPORT_UNAVAILABLE,
        "Transport provider submission could not be recovered safely.");
  }

  private static PlatformProblemException conflict(String detail) {
    return problem(HttpStatus.CONFLICT, PlatformErrorCode.TRANSPORT_STATE_CONFLICT, detail);
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  private record Prepared(
      TransportProviderSubmission submission,
      Optional<TransportProviderSubmissionRequest> request,
      boolean firstAttempt) {}

  public record SubmissionResult(TransportProviderSubmission submission, boolean replayed) {}
}
