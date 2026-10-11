package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
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
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.adapter.configuration.TransferProperties;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates the next ordered request only after the previous attempt has a terminal outcome. */
@Service
@Lazy
public final class TransferNextRequestCommands {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String BUSY_DETAIL =
      "A matching Transfer command is still being processed; retry with the same idempotency key.";

  private final TransferStore store;
  private final MatchingApplicabilityPort matching;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransferProperties properties;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public TransferNextRequestCommands(
      @Lazy TransferStore store,
      MatchingApplicabilityPort matching,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      TransferProperties properties,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "Transfer store");
    this.matching = Objects.requireNonNull(matching, "Matching applicability port");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.properties = Objects.requireNonNull(properties, "Transfer properties");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public TransferJourneyCommandResult createNextRequest(
      ExecutionContext context,
      InternalId journeyId,
      long referralRevision,
      InternalId recommendationId,
      ExternalReference candidateFacility,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(journeyId, "TransferJourney id");
    Objects.requireNonNull(recommendationId, "recommendation id");
    Objects.requireNonNull(candidateFacility, "candidate Facility");
    Objects.requireNonNull(fingerprint, "request fingerprint");
    requireOriginScope(context.scope());
    requireDirectoryFacility(candidateFacility);
    if (referralRevision < 1) {
      throw invalid("A positive submitted Referral revision is required.");
    }
    requireAuthority(context, journeyId);
    CommandReceiptKey receiptKey = commandKey(context, journeyId, idempotencyKey);
    Optional<TransferStore.ReceiptClaim> existing;
    try {
      existing = store.findReceipt(receiptKey, fingerprint);
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
    if (existing.isPresent()) {
      TransferJourneyCommandResult replay = replay(journeyId, existing.orElseThrow());
      if (replay != null) {
        return replay;
      }
    }

    TransferJourney currentJourney =
        store.findJourney(journeyId).orElseThrow(TransferNextRequestCommands::notFound);
    requireOriginTarget(context, currentJourney);
    RecommendationApplicabilityResult applicability;
    try {
      applicability =
          matching.revalidate(
              context,
              currentJourney.referralId(),
              recommendationId,
              candidateFacility,
              referralRevision);
    } catch (RuntimeException unavailable) {
      throw matchingUnavailable();
    }
    if (applicability == null
        || !recommendationId.equals(applicability.recommendationId())
        || !candidateFacility.equals(applicability.candidateFacility())) {
      throw matchingUnavailable();
    }
    switch (applicability.status()) {
      case APPLICABLE -> {}
      case NOT_APPLICABLE ->
          throw problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.TRANSFER_RECOMMENDATION_STALE,
              "The recommendation no longer applies to this Referral revision and destination.");
      case INDETERMINATE -> throw matchingUnavailable();
    }

    try {
      return transaction.execute(
          status -> {
            Instant now = clock.instant();
            store.configureBoundedReceiptWait();
            TransferStore.ReceiptClaim claim = store.claimReceipt(receiptKey, fingerprint, now);
            TransferJourneyCommandResult replay = replay(journeyId, claim);
            if (replay != null) {
              return replay;
            }
            TransferJourney journey =
                store.lockJourney(journeyId).orElseThrow(TransferNextRequestCommands::notFound);
            requireOriginTarget(context, journey);
            if (store.findJourneyClosure(journeyId).isPresent()) {
              throw problem(
                  HttpStatus.CONFLICT,
                  PlatformErrorCode.TRANSFER_STATE_CONFLICT,
                  "The TransferJourney has already been closed as unfulfilled.");
            }
            TransferRequestAttempt latest =
                store
                    .findLatestRequest(journeyId)
                    .orElseThrow(TransferNextRequestCommands::unavailable);
            requirePriorAttemptReconciled(latest);
            var deadline = properties.getRequestDeadline();
            if (deadline == null || deadline.isNegative() || deadline.isZero()) {
              throw problem(
                  HttpStatus.UNPROCESSABLE_ENTITY,
                  PlatformErrorCode.TRANSFER_EXPIRY_POLICY_UNCONFIGURED,
                  "A valid Transfer request response-deadline policy is not configured.");
            }
            int attemptNumber = Math.addExact(latest.attemptNumber(), 1);
            TransferRequestAttempt attempt =
                new TransferRequestAttempt(
                    InternalId.generate(),
                    journey.id(),
                    attemptNumber,
                    journey.referralId(),
                    referralRevision,
                    recommendationId,
                    candidateFacility,
                    TransferRequestAttempt.Status.PENDING,
                    context.actor(),
                    now,
                    now.plus(deadline),
                    Optional.empty(),
                    Optional.empty(),
                    1);
            if (!store.insertNextRequest(journey.id(), journey.version(), attempt)) {
              throw activeAttemptExists();
            }
            auditMutation(context, journeyId);
            store.completeReceipt(
                receiptKey, HttpStatus.CREATED.value(), journey.id(), attemptNumber, now);
            TransferJourney advancedJourney =
                new TransferJourney(
                    journey.id(),
                    journey.referralId(),
                    journey.originOrganization(),
                    journey.originFacility(),
                    journey.createdBy(),
                    journey.createdAt(),
                    Math.addExact(journey.version(), 1));
            return new TransferJourneyCommandResult(advancedJourney, attempt, false);
          });
    } catch (CannotAcquireLockException busy) {
      throw problem(HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
    } catch (DuplicateKeyException conflict) {
      throw activeAttemptExists();
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  private void requirePriorAttemptReconciled(TransferRequestAttempt latest) {
    if (latest.status() == TransferRequestAttempt.Status.PENDING) {
      throw activeAttemptExists();
    }
    if (latest.status() != TransferRequestAttempt.Status.ACCEPTED) {
      return;
    }
    TransferStore.TransferAttemptState state =
        store.findAttemptState(latest.id()).orElseThrow(TransferNextRequestCommands::unavailable);
    if (state.transferStatus().orElse(null) != CoordinatedTransfer.Status.CANCELLED) {
      throw activeAttemptExists();
    }
    if (state.reservationOutcome().filter(value -> !reservationTerminal(value)).isPresent()) {
      throw activeAttemptExists();
    }
  }

  private static boolean reservationTerminal(
      org.ayuvahak.transfer.domain.CapacityReservationIntent.Outcome outcome) {
    return switch (outcome) {
      case CONFLICT, INVALID, UNCONFIGURED, RELEASED, EXPIRED, NOT_RESERVED -> true;
      case PENDING, RESERVED, UNKNOWN, UNAVAILABLE, AT_RISK -> false;
    };
  }

  private TransferJourneyCommandResult replay(
      InternalId journeyId, TransferStore.ReceiptClaim claim) {
    return switch (claim.state()) {
      case OWNER -> null;
      case KEY_REUSED ->
          throw problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
              "The Idempotency-Key was already used for a different Transfer command.");
      case IN_PROGRESS ->
          throw problem(
              HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
      case REPLAY -> {
        int attemptNumber = Math.toIntExact(claim.resultVersion().orElseThrow());
        TransferJourney journey =
            store.findJourney(journeyId).orElseThrow(TransferNextRequestCommands::unavailable);
        TransferRequestAttempt attempt =
            store
                .findRequestAttempt(journeyId, attemptNumber)
                .orElseThrow(TransferNextRequestCommands::unavailable);
        yield new TransferJourneyCommandResult(journey, attempt, true);
      }
    };
  }

  private void requireAuthority(ExecutionContext context, InternalId journeyId) {
    AuthorizationDecision decision =
        authorization.getObject().evaluate(context, TransferAuthorities.request().code());
    if (decision.isAllowed()) {
      return;
    }
    boolean unavailable =
        decision.denialReason().orElse(null)
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
    recordSecurityOutcome(context, journeyId, unavailable);
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        unavailable
            ? "Transfer authorization is temporarily unavailable."
            : "The actor is not authorized to initiate a Transfer request in this scope.");
  }

  private void auditMutation(ExecutionContext context, InternalId journeyId) {
    try {
      auditWriter
          .getObject()
          .recordMutation(
              new AuditEvidence(
                  AuditActionKind.PROTECTED_MUTATION,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  "TRANSFER_REQUEST_REROUTE",
                  "TransferJourney",
                  Optional.of(journeyId),
                  clock.instant(),
                  AuditOutcome.SUCCEEDED,
                  Optional.empty(),
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.ALLOWED,
                  context.correlationId(),
                  Optional.of(targetContext(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The next request and its required audit evidence could not be committed.");
    }
  }

  private void recordSecurityOutcome(
      ExecutionContext context, InternalId journeyId, boolean authorizationUnavailable) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  "TRANSFER_REQUEST_REROUTE_DENIED",
                  "TransferJourney",
                  Optional.of(journeyId),
                  clock.instant(),
                  AuditOutcome.DENIED,
                  Optional.of(
                      authorizationUnavailable
                          ? "AUTHORIZATION_STORE_UNAVAILABLE"
                          : "AUTHORIZATION_DENIED"),
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.DENIED,
                  context.correlationId()));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The request remains denied; its required audit record is unavailable.");
    }
  }

  private static void requireOriginTarget(ExecutionContext context, TransferJourney journey) {
    if (!journey.originOrganization().equals(context.scope().organization().orElseThrow())
        || (context.scope().facility().isPresent()
            && !journey.originFacility().equals(context.scope().facility()))) {
      throw notFound();
    }
  }

  private static void requireOriginScope(ExecutionScope scope) {
    if (scope.kind() == ExecutionScope.Kind.PLATFORM
        || scope.organization().isEmpty()
        || !DIRECTORY_ISSUER.equals(scope.organization().orElseThrow().issuer().value())
        || scope
            .facility()
            .filter(reference -> !DIRECTORY_ISSUER.equals(reference.issuer().value()))
            .isPresent()) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Transfer request creation requires a validated Directory origin scope.");
    }
    InternalId.parse(scope.organization().orElseThrow().value());
    scope.facility().ifPresent(reference -> InternalId.parse(reference.value()));
  }

  private static void requireDirectoryFacility(ExternalReference facility) {
    if (!DIRECTORY_ISSUER.equals(facility.issuer().value())
        || !"Facility".equals(facility.resourceType())) {
      throw invalid("A Directory candidate Facility is required.");
    }
    InternalId.parse(facility.value());
  }

  private static CommandReceiptKey commandKey(
      ExecutionContext context, InternalId journeyId, String idempotencyKey) {
    try {
      return CommandReceiptKey.from(
          context, "transfer.journey.reroute", "journeys/" + journeyId, idempotencyKey);
    } catch (IllegalArgumentException invalid) {
      throw invalid("A valid Idempotency-Key is required for this Transfer command.");
    }
  }

  private static AuditTargetContext targetContext(ExecutionScope scope) {
    return new AuditTargetContext(scope.kind(), scope.organization(), scope.facility());
  }

  private static AuditAuthenticationEvidence authenticationEvidence(
      AuthenticationMechanism mechanism) {
    return switch (mechanism) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }

  private static PlatformProblemException activeAttemptExists() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.TRANSFER_ACTIVE_ATTEMPT_EXISTS,
        "The prior Transfer request, movement, or Capacity hold is not terminal and reconciled.");
  }

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.TRANSFER_REQUEST_NOT_FOUND,
        "The TransferJourney was not found in the authorized scope.");
  }

  private static PlatformProblemException matchingUnavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSFER_DESTINATION_NOT_ELIGIBLE,
        "Recommendation applicability could not be established safely.");
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.INTERNAL_ERROR,
        "Transfer could not complete the operation safely.");
  }

  private static PlatformProblemException invalid(String detail) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.TRANSFER_REQUEST_INVALID, detail);
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
