package org.ayuvahak.capacity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.capacity.domain.CapacityAuthorities;
import org.ayuvahak.capacity.domain.CapacityDimensionReadiness;
import org.ayuvahak.capacity.domain.CapacityEvidenceProjection;
import org.ayuvahak.capacity.domain.CapacityEvidenceProjector;
import org.ayuvahak.capacity.domain.CapacityReadinessEvaluation;
import org.ayuvahak.capacity.domain.CapacityReadinessEvaluator;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Authorization and consistent query-time reconstruction of Capacity evidence. */
@Service
@Lazy
public class CapacityEvidenceQueries implements CapacityReadiness {

  private static final int MAX_HISTORY_SIZE = 100;
  private static final int MAX_DIMENSIONS_PER_QUERY = 50;

  private final CapacityEvidenceStore evidence;
  private final CapacityConfigurationLookup configuration;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate readTransaction;
  private final Clock clock;
  private final CapacityEvidenceProjector projector = new CapacityEvidenceProjector();
  private final CapacityReadinessEvaluator readinessEvaluator = new CapacityReadinessEvaluator();

  public CapacityEvidenceQueries(
      @Lazy CapacityEvidenceStore evidence,
      @Lazy CapacityConfigurationLookup configuration,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock) {
    this.evidence = Objects.requireNonNull(evidence, "Capacity evidence store");
    this.configuration = Objects.requireNonNull(configuration, "Capacity configuration lookup");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.clock = Objects.requireNonNull(clock, "audit clock");
    readTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    readTransaction.setReadOnly(true);
    readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  public List<CapacityEvidenceProjection> current(
      ExecutionContext context,
      ExternalReference facility,
      List<String> dimensionCodes,
      Optional<Instant> requestedAt) {
    requireFacility(context, facility);
    requireReviewer(context, "CAPACITY_CURRENT_EVIDENCE_READ", facility);
    List<String> dimensions = validateDimensions(dimensionCodes);
    Instant evaluationTime = requestedAt.orElseGet(clock::instant);
    if (evaluationTime.isAfter(clock.instant())) {
      throw invalid("Current evidence cannot be evaluated at a future instant.");
    }

    List<CapacityEvidenceProjection> result;
    try {
      result =
          Objects.requireNonNull(
              readTransaction.execute(
                  status ->
                      dimensions.stream()
                          .map(
                              code ->
                                  projector.project(
                                      facility,
                                      code,
                                      configuration.currentDimension(code),
                                      configuration.currentPolicy(code),
                                      evidence.findForProjection(facility, code),
                                      evaluationTime))
                          .toList()),
              "Capacity evidence transaction result");
    } catch (DataAccessException unavailable) {
      throw unavailable("Current Capacity evidence could not be read safely.");
    }
    recordRead(context, facility, "CAPACITY_CURRENT_EVIDENCE_READ");
    return result;
  }

  @Override
  public CapacityReadinessEvaluation evaluateOperationalReadiness(
      ExecutionContext context,
      ExternalReference facility,
      List<String> requiredDimensionCodes,
      Optional<Instant> requestedAt) {
    requireFacility(context, facility);
    requireReviewer(context, "CAPACITY_READINESS_EVALUATE", facility);
    List<String> dimensions = validateDimensions(requiredDimensionCodes);
    Instant evaluationTime = requestedAt.orElseGet(clock::instant);
    if (evaluationTime.isAfter(clock.instant())) {
      throw invalid("Capacity readiness cannot be evaluated at a future instant.");
    }

    List<CapacityDimensionReadiness> results;
    try {
      results =
          Objects.requireNonNull(
              readTransaction.execute(
                  status ->
                      dimensions.stream()
                          .map(
                              code -> {
                                var dimension = configuration.currentDimension(code);
                                var policy = configuration.currentPolicy(code);
                                var projection =
                                    projector.project(
                                        facility,
                                        code,
                                        dimension,
                                        policy,
                                        evidence.findForProjection(facility, code),
                                        evaluationTime);
                                return readinessEvaluator.evaluate(
                                    code, dimension, policy, projection);
                              })
                          .toList()),
              "Capacity readiness transaction result");
    } catch (DataAccessException unavailable) {
      throw unavailable("Capacity readiness evidence could not be evaluated safely.");
    }
    recordRead(context, facility, "CAPACITY_READINESS_EVALUATE");
    return new CapacityReadinessEvaluation(
        facility, evaluationTime, CapacityReadinessEvaluation.aggregate(results), results);
  }

  public CapacityObservationHistoryPage history(
      ExecutionContext context,
      ExternalReference facility,
      Optional<String> dimensionCode,
      Optional<String> sourceCode,
      Optional<Instant> observedFromInclusive,
      Optional<Instant> observedUntilExclusive,
      int page,
      int size) {
    requireFacility(context, facility);
    requireReviewer(context, "CAPACITY_OBSERVATION_HISTORY_READ", facility);
    if (page < 0 || size < 1 || size > MAX_HISTORY_SIZE) {
      throw invalid(
          "Observation history pagination must use page >= 0 and size between 1 and 100.");
    }
    if (page > Integer.MAX_VALUE / size) {
      throw invalid("The requested observation history page is outside the supported range.");
    }
    dimensionCode.ifPresent(
        code -> {
          if (!code.matches("[A-Z][A-Z0-9._-]{0,63}")) {
            throw invalid("The dimension filter is malformed.");
          }
        });
    sourceCode.ifPresent(
        code -> {
          if (!code.matches("[A-Za-z][A-Za-z0-9._:-]{0,127}")) {
            throw invalid("The source filter is malformed.");
          }
        });
    if (observedFromInclusive.isPresent()
        && observedUntilExclusive.isPresent()
        && !observedUntilExclusive.orElseThrow().isAfter(observedFromInclusive.orElseThrow())) {
      throw invalid("The exclusive history end must be later than the inclusive start.");
    }

    List<org.ayuvahak.capacity.domain.CapacityObservationFact> rows;
    try {
      rows =
          Objects.requireNonNull(
              readTransaction.execute(
                  status ->
                      evidence.findHistory(
                          facility,
                          dimensionCode,
                          sourceCode,
                          observedFromInclusive,
                          observedUntilExclusive,
                          size + 1,
                          (long) page * size)),
              "Capacity history transaction result");
    } catch (DataAccessException unavailable) {
      throw unavailable("Capacity observation history could not be read safely.");
    }
    boolean hasMore = rows.size() > size;
    List<org.ayuvahak.capacity.domain.CapacityObservationFact> items =
        hasMore ? rows.subList(0, size) : rows;
    recordRead(context, facility, "CAPACITY_OBSERVATION_HISTORY_READ");
    return new CapacityObservationHistoryPage(items, page, size, hasMore);
  }

  public void recordInvalidScope(ActorReference actor, CorrelationId correlationId) {
    recordEvidence(
        Optional.of(actor),
        Optional.empty(),
        AuditActionKind.SECURITY_OUTCOME,
        "CAPACITY_EVIDENCE_SCOPE_REJECTED",
        Optional.empty(),
        AuditOutcome.DENIED,
        AuditAuthorizationOutcome.NOT_EVALUATED,
        correlationId,
        actor.kind() == ActorKind.HUMAN
            ? AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN
            : AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN);
  }

  private void requireFacility(ExecutionContext context, ExternalReference facility) {
    if (context.scope().kind() != ExecutionScope.Kind.FACILITY
        || context.scope().facility().isEmpty()
        || !context.scope().facility().orElseThrow().equals(facility)) {
      recordSecurityOutcome(context, "CAPACITY_EVIDENCE_SCOPE_MISMATCH", facility);
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Capacity evidence queries require the matching validated Facility scope.");
    }
  }

  private void requireReviewer(
      ExecutionContext context, String actionCode, ExternalReference facility) {
    AuthorizationDecision decision =
        authorization.getObject().evaluate(context, CapacityAuthorities.evidenceReview().code());
    if (decision.isAllowed()) {
      return;
    }
    recordSecurityOutcome(context, actionCode, facility);
    if (decision.denialReason().orElse(null)
        == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
          "Capacity evidence access could not be authorized safely.");
    }
    throw problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated actor is not authorized to review Capacity evidence.");
  }

