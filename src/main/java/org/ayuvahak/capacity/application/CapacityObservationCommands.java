package org.ayuvahak.capacity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.capacity.domain.CapacityAuthorities;
import org.ayuvahak.capacity.domain.CapacityDimension;
import org.ayuvahak.capacity.domain.CapacityObservation;
import org.ayuvahak.capacity.domain.CapacityObservationKind;
import org.ayuvahak.capacity.domain.CapacityPolicy;
import org.ayuvahak.capacity.domain.CapacityReportingMethod;
import org.ayuvahak.capacity.domain.CapacityValueKind;
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
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
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

/** Capacity owner commands for immutable evidence receipt, correction, and retraction. */
@Service
@Lazy
public class CapacityObservationCommands {

  private static final String COMMAND_BUSY_DETAIL =
      "A matching Capacity command is still being processed; retry with the same idempotency key.";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";

  private final CapacityObservationStore observations;
  private final CapacityPolicyStore configuration;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public CapacityObservationCommands(
      @Lazy CapacityObservationStore observations,
      @Lazy CapacityPolicyStore configuration,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock) {
    this.observations = Objects.requireNonNull(observations, "Capacity observation store");
    this.configuration = Objects.requireNonNull(configuration, "Capacity configuration store");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public CapacityObservationCommandResult submit(
      ExecutionContext context,
      CapacityObservationInput input,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    return execute(context, input, idempotencyKey, fingerprint, "capacity.observation.submit");
  }

  public CapacityObservationCommandResult correct(
      ExecutionContext context,
      InternalId supersededId,
      CapacityObservationInput input,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(supersededId, "superseded observation");
    if (input.supersedesObservationId().isEmpty()
        || !input.supersedesObservationId().orElseThrow().equals(supersededId)) {
      throw invalid("The correction must identify the observation in its route.");
    }
    return execute(context, input, idempotencyKey, fingerprint, "capacity.observation.correct");
  }

  public void recordInvalidScope(ActorReference actor, CorrelationId correlationId) {
    recordSecurityOutcome(
        actor,
        ExecutionScope.platform(),
        correlationId,
        "CAPACITY_SCOPE_REJECTED",
        "INVALID_SCOPE",
        Optional.empty());
  }

  private CapacityObservationCommandResult execute(
      ExecutionContext context,
      CapacityObservationInput input,
      String idempotencyKey,
      RequestFingerprint fingerprint,
      String operation) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(input, "observation input");
    Objects.requireNonNull(fingerprint, "request fingerprint");
    requireFacilityScope(context);
    requireReporter(
        context,
        input.kind() == CapacityObservationKind.RETRACTION
            ? "CAPACITY_OBSERVATION_RETRACT"
            : input.supersedesObservationId().isPresent()
                ? "CAPACITY_OBSERVATION_CORRECT"
                : "CAPACITY_OBSERVATION_INGEST");

    try {
      return transaction.execute(
          status -> persist(context, input, idempotencyKey, fingerprint, operation));
    } catch (CapacitySourceIdentityConflictException conflict) {
      recordSourceConflict(context, conflict.existingObservationId());
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.CAPACITY_OBSERVATION_CONFLICT,
          "The source observation identifier is already attached to different evidence.");
    } catch (CannotAcquireLockException busy) {
      throw problem(
          HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, COMMAND_BUSY_DETAIL);
    } catch (DuplicateKeyException conflict) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.CAPACITY_OBSERVATION_CONFLICT,
          "The observation conflicts with a previously recorded Capacity fact.");
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The required Capacity audit record could not be committed.");
    } catch (DataAccessException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.CAPACITY_UNAVAILABLE,
          "The Capacity observation could not be committed safely.");
    }
  }

  private CapacityObservationCommandResult persist(
      ExecutionContext context,
      CapacityObservationInput input,
      String idempotencyKey,
      RequestFingerprint fingerprint,
      String operation) {
    Instant receivedAt = clock.instant();
    CommandReceiptKey receiptKey = commandKey(context, operation, input, idempotencyKey);
    configuration.configureBoundedReceiptWait();
    CapacityPolicyStore.ReceiptClaim claim =
        configuration.claimReceipt(receiptKey, fingerprint, receivedAt);
    if (claim.state() == CapacityPolicyStore.ReceiptState.KEY_REUSED) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
          "The idempotency key was already used for a different Capacity command.");
    }
    if (claim.state() == CapacityPolicyStore.ReceiptState.IN_PROGRESS) {
      throw problem(
          HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, COMMAND_BUSY_DETAIL);
    }
    if (claim.state() == CapacityPolicyStore.ReceiptState.REPLAY) {
      CapacityObservation replay =
          observations
              .findBySourceIdentity(input.sourceCode(), input.sourceObservation())
              .filter(item -> item.id().equals(claim.resultReference().orElseThrow()))
              .orElseGet(
                  () ->
                      observations
                          .lockObservation(claim.resultReference().orElseThrow())
                          .orElseThrow(
                              () ->
                                  new IllegalStateException(
                                      "Capacity command receipt target is missing")));
      return result(replay, true, false, claim.responseStatus().orElseThrow());
    }

    ExternalReference facility = context.scope().facility().orElseThrow();
    observations.lockSourceIdentity(input.sourceCode(), input.sourceObservation());
    Optional<CapacityObservation> sameSource =
        observations.findBySourceIdentity(input.sourceCode(), input.sourceObservation());
    if (sameSource.isPresent()) {
      CapacityObservation existing = sameSource.orElseThrow();
      if (!existing.hasSameSourceFact(
          facility,
          input.dimensionCode(),
          input.kind(),
          input.valueKind(),
          input.quantity(),
          input.state(),
          input.unit(),
          input.sourceCode(),
          input.sourceObservation(),
          input.reportingMethod(),
          input.observedAt(),
          input.validUntil(),
          input.reasonCode(),
          input.supersedesObservationId())) {
        throw new CapacitySourceIdentityConflictException(existing.id());
      }
      configuration.completeReceipt(
          receiptKey, HttpStatus.OK.value(), existing.id(), 1, receivedAt);
      return result(existing, false, true, HttpStatus.OK.value());
    }

    CapacityDimension dimension =
        configuration
            .currentDimension(input.dimensionCode())
            .orElseThrow(
                () ->
                    problem(
                        HttpStatus.NOT_FOUND,
                        PlatformErrorCode.CAPACITY_DIMENSION_NOT_FOUND,
                        "The Capacity dimension does not exist."));
    CapacityPolicy policy =
        configuration
            .currentPolicy(input.dimensionCode())
            .filter(candidate -> candidate.dimensionVersion() == dimension.version())
            .orElseThrow(
                () ->
                    problem(
                        HttpStatus.CONFLICT,
                        PlatformErrorCode.CAPACITY_POLICY_UNCONFIGURED,
                        "The Capacity dimension has no current compatible source policy."));
    if (policy.sources().stream()
        .noneMatch(source -> source.sourceCode().equals(input.sourceCode()))) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.CAPACITY_SOURCE_UNTRUSTED,
          "The configured Capacity policy does not permit this source.");
    }

    validateValue(dimension, input);
    Optional<Instant> effectiveUntil = effectiveValidity(policy, input, receivedAt);
    boolean futureSkewed = isFutureSkewed(policy, input.observedAt(), receivedAt);
    CapacityObservation proposed =
        new CapacityObservation(
            InternalId.generate(),
            facility,
            dimension.code(),
            dimension.version(),
            input.kind(),
            input.valueKind(),
            input.quantity(),
            input.state(),
            input.unit(),
            input.sourceCode(),
            input.sourceObservation(),
            input.reportingMethod(),
            context.actor(),
            input.observedAt(),
            receivedAt,
            input.validUntil(),
            effectiveUntil,
            policy.version(),
            futureSkewed,
            input.reasonCode(),
            input.supersedesObservationId());

    if (input.supersedesObservationId().isPresent()) {
      appendCorrection(context, input, proposed);
    } else {
      observations.append(proposed);
      auditMutation(context, proposed, actionFor(input), AuditOutcome.SUCCEEDED);
    }
    configuration.completeReceipt(
        receiptKey, HttpStatus.CREATED.value(), proposed.id(), 1, receivedAt);
    return result(proposed, false, false, HttpStatus.CREATED.value());
  }

  private void appendCorrection(
      ExecutionContext context, CapacityObservationInput input, CapacityObservation proposed) {
    InternalId targetId = input.supersedesObservationId().orElseThrow();
    CapacityObservation target =
        observations
            .lockObservation(targetId)
            .orElseThrow(
                () ->
                    problem(
                        HttpStatus.NOT_FOUND,
                        PlatformErrorCode.NOT_FOUND,
                        "The Capacity observation does not exist."));
    if (!target.facility().equals(context.scope().facility().orElseThrow())
        || !target.dimensionCode().equals(input.dimensionCode())
        || target.kind() != CapacityObservationKind.VALUE
        || observations.isSuperseded(targetId)) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.CAPACITY_OBSERVATION_CONFLICT,
          "The target is outside this Facility scope or cannot be superseded again.");
    }
    if (input.reportingMethod() == CapacityReportingMethod.MANUAL_ENTRY) {
      throw invalid("A correction must state a correction-specific reporting method.");
    }
    observations.appendCorrection(
        proposed,
        targetId,
        input.kind() == CapacityObservationKind.RETRACTION ? "RETRACTION" : "CORRECTION");
    auditMutation(
        context,
        proposed,
        input.kind() == CapacityObservationKind.RETRACTION
            ? "CAPACITY_OBSERVATION_RETRACT"
            : "CAPACITY_OBSERVATION_CORRECT",
        AuditOutcome.SUCCEEDED);
  }

  private void validateValue(CapacityDimension dimension, CapacityObservationInput input) {
    boolean correctionMethod = input.reportingMethod() == CapacityReportingMethod.MANUAL_CORRECTION;
    boolean requiresSupersededFact =
        input.kind() == CapacityObservationKind.RETRACTION || correctionMethod;
    if ((requiresSupersededFact && input.supersedesObservationId().isEmpty())
        || (input.supersedesObservationId().isPresent()
            && input.reportingMethod() == CapacityReportingMethod.MANUAL_ENTRY)) {
      throw invalid("A correction or retraction must reference its superseded observation.");
    }
    if (input.kind() == CapacityObservationKind.RETRACTION) {
      if (input.valueKind().isPresent()
          || input.quantity().isPresent()
          || input.state().isPresent()
          || input.unit().isPresent()
          || input.validUntil().isPresent()) {
        throw invalid("A retraction must not contain a capacity value or validity time.");
      }
      return;
    }
    if (input.valueKind().isEmpty() || input.valueKind().orElseThrow() != dimension.valueKind()) {
      throw invalid("The observation value kind does not match the configured dimension.");
    }
    if (dimension.valueKind() == CapacityValueKind.QUANTITY) {
      if (input.quantity().isEmpty()
          || input.state().isPresent()
          || input.unit().isEmpty()
          || !dimension.unit().equals(input.unit().orElseThrow())) {
        throw problem(
            HttpStatus.BAD_REQUEST,
            PlatformErrorCode.CAPACITY_UNIT_MISMATCH,
            "The quantity and unit do not match the configured dimension.");
      }
      var quantity = input.quantity().orElseThrow();
      if (quantity.signum() < 0
          || (dimension.minimumValue() != null && quantity.compareTo(dimension.minimumValue()) < 0)
          || (dimension.maximumValue() != null
              && quantity.compareTo(dimension.maximumValue()) > 0)) {
        throw invalid("The observed quantity is outside the configured dimension range.");
      }
    } else if (input.quantity().isPresent()
        || input.unit().isPresent()
        || input.state().isEmpty()
        || !dimension.allowedStates().contains(input.state().orElseThrow())) {
      throw invalid("The observed state is not allowed for the configured dimension.");
    }
    input
        .validUntil()
        .ifPresent(
            expiry -> {
              if (!expiry.isAfter(input.observedAt())) {
                throw invalid("validUntil must be later than observedAt.");
              }
            });
  }

  private Optional<Instant> effectiveValidity(
      CapacityPolicy policy, CapacityObservationInput input, Instant receivedAt) {
    if (input.kind() == CapacityObservationKind.RETRACTION) {
      return Optional.empty();
    }
    if (input.validUntil().isPresent()) {
      return input.validUntil();
    }
    if (policy.defaultValiditySeconds() == null) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.CAPACITY_POLICY_UNCONFIGURED,
          "An observation validity time is required because the policy has no default validity.");
    }
    try {
      return Optional.of(input.observedAt().plusSeconds(policy.defaultValiditySeconds()));
    } catch (RuntimeException invalid) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.CAPACITY_VALUE_INVALID,
          "The observation validity time is outside the supported range.");
    }
  }

  private static boolean isFutureSkewed(
      CapacityPolicy policy, Instant observedAt, Instant receivedAt) {
    try {
      return observedAt.isAfter(receivedAt.plusSeconds(policy.futureSkewToleranceSeconds()));
    } catch (RuntimeException overflow) {
      return false;
    }
  }

  private void requireFacilityScope(ExecutionContext context) {
    ExecutionScope scope = context.scope();
    if (scope.kind() != ExecutionScope.Kind.FACILITY
        || scope.organization().isEmpty()
        || scope.facility().isEmpty()
        || !DIRECTORY_ISSUER.equals(scope.facility().orElseThrow().issuer().value())
        || !DIRECTORY_ISSUER.equals(scope.organization().orElseThrow().issuer().value())) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Capacity observation commands require a validated Directory Facility scope.");
    }
  }

  private void requireReporter(ExecutionContext context, String actionCode) {
    AuthorizationDecision decision =
        authorization.getObject().evaluate(context, CapacityAuthorities.reporting().code());
    if (decision.isAllowed()) {
      return;
    }
    recordSecurityOutcome(context, actionCode, "AUTHORIZATION_DENIED", Optional.empty());
    if (decision.denialReason().orElse(null)
        == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
          "Authorization could not be evaluated safely.");
    }
    throw problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated actor is not authorized to report Capacity evidence.");
  }

  private void recordSourceConflict(ExecutionContext context, InternalId existingObservationId) {
    recordSecurityOutcome(
        context,
        "CAPACITY_OBSERVATION_SOURCE_CONFLICT",
        "SOURCE_ID_REUSED",
        Optional.of(existingObservationId),
        AuditOutcome.FAILED,
        AuditAuthorizationOutcome.ALLOWED);
  }

  private void recordSecurityOutcome(
      ExecutionContext context,
      String actionCode,
      String reasonCode,
      Optional<InternalId> targetId) {
    recordSecurityOutcome(
        context,
        actionCode,
        reasonCode,
        targetId,
        AuditOutcome.DENIED,
        AuditAuthorizationOutcome.DENIED);
  }

  private void recordSecurityOutcome(
      ExecutionContext context,
      String actionCode,
      String reasonCode,
      Optional<InternalId> targetId,
      AuditOutcome outcome,
      AuditAuthorizationOutcome authorizationOutcome) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditActionKind.SECURITY_OUTCOME,
                  actionCode,
                  targetId,
                  reasonCode,
                  outcome,
                  authorizationOutcome));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The request remains denied; its required audit record is unavailable.");
    }
  }

  private void recordSecurityOutcome(
      ActorReference actor,
      ExecutionScope scope,
      CorrelationId correlationId,
      String actionCode,
      String reasonCode,
      Optional<InternalId> targetId) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(actor),
                  Optional.of(scope),
                  actionCode,
                  "ExecutionScope",
                  targetId,
                  clock.instant(),
                  AuditOutcome.DENIED,
                  Optional.of(reasonCode),
                  authenticationEvidence(
                      actor.kind() == ActorKind.HUMAN
                          ? AuthenticationMechanism.HUMAN_ACCESS_TOKEN
                          : AuthenticationMechanism.SERVICE_CLIENT_CREDENTIALS),
                  AuditAuthorizationOutcome.NOT_EVALUATED,
                  correlationId));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The request remains denied; its required audit record is unavailable.");
    }
  }

  private void auditMutation(
      ExecutionContext context,
      CapacityObservation observation,
      String actionCode,
      AuditOutcome outcome) {
    try {
      auditWriter
          .getObject()
          .recordMutation(
              evidence(
                  context,
                  AuditActionKind.PROTECTED_MUTATION,
                  actionCode,
                  Optional.of(observation.id()),
                  observation.reasonCode(),
                  outcome,
                  AuditAuthorizationOutcome.ALLOWED));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw unavailable;
    }
  }

  private AuditEvidence evidence(
      ExecutionContext context,
      AuditActionKind kind,
      String actionCode,
      Optional<InternalId> targetId,
      String reasonCode,
      AuditOutcome outcome,
      AuditAuthorizationOutcome authorizationOutcome) {
    return new AuditEvidence(
        kind,
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        actionCode,
        "CapacityObservation",
        targetId,
        clock.instant(),
        outcome,
        Optional.of(reasonCode),
        authenticationEvidence(context.authenticationMechanism()),
        authorizationOutcome,
        context.correlationId());
  }

  private CommandReceiptKey commandKey(
      ExecutionContext context,
      String operation,
      CapacityObservationInput input,
      String idempotencyKey) {
    String target =
        input.sourceCode()
            + ":"
            + input.sourceObservation().issuer().value()
            + ":"
            + input.sourceObservation().value();
    try {
      return CommandReceiptKey.from(context, operation, target, idempotencyKey);
    } catch (IllegalArgumentException invalid) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.CAPACITY_VALUE_INVALID,
          "A valid Idempotency-Key is required for this Capacity command.");
    }
  }

  private static String actionFor(CapacityObservationInput input) {
    return input.kind() == CapacityObservationKind.RETRACTION
        ? "CAPACITY_OBSERVATION_RETRACT"
        : input.supersedesObservationId().isPresent()
            ? "CAPACITY_OBSERVATION_CORRECT"
            : "CAPACITY_OBSERVATION_INGEST";
  }

  private static CapacityObservationCommandResult result(
      CapacityObservation observation,
      boolean commandReplayed,
      boolean sourceReplayed,
      int responseStatus) {
    return new CapacityObservationCommandResult(
        observation.id(),
        observation.dimensionCode(),
        observation.kind(),
        observation.receivedAt(),
        observation.validUntil(),
        observation.futureSkewed(),
        commandReplayed,
        sourceReplayed,
        responseStatus);
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
    return problem(HttpStatus.BAD_REQUEST, PlatformErrorCode.CAPACITY_VALUE_INVALID, detail);
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
