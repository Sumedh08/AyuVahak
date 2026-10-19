package org.ayuvahak.analytics.adapter.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.application.OwnerReportingRequest;
import org.ayuvahak.analytics.domain.AggregateCompleteness;
import org.ayuvahak.analytics.domain.ReportingPeriod;
import org.ayuvahak.analytics.domain.SourceMeasure;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.transfer.application.TransferReportingSnapshotPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class OwnerReportingSnapshotAdapterTest {

  private static final Instant CUTOFF = Instant.parse("2026-03-30T00:00:00Z");
  private static final ExternalReference ORGANIZATION =
      new ExternalReference(
          new IssuerId("urn:ayuvahak:directory"),
          "Organization",
          "4a81f23f-b4ae-4e9b-a225-5f60ebbe4ff3");
  private static final ExternalReference FACILITY =
      new ExternalReference(
          new IssuerId("urn:ayuvahak:directory"),
          "Facility",
          "d4e31f9e-2f78-48bd-9ef6-e9ec0aab2c15");
  private static final SourceMeasure MEASURE =
      SourceMeasure.transfer("TRANSFER_COMPLETED", SourceMeasure.Attribution.ORIGIN);

  @Test
  void authorizationFailureIsNotConvertedIntoMissingSource() {
    TransferReportingSnapshotPort transfer =
        (context, request) -> {
          throw new PlatformProblemException(
              HttpStatus.SERVICE_UNAVAILABLE,
              PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
              "Synthetic authorization store failure.");
        };
    var adapter = new OwnerReportingSnapshotAdapter(null, transfer);

    assertThatThrownBy(() -> adapter.snapshot(context(), MEASURE, request()))
        .isInstanceOf(PlatformProblemException.class)
        .satisfies(
            failure ->
                assertThat(((PlatformProblemException) failure).code())
                    .isEqualTo(PlatformErrorCode.AUTHORIZATION_UNAVAILABLE));
  }

  @Test
  void ownerStoreUnavailableIsIncompleteAndNeverNumericZero() {
    TransferReportingSnapshotPort transfer =
        (context, request) -> {
          throw new PlatformProblemException(
              HttpStatus.SERVICE_UNAVAILABLE,
              PlatformErrorCode.TRANSFER_UNAVAILABLE,
              "Synthetic owner store failure.");
        };
    var adapter = new OwnerReportingSnapshotAdapter(null, transfer);

    var result = adapter.snapshot(context(), MEASURE, request());

    assertThat(result.completeness()).isEqualTo(AggregateCompleteness.MISSING_SOURCE);
    assertThat(result.value()).isEmpty();
    assertThat(result.sourceSnapshotReference()).isEmpty();
  }

  @Test
  void facilityScopeFromTransferOwnerIsPreservedInNormalizedInput() {
    ReportingPeriod period = period();
    TransferReportingSnapshotPort transfer =
        (context, request) ->
            new TransferReportingSnapshotPort.Snapshot(
                InternalId.generate(),
                request.measure().key(),
                request.attribution(),
                context.scope(),
                period.startInclusiveUtc(),
                period.endExclusiveUtc(),
                CUTOFF,
                TransferReportingSnapshotPort.OWNER_CONTRACT_VERSION,
                TransferReportingSnapshotPort.Completeness.COMPLETE,
                4,
                Map.of(),
                Instant.parse("2026-03-31T12:00:00Z"));
    var adapter = new OwnerReportingSnapshotAdapter(null, transfer);
    var facilityContext =
        new ExecutionContextFactory((actor, scope) -> actor != null && scope != null)
            .forBackgroundWork(
                ActorReference.service(
                    new IssuerId("https://identity.example.test/realm"), "synthetic-analytics"),
                RequestedExecutionScope.facility(ORGANIZATION, FACILITY),
                new CorrelationId(UUID.randomUUID().toString()),
                Optional.empty());
    var facilityRequest =
        new OwnerReportingRequest(
            InternalId.generate(), ORGANIZATION, Optional.of(FACILITY), period, CUTOFF);

    var result = adapter.snapshot(facilityContext, MEASURE, facilityRequest);

    assertThat(result.organization()).isEqualTo(ORGANIZATION);
    assertThat(result.facility()).contains(FACILITY);
    assertThat(result.value()).hasValue(4L);
  }

  private static ExecutionContext context() {
    var contexts = new ExecutionContextFactory((actor, scope) -> actor != null && scope != null);
    return contexts.forBackgroundWork(
        ActorReference.service(
            new IssuerId("https://identity.example.test/realm"), "synthetic-analytics"),
        RequestedExecutionScope.organization(ORGANIZATION),
        new CorrelationId(UUID.randomUUID().toString()),
        Optional.empty());
  }

  private static OwnerReportingRequest request() {
    return new OwnerReportingRequest(InternalId.generate(), ORGANIZATION, period(), CUTOFF);
  }

  private static ReportingPeriod period() {
    return ReportingPeriod.resolve(
        LocalDate.parse("2026-03-01"), LocalDate.parse("2026-04-01"), ZoneId.of("Europe/Berlin"));
  }
}
