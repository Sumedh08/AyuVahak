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
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.adapter.configuration.TransferProperties;
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

/** Transfer-owned command that creates a Journey and its first currently applicable request. */
@Service
@Lazy
public final class TransferJourneyCommands {

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

  public TransferJourneyCommands(
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

  public TransferJourneyCommandResult createFirstRequest(
      ExecutionContext context,
      InternalId referralId,
      long referralRevision,
      InternalId recommendationId,
      ExternalReference candidateFacility,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(referralId, "Referral id");
    Objects.requireNonNull(recommendationId, "recommendation id");
    Objects.requireNonNull(candidateFacility, "candidate Facility");
    Objects.requireNonNull(fingerprint, "request fingerprint");
    requireOriginScope(context.scope());
    requireDirectoryFacility(candidateFacility);
    if (referralRevision < 1) {
      throw invalid("A positive submitted Referral revision is required.");
    }
    requireAuthority(context);

    var deadline = properties.getRequestDeadline();
    if (deadline == null || deadline.isNegative() || deadline.isZero()) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSFER_EXPIRY_POLICY_UNCONFIGURED,
          "A valid Transfer request response-deadline policy is not configured.");
    }

    CommandReceiptKey receiptKey = commandKey(context, referralId, idempotencyKey);
    Optional<TransferStore.ReceiptClaim> existing;
    try {
      existing = store.findReceipt(receiptKey, fingerprint);
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
    if (existing.isPresent()) {
      TransferJourneyCommandResult replay = replay(existing.orElseThrow());
      if (replay != null) {
        return replay;
      }
    }

    RecommendationApplicabilityResult applicability;
    try {
      applicability =
          matching.revalidate(context, recommendationId, candidateFacility, referralRevision);
    } catch (DataAccessException unavailable) {
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

    return execute(
        () -> {
          Instant now = clock.instant();
          store.configureBoundedReceiptWait();
          TransferStore.ReceiptClaim claim = store.claimReceipt(receiptKey, fingerprint, now);
          TransferJourneyCommandResult replay = replay(claim);
          if (replay != null) {
            return replay;
          }
          if (store.findJourneyByReferral(referralId).isPresent()) {
            throw problem(
                HttpStatus.CONFLICT,
                PlatformErrorCode.TRANSFER_ACTIVE_ATTEMPT_EXISTS,
                "A TransferJourney already exists for this Referral.");
          }
          InternalId journeyId = InternalId.generate();
          TransferJourney journey =
              new TransferJourney(
                  journeyId,
                  referralId,
                  context.scope().organization().orElseThrow(),
                  context.scope().facility(),
                  context.actor(),
                  now,
                  1);
          TransferRequestAttempt attempt =
              new TransferRequestAttempt(
                  InternalId.generate(),
                  journeyId,
                  1,
                  referralId,
                  referralRevision,
                  recommendationId,
                  candidateFacility,
                  TransferRequestAttempt.Status.PENDING,
                  context.actor(),
                  now,
                  now.plus(deadline),
                  1);
          store.insertFirstRequest(journey, attempt);
          auditMutation(context, journey);
          store.completeReceipt(
              receiptKey, HttpStatus.CREATED.value(), journey.id(), journey.version(), now);
          return new TransferJourneyCommandResult(journey, attempt, false);
        });
  }

  private TransferJourneyCommandResult replay(TransferStore.ReceiptClaim claim) {
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
        InternalId journeyId = claim.resultReference().orElseThrow();
        TransferJourney journey =
            store.findJourney(journeyId).orElseThrow(TransferJourneyCommands::unavailable);
        TransferRequestAttempt attempt =
            store.findFirstRequest(journeyId).orElseThrow(TransferJourneyCommands::unavailable);
        yield new TransferJourneyCommandResult(journey, attempt, true);
      }
    };
  }

  private void requireAuthority(ExecutionContext context) {
    AuthorizationDecision decision =
        authorization.getObject().evaluate(context, TransferAuthorities.request().code());
    if (decision.isAllowed()) {
      return;
    }
    boolean unavailable =
        decision.denialReason().orElseThrow()
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
    recordDenial(context, unavailable ? "AUTHORIZATION_STORE_UNAVAILABLE" : "AUTHORIZATION_DENIED");
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        unavailable
            ? "Transfer authorization is temporarily unavailable."
            : "The actor is not authorized to initiate a Transfer request in this scope.");
  }

  private void auditMutation(ExecutionContext context, TransferJourney journey) {
    try {
      auditWriter
          .getObject()
          .recordMutation(
              new AuditEvidence(
                  AuditActionKind.PROTECTED_MUTATION,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  "TRANSFER_REQUEST_CREATE",
                  "TransferJourney",
                  Optional.of(journey.id()),
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
          "The TransferJourney and its required audit evidence could not be committed.");
    }
  }

  private void recordDenial(ExecutionContext context, String reason) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  "TRANSFER_REQUEST_CREATE_DENIED",
                  "TransferJourney",
                  Optional.empty(),
                  clock.instant(),
                  AuditOutcome.DENIED,
                  Optional.of(reason),
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

  private CommandReceiptKey commandKey(
      ExecutionContext context, InternalId referralId, String idempotencyKey) {
    try {
      return CommandReceiptKey.from(
          context, "transfer.journey.create", "referrals/" + referralId, idempotencyKey);
    } catch (IllegalArgumentException invalid) {
      throw invalid("A valid Idempotency-Key is required for this Transfer command.");
    }
  }

  private <T> T execute(java.util.function.Supplier<T> operation) {
    try {
      return transaction.execute(status -> operation.get());
    } catch (CannotAcquireLockException busy) {
      throw problem(HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
    } catch (DuplicateKeyException conflict) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSFER_ACTIVE_ATTEMPT_EXISTS,
          "A TransferJourney already exists for this Referral.");
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private static void requireOriginScope(ExecutionScope scope) {
    if (scope.kind() == ExecutionScope.Kind.PLATFORM
        || scope.organization().isEmpty()
        || scope.organization().orElseThrow().issuer().equals(new IssuerId(DIRECTORY_ISSUER))
            == false
        || scope
            .facility()
            .filter(reference -> !DIRECTORY_ISSUER.equals(reference.issuer().value()))
            .isPresent()) {
      throw invalid("Transfer request creation requires a validated Directory origin scope.");
    }
    InternalId.parse(scope.organization().orElseThrow().value());
    scope.facility().ifPresent(reference -> InternalId.parse(reference.value()));
  }

  private static void requireDirectoryFacility(ExternalReference facility) {
    if (!DIRECTORY_ISSUER.equals(facility.issuer().value())
        || !"Facility".equals(facility.resourceType())) {
      throw invalid("The selected destination must be a Directory Facility reference.");
    }
    InternalId.parse(facility.value());
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

  private static PlatformProblemException invalid(String detail) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.TRANSFER_REQUEST_INVALID, detail);
  }

  private static PlatformProblemException matchingUnavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSFER_MATCHING_UNAVAILABLE,
        "Current recommendation applicability could not be established safely.");
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSFER_UNAVAILABLE,
        "The Transfer request could not be committed safely.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