  private void recordRead(ExecutionContext context, ExternalReference facility, String actionCode) {
    recordEvidence(
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        AuditActionKind.SENSITIVE_READ,
        actionCode,
        targetId(facility),
        AuditOutcome.SUCCEEDED,
        AuditAuthorizationOutcome.ALLOWED,
        context.correlationId(),
        authenticationEvidence(context.authenticationMechanism()));
  }

  private void recordSecurityOutcome(
      ExecutionContext context, String actionCode, ExternalReference facility) {
    recordEvidence(
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        AuditActionKind.SECURITY_OUTCOME,
        actionCode,
        targetId(facility),
        AuditOutcome.DENIED,
        AuditAuthorizationOutcome.DENIED,
        context.correlationId(),
        authenticationEvidence(context.authenticationMechanism()));
  }

  private void recordEvidence(
      Optional<ActorReference> actor,
      Optional<ExecutionScope> scope,
      AuditActionKind kind,
      String actionCode,
      Optional<InternalId> targetId,
      AuditOutcome outcome,
      AuditAuthorizationOutcome authorizationOutcome,
      CorrelationId correlationId,
      AuditAuthenticationEvidence authentication) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  kind,
                  actor,
                  scope,
                  actionCode,
                  "Facility",
                  targetId,
                  clock.instant(),
                  outcome,
                  Optional.empty(),
                  authentication,
                  authorizationOutcome,
                  correlationId));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The evidence request outcome could not be recorded durably.");
    }
  }

  private static List<String> validateDimensions(List<String> dimensionCodes) {
    if (dimensionCodes == null
        || dimensionCodes.isEmpty()
        || dimensionCodes.size() > MAX_DIMENSIONS_PER_QUERY
        || dimensionCodes.stream().anyMatch(Objects::isNull)
        || dimensionCodes.stream().anyMatch(code -> !code.matches("[A-Z][A-Z0-9._-]{0,63}"))
        || dimensionCodes.stream().distinct().count() != dimensionCodes.size()) {
      throw invalid("Request between 1 and 50 distinct valid Capacity dimensions.");
    }
    return List.copyOf(dimensionCodes);
  }

  private static Optional<InternalId> targetId(ExternalReference facility) {
    try {
      return Optional.of(InternalId.parse(facility.value()));
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
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

  private static PlatformProblemException unavailable(String detail) {
    return problem(HttpStatus.SERVICE_UNAVAILABLE, PlatformErrorCode.CAPACITY_UNAVAILABLE, detail);
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
