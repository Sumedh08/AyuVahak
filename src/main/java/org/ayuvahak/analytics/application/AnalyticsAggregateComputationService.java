package org.ayuvahak.analytics.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.ayuvahak.analytics.domain.AggregateCompleteness;
import org.ayuvahak.analytics.domain.AggregateInput;
import org.ayuvahak.analytics.domain.AggregateSnapshot;
import org.ayuvahak.analytics.domain.AggregationFormula;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.analytics.domain.IndicatorDefinitionVersion;
import org.ayuvahak.analytics.domain.ReportingPeriod;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.SourceKind;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Computes immutable aggregates from authorized owner snapshots; it cannot mutate owner state. */
@Service
public final class AnalyticsAggregateComputationService {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";

  private final AnalyticsStore store;
  private final AnalyticsComputationOperationStore operations;
  private final OwnerReportingSnapshotReader ownerSnapshots;
  private final AuthorizationService authorization;
  private final ExecutionContextFactory contexts;
  private final Clock clock;

  public AnalyticsAggregateComputationService(
      AnalyticsStore store,
      AnalyticsComputationOperationStore operations,
      OwnerReportingSnapshotReader ownerSnapshots,
      AuthorizationService authorization,
      ExecutionContextFactory contexts,
      @Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "Analytics store");
    this.operations = Objects.requireNonNull(operations, "computation operation store");
    this.ownerSnapshots = Objects.requireNonNull(ownerSnapshots, "owner reporting reader");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.contexts = Objects.requireNonNull(contexts, "execution context factory");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Durably accepts an authorized computation request; owner reads occur on the polling worker. */
  public AnalyticsComputationOperation enqueue(
      ExecutionContext context, AnalyticsComputationRequest request) {
    Objects.requireNonNull(request, "computation request");
    ExternalReference organization = authorizedOrganization(context);
    authorizeComputation(context);
    Instant now = clock.instant();
    if (request.referralSourceCutoff().isAfter(now)) {
      throw new IllegalArgumentException("source cutoff cannot be in the future");
    }
    AnalyticsComputationOperation proposed =
        new AnalyticsComputationOperation(
            request.computationRequestId(),
            fingerprint(request, organization, context.actor()),
            request,
            organization,
            context.actor(),
            context.correlationId(),
            AnalyticsComputationOperation.State.PENDING,
            0,
            now,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            now,
            now);
    return operations.createOrReplay(proposed);
  }

  /**
   * Claims one durable request, rechecks its stored service grant, and records a recoverable
   * result.
   */
  public Optional<AnalyticsComputationOperation> processNext() {
    Instant now = clock.instant();
    Optional<AnalyticsComputationOperation> claimed =
        operations.claimNext(now, now.plus(Duration.ofMinutes(2)));
    if (claimed.isEmpty()) {
      return Optional.empty();
    }
    AnalyticsComputationOperation operation = claimed.orElseThrow();
    try {
      Optional<AggregateSnapshot> prior = store.findSnapshot(operation.requestId());
      if (prior.isPresent()) {
        return Optional.of(
            operations.complete(
                operation.requestId(),
                operation.attemptCount(),
                prior.orElseThrow().snapshotId(),
                clock.instant()));
      }
      ExecutionContext workerContext =
          contexts.forBackgroundWork(
              operation.requester(),
              RequestedExecutionScope.organization(operation.organization()),
              operation.correlationId(),
              Optional.empty());
      AggregateSnapshot snapshot = computeSnapshot(workerContext, operation.request());
      return Optional.of(
          operations.complete(
              operation.requestId(),
              operation.attemptCount(),
              snapshot.snapshotId(),
              clock.instant()));
    } catch (RuntimeException failure) {
      boolean permanent = failure instanceof AnalyticsComputationException;
      String failureClass = failureClass(failure);
      int maxAttempts = 8;
      long delaySeconds = Math.min(300, 1L << Math.min(operation.attemptCount(), 8));
      return Optional.of(
          operations.retry(
              operation.requestId(),
              operation.attemptCount(),
              failureClass,
              permanent ? 0 : maxAttempts,
              clock.instant().plusSeconds(delaySeconds),
              clock.instant()));
    }
  }

