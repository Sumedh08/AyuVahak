package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.matching.application.RecommendationApplicabilityResult;
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
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.adapter.configuration.TransferProperties;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.DestinationChangeReservationPlan;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferDestinationChangeOperation;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Transfer-owned authorization and durable intent for a post-movement reroute. */
@Service
public final class TransferDestinationChangeCommands {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String BUSY_DETAIL =
      "A matching Transfer destination-change command is still processing; retry with the same idempotency key.";

  private final TransferStore transfers;
  private final MatchingApplicabilityPort matching;
  private final DirectoryScopeReferenceLookup directory;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final TransferProperties properties;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public TransferDestinationChangeCommands(
      TransferStore transfers,
      MatchingApplicabilityPort matching,
      DirectoryScopeReferenceLookup directory,
      AuthorizationService authorization,
      AuditWriter audit,
      TransferProperties properties,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.transfers = Objects.requireNonNull(transfers, "Transfer store");
    this.matching = Objects.requireNonNull(matching, "Matching applicability port");
    this.directory = Objects.requireNonNull(directory, "Directory reference lookup");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.properties = Objects.requireNonNull(properties, "Transfer properties");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public TransferDestinationChangeCommandResult request(
      ExecutionContext context,
      InternalId transferId,
      long referralRevision,
      InternalId recommendationId,
      ExternalReference proposedOrganization,
      ExternalReference proposedFacility,
      String reasonCode,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requireOriginContext(context);
    requireDirectoryReference(proposedOrganization, "Organization");
    requireDirectoryReference(proposedFacility, "Facility");
    if (referralRevision < 1 || reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
      throw invalid(
          "A positive Referral revision and coded destination-change reason are required.");
    }
    requireRerouteAuthority(context, transferId);
    CommandReceiptKey receiptKey = commandKey(context, transferId, idempotencyKey);
    InternalId proposedOperationId = InternalId.generate();
    try {
      return transaction.execute(
          ignored -> {
            Instant now = clock.instant();
            transfers.configureBoundedReceiptWait();
            TransferStore.DestinationChangeReceiptClaim claim =
                transfers.claimDestinationChangeReceipt(
                    receiptKey, fingerprint, proposedOperationId, now);
            TransferDestinationChangeCommandResult replay = replay(claim, transferId);
            if (replay != null) {
              return replay;
            }

            TransferProgressTarget target =
                transfers
                    .lockProgressTarget(transferId)
                    .orElseThrow(TransferDestinationChangeCommands::notFound);
            requireOrigin(context, transferId, target);
            if (target.transfer().status() != CoordinatedTransfer.Status.IN_TRANSIT) {
              throw conflict(
                  "A destination change is allowed only after Transfer records IN_TRANSIT.");
            }
            InternalId transportRequestId =
                transfers
                    .findSelectedTransportRequest(transferId)
                    .orElseThrow(TransferDestinationChangeCommands::unavailable);
            if (!transfers.isSelectedTransportRequest(transferId, transportRequestId)) {
              throw conflict("The linked Transport request is no longer selected.");
            }
            var journey =
                transfers
                    .findJourney(target.transfer().journeyId())
                    .orElseThrow(TransferDestinationChangeCommands::unavailable);
            if (!journey.transportRequired()) {
              throw conflict("This Transfer does not require coordinated Transport.");
            }
            if (transfers.findUnresolvedDestinationChange(transferId).isPresent()) {
              throw conflict("A previous destination change must be reconciled first.");
            }

            boolean activeDestination;
            try {
              activeDestination =
                  directory.isActiveFacilityInOrganization(proposedOrganization, proposedFacility);
            } catch (RuntimeException unavailable) {
              throw unavailable();
            }
            if (!activeDestination) {
              throw conflict("The proposed destination is not an active Directory Facility.");
            }
            if (target.receivingFacility().equals(proposedFacility)) {
              throw conflict("The proposed destination is already active for this Transfer.");
            }
            requireRecommendationApplicable(
                context,
                journey.referralId(),
                recommendationId,
                proposedFacility,
                referralRevision);

            TransferStore.ActiveDestination current =
                transfers
                    .findActiveDestination(transferId)
                    .orElseThrow(TransferDestinationChangeCommands::unavailable);
            if (!current.facility().equals(target.receivingFacility())) {
              throw unavailable();
            }
            TransferDestinationChangeOperation operation =
                new TransferDestinationChangeOperation(
                    proposedOperationId,
                    transferId,
                    transportRequestId,
                    current.organization(),
                    current.facility(),
                    proposedOrganization,
                    proposedFacility,
                    recommendationId,
                    referralRevision,
                    reasonCode,
                    reservationPlan(now),
                    TransferDestinationChangeOperation.Status.AWAITING_RECEIVER_DECISIONS,
                    InternalId.generate(),
                    TransferDestinationChangeOperation.CapacityOutcome.PENDING,
                    current.reservationOperationId(),
                    InternalId.generate(),
                    InternalId.generate(),
                    InternalId.generate(),
                    Optional.empty(),
                    context.actor(),
                    target.originOrganization(),
                    target.originFacility().orElseThrow(),
                    context.correlationId(),
                    1,
                    now,
                    now);
            if (!transfers.insertDestinationChange(operation)) {
              throw conflict("A destination change is already pending for this Transfer.");
            }
            auditMutation(context, transferId, "TRANSFER_DESTINATION_CHANGE_REQUESTED", now);
            transfers.completeDestinationChangeReceipt(
                receiptKey, fingerprint, operation.id(), now);
            return new TransferDestinationChangeCommandResult(operation, false);
          });
    } catch (CannotAcquireLockException busy) {
      throw problem(HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private TransferDestinationChangeCommandResult replay(
      TransferStore.DestinationChangeReceiptClaim claim, InternalId transferId) {
    return switch (claim.state()) {
      case OWNER -> null;
      case KEY_REUSED ->
          throw problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
              "The Idempotency-Key was already used for a different destination-change command.");
      case IN_PROGRESS ->
          throw problem(
              HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
      case REPLAY -> {
        TransferDestinationChangeOperation operation =
            transfers
                .findDestinationChange(claim.operationId().orElseThrow())
                .orElseThrow(TransferDestinationChangeCommands::unavailable);
        if (!operation.transferId().equals(transferId)) {
          throw unavailable();
        }
        yield new TransferDestinationChangeCommandResult(operation, true);
      }
    };
  }

  private void requireRecommendationApplicable(
      ExecutionContext context,
      InternalId referralId,
      InternalId recommendationId,
      ExternalReference facility,
      long referralRevision) {
    RecommendationApplicabilityResult result;
    try {
      result =
          matching.revalidate(context, referralId, recommendationId, facility, referralRevision);
    } catch (RuntimeException unavailable) {
      throw unavailable();
    }
    if (result == null
        || !result.recommendationId().equals(recommendationId)
        || !result.candidateFacility().equals(facility)) {
      throw unavailable();
    }
    switch (result.status()) {
      case APPLICABLE -> {}
      case NOT_APPLICABLE ->
          throw problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.TRANSFER_RECOMMENDATION_STALE,
              "The recommendation no longer applies to the proposed destination and Referral revision.");
      case INDETERMINATE -> throw unavailable();
    }
  }

  private Optional<DestinationChangeReservationPlan> reservationPlan(Instant start) {
    return properties
        .reservationPolicy()
        .map(
            policy ->
                new DestinationChangeReservationPlan(
                    policy.dimension(),
                    policy.unit(),
                    policy.quantity(),
                    start,
                    start.plus(policy.duration())));
  }

  private void requireRerouteAuthority(ExecutionContext context, InternalId transferId) {
    AuthorizationDecision decision =
        authorization.evaluate(context, TransferAuthorities.reroute().code());
    if (decision.isAllowed()) {
      return;
    }
    boolean unavailable =
        decision.denialReason().orElse(null)
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
    auditDenied(context, transferId, unavailable);
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        unavailable
            ? "Transfer authorization is temporarily unavailable."
            : "The actor is not authorized to request this destination change.");
  }

  private void requireOrigin(
      ExecutionContext context, InternalId transferId, TransferProgressTarget target) {
    boolean sameOrganization =
        target.originOrganization().equals(context.scope().organization().orElseThrow());
    boolean sameFacility = target.originFacility().equals(context.scope().facility());
    if (!sameOrganization || !sameFacility) {
      auditDenied(context, transferId, false);
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.TRANSFER_SCOPE_MISMATCH,
          "The Transfer is outside the authorized origin scope.");
    }
  }

  private void auditMutation(
      ExecutionContext context, InternalId transferId, String action, Instant recordedAt) {
    try {
      audit.recordMutation(
          new AuditEvidence(
              AuditActionKind.PROTECTED_MUTATION,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              action,
              "TransferDestinationChange",
              Optional.of(transferId),
              recordedAt,
              AuditOutcome.SUCCEEDED,
              Optional.of("POST_MOVEMENT_DESTINATION_CHANGE"),
              authentication(context.authenticationMechanism()),
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
          "The destination-change intent and required audit could not be committed.");
    }
  }

  private void auditDenied(ExecutionContext context, InternalId transferId, boolean unavailable) {
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              "TRANSFER_DESTINATION_CHANGE_DENIED",
              "TransferDestinationChange",
              Optional.of(transferId),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of(unavailable ? "AUTHORIZATION_STORE_UNAVAILABLE" : "AUTHORIZATION_DENIED"),
              authentication(context.authenticationMechanism()),
              AuditAuthorizationOutcome.DENIED,
              context.correlationId(),
              Optional.of(
                  new AuditTargetContext(
                      context.scope().kind(),
                      context.scope().organization(),
                      context.scope().facility()))));
    } catch (AuditPersistenceUnavailableException auditUnavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The request remains denied; its required security audit is unavailable.");
    }
  }

  private static void requireOriginContext(ExecutionContext context) {
    if (context == null
        || context.actor().kind() != ActorKind.HUMAN
        || context.scope().kind() != ExecutionScope.Kind.FACILITY
        || context.scope().organization().isEmpty()
        || context.scope().facility().isEmpty()) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "A destination change requires a human actor in validated origin Facility scope.");
    }
    requireDirectoryReference(context.scope().organization().orElseThrow(), "Organization");
    requireDirectoryReference(context.scope().facility().orElseThrow(), "Facility");
  }

  private static void requireDirectoryReference(ExternalReference reference, String type) {
    if (reference == null
        || !DIRECTORY_ISSUER.equals(reference.issuer().value())
        || !type.equals(reference.resourceType())) {
      throw invalid("A valid Directory " + type + " reference is required.");
    }
    InternalId.parse(reference.value());
  }

  private static CommandReceiptKey commandKey(
      ExecutionContext context, InternalId transferId, String idempotencyKey) {
    try {
      return CommandReceiptKey.from(
          context,
          "transfer.destination-change.request",
          "transfer/" + transferId + "/destination-changes",
          idempotencyKey);
    } catch (IllegalArgumentException malformed) {
      throw invalid("A valid Idempotency-Key is required for the destination-change command.");
    }
  }

  private static AuditAuthenticationEvidence authentication(AuthenticationMechanism mechanism) {
    return switch (mechanism) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.TRANSFER_STATE_CONFLICT,
        "The Transfer journey was not found.");
  }

  private static PlatformProblemException invalid(String detail) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.TRANSFER_REQUEST_INVALID, detail);
  }

  private static PlatformProblemException conflict(String detail) {
    return problem(HttpStatus.CONFLICT, PlatformErrorCode.TRANSFER_STATE_CONFLICT, detail);
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSFER_UNAVAILABLE,
        "Transfer could not commit the destination-change request safely.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
