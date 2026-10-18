package org.ayuvahak.analytics.application;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
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
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.SourceKind;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Computes immutable aggregates from authorized owner snapshots; it cannot mutate owner state. */
@Service
public final class AnalyticsAggregateComputationService {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";

  private final AnalyticsStore store;
  private final OwnerReportingSnapshotReader ownerSnapshots;
  private final AuthorizationService authorization;
  private final Clock clock;

  public AnalyticsAggregateComputationService(
      AnalyticsStore store,
      OwnerReportingSnapshotReader ownerSnapshots,
      AuthorizationService authorization,
      @Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "Analytics store");
    this.ownerSnapshots = Objects.requireNonNull(ownerSnapshots, "owner reporting reader");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public AggregateSnapshot compute(ExecutionContext context, AnalyticsComputationRequest request) {
    Objects.requireNonNull(request, "computation request");
    ExternalReference organization = authorizedOrganization(context);
    AuthorizationDecision decision =
        authorization.evaluate(context, AnalyticsAuthorities.aggregateComputation().code());
    if (!decision.isAllowed()) {
      throw new AnalyticsComputationException(
          AnalyticsComputationException.Reason.NOT_AUTHORIZED,
          "Analytics computation is not authorized.");
    }
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
        ownerInputs.stream().map(OwnerMeasureSnapshot::toAggregateInput).toList();
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