  private AggregateSnapshot computeSnapshot(
      ExecutionContext context, AnalyticsComputationRequest request) {
    Objects.requireNonNull(request, "computation request");
    ExternalReference organization = authorizedOrganization(context);
    authorizeComputation(context);
    if (request.referralSourceCutoff().isAfter(clock.instant())) {
      throw new IllegalArgumentException("source cutoff cannot be in the future");
    }

    IndicatorDefinitionVersion definition =
        store
            .findDefinition(request.indicatorKey(), request.indicatorVersion())
            .orElseThrow(
                () ->
                    new AnalyticsComputationException(
                        AnalyticsComputationException.Reason.DEFINITION_NOT_FOUND,
                        "The approved indicator definition is unavailable."));
    ReportingPeriod period =
        ReportingPeriod.resolve(
            request.startInclusive(), request.endExclusive(), definition.reportingTimeZone());
    if (!definition.governance().effectiveFor(period)) {
      throw new AnalyticsComputationException(
          AnalyticsComputationException.Reason.DEFINITION_NOT_EFFECTIVE,
          "The indicator definition is not effective for the requested period.");
    }
    if (!definition.governance().calendarAccepts(period)) {
      throw new AnalyticsComputationException(
          AnalyticsComputationException.Reason.REPORTING_PERIOD_INVALID,
          "The requested period does not match the indicator reporting calendar.");
    }
    OwnerReportingRequest ownerRequest =
        new OwnerReportingRequest(
            request.computationRequestId(), organization, period, request.referralSourceCutoff());

    List<OwnerMeasureSnapshot> ownerInputs =
        definition.sourceMeasures().stream()
            .map(measure -> ownerSnapshots.snapshot(context, measure, ownerRequest))
            .toList();
    for (int index = 0; index < ownerInputs.size(); index++) {
      validateOwnerInput(
          ownerInputs.get(index),
          definition.sourceMeasures().get(index),
          organization,
          period,
          ownerRequest);
    }

    List<AggregateInput> inputs =
        ownerInputs.stream()
            .map(OwnerMeasureSnapshot::toAggregateInput)
            .map(input -> applyFreshnessPolicy(input, definition))
            .toList();
    AggregateCompleteness completeness = completeness(inputs);
    OptionalLong value =
        completeness == AggregateCompleteness.COMPLETE
            ? OptionalLong.of(aggregate(definition, inputs))
            : OptionalLong.empty();
    return store.saveOrReplay(
        AggregateSnapshot.create(
            request.computationRequestId(),
            definition,
            organization,
            period,
            completeness,
            value,
            inputs,
            clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
  }

  private AggregateInput applyFreshnessPolicy(
      AggregateInput input, IndicatorDefinitionVersion definition) {
    Optional<Duration> maximumAge = definition.governance().maximumSourceAge();
    if (maximumAge.isEmpty() || input.completeness() != AggregateCompleteness.COMPLETE) {
      return input;
    }
    Instant generatedAt = input.generatedAt().orElseThrow();
    Instant oldestAllowed = clock.instant().minus(maximumAge.orElseThrow());
    if (!generatedAt.isBefore(oldestAllowed) && !generatedAt.isAfter(clock.instant())) {
      return input;
    }
    return new AggregateInput(
        input.measure(),
        input.sourceSnapshotReference(),
        input.sourceCutoff(),
        input.ownerContractVersion(),
        AggregateCompleteness.STALE_SOURCE,
        OptionalLong.empty(),
        input.generatedAt());
  }

  private void authorizeComputation(ExecutionContext context) {
    AuthorizationDecision decision =
        authorization.evaluate(context, AnalyticsAuthorities.aggregateComputation().code());
    if (!decision.isAllowed()) {
      throw new AnalyticsComputationException(
          AnalyticsComputationException.Reason.NOT_AUTHORIZED,
          "Analytics computation is not authorized.");
    }
  }

  private static String fingerprint(
      AnalyticsComputationRequest request,
      ExternalReference organization,
      ActorReference requester) {
    String canonical =
        String.join(
            "\n",
            request.indicatorKey(),
            Integer.toString(request.indicatorVersion()),
            request.startInclusive().toString(),
            request.endExclusive().toString(),
            request.referralSourceCutoff().toString(),
            organization.issuer().value(),
            organization.resourceType(),
            organization.value(),
            requester.identity().issuer().value(),
            requester.identity().value());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }

  private static String failureClass(RuntimeException failure) {
    if (failure instanceof AnalyticsComputationException computationFailure) {
      return "COMPUTATION_" + computationFailure.reason().name();
    }
    return "COMPUTATION_UNAVAILABLE";
  }

  private ExternalReference authorizedOrganization(ExecutionContext context) {
    if (context == null
        || context.actor().kind() != ActorKind.SERVICE
        || !(context.sourceKind() == SourceKind.INTEGRATION
            || context.sourceKind() == SourceKind.BACKGROUND_WORK
            || context.sourceKind() == SourceKind.SCHEDULED_WORK)
        || context.scope().kind() != ExecutionScope.Kind.ORGANIZATION) {
      throw notAuthorized();
    }
    ExternalReference organization = context.scope().organization().orElse(null);
    if (organization == null
        || !DIRECTORY_ISSUER.equals(organization.issuer().value())
        || !"Organization".equals(organization.resourceType())
        || !isCanonicalUuid(organization.value())) {
      throw notAuthorized();
    }
    return organization;
  }

  private void validateOwnerInput(
      OwnerMeasureSnapshot input,
      org.ayuvahak.analytics.domain.SourceMeasure expectedMeasure,
      ExternalReference organization,
      ReportingPeriod period,
      OwnerReportingRequest request) {
    if (input == null
        || !expectedMeasure.equals(input.measure())
        || !organization.equals(input.organization())
        || !period.startInclusiveUtc().equals(input.periodStart())
        || !period.endExclusiveUtc().equals(input.periodEnd())
        || (expectedMeasure.owner() == org.ayuvahak.analytics.domain.SourceMeasure.Owner.REFERRAL
            && input.sourceCutoff().isPresent()
            && !request.referralSourceCutoff().equals(input.sourceCutoff().orElseThrow()))) {
      throw new AnalyticsComputationException(
          AnalyticsComputationException.Reason.OWNER_CONTRACT_INVALID,
          "An owner reporting contract returned mismatched scope or period evidence.");
    }
  }

  private static boolean isCanonicalUuid(String value) {
    try {
      return UUID.fromString(value).toString().equalsIgnoreCase(value);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private static AggregateCompleteness completeness(List<AggregateInput> inputs) {
    if (inputs.stream().allMatch(input -> input.completeness() == AggregateCompleteness.COMPLETE)) {
      return AggregateCompleteness.COMPLETE;
    }
    if (inputs.stream()
        .allMatch(input -> input.completeness() == AggregateCompleteness.NOT_APPLICABLE)) {
      return AggregateCompleteness.NOT_APPLICABLE;
    }
    if (inputs.stream()
        .anyMatch(input -> input.completeness() == AggregateCompleteness.STALE_SOURCE)) {
      return AggregateCompleteness.STALE_SOURCE;
    }
    if (inputs.stream().noneMatch(input -> input.completeness() == AggregateCompleteness.COMPLETE)
        && inputs.stream()
            .anyMatch(input -> input.completeness() == AggregateCompleteness.MISSING_SOURCE)) {
      return AggregateCompleteness.MISSING_SOURCE;
    }
    return AggregateCompleteness.PARTIAL;
  }

  private static long aggregate(
      IndicatorDefinitionVersion definition, List<AggregateInput> inputs) {
    if (definition.formula() != AggregationFormula.SUM) {
      throw new IllegalStateException("unsupported Analytics formula");
    }
    long total = 0;
    try {
      for (AggregateInput input : inputs) {
        total = Math.addExact(total, input.value().orElseThrow());
      }
    } catch (ArithmeticException overflow) {
      throw new AnalyticsComputationException(
          AnalyticsComputationException.Reason.VALUE_OVERFLOW,
          "The aggregate result is outside the supported numeric range.",
          overflow);
    }
    if (total == 0 && !definition.zeroAllowed()) {
      throw new AnalyticsComputationException(
          AnalyticsComputationException.Reason.ZERO_NOT_ALLOWED,
          "The indicator definition does not permit a zero result.");
    }
    return total;
  }

  private static AnalyticsComputationException notAuthorized() {
    return new AnalyticsComputationException(
        AnalyticsComputationException.Reason.NOT_AUTHORIZED,
        "Analytics computation is not authorized.");
  }
}
