package org.ayuvahak.analytics.adapter.reporting;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.ayuvahak.analytics.application.AnalyticsComputationException;
import org.ayuvahak.analytics.application.OwnerMeasureSnapshot;
import org.ayuvahak.analytics.application.OwnerReportingRequest;
import org.ayuvahak.analytics.application.OwnerReportingSnapshotReader;
import org.ayuvahak.analytics.domain.AggregateCompleteness;
import org.ayuvahak.analytics.domain.SourceMeasure;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.referral.application.ReferralReportingCompleteness;
import org.ayuvahak.referral.application.ReferralReportingSnapshot;
import org.ayuvahak.referral.application.ReferralReportingSnapshotRequest;
import org.ayuvahak.referral.application.ReferralReportingSnapshots;
import org.ayuvahak.transfer.application.TransferReportingSnapshotPort;
import org.springframework.stereotype.Component;

/** Translates Analytics-owned measure keys to the two frozen owner reporting application ports. */
@Component
public final class OwnerReportingSnapshotAdapter implements OwnerReportingSnapshotReader {

  private final ReferralReportingSnapshots referral;
  private final TransferReportingSnapshotPort transfer;

  public OwnerReportingSnapshotAdapter(
      ReferralReportingSnapshots referral, TransferReportingSnapshotPort transfer) {
    this.referral = referral;
    this.transfer = transfer;
  }

  @Override
  public OwnerMeasureSnapshot snapshot(
      ExecutionContext context, SourceMeasure measure, OwnerReportingRequest request) {
    return switch (measure.owner()) {
      case REFERRAL -> referral(context, measure, request);
      case TRANSFER -> transfer(context, measure, request);
    };
  }

  private OwnerMeasureSnapshot referral(
      ExecutionContext context, SourceMeasure measure, OwnerReportingRequest request) {
    try {
      UUID requestId =
          UUID.nameUUIDFromBytes(
              requestIdentity(request, measure).getBytes(StandardCharsets.UTF_8));
      ReferralReportingSnapshot snapshot =
          referral.snapshot(
              context,
              new ReferralReportingSnapshotRequest(
                  requestId,
                  java.util.Set.of(measure.key()),
                  request.period().startInclusiveUtc(),
                  request.period().endExclusiveUtc(),
                  request.referralSourceCutoff()));
      OptionalLong value = snapshot.measures().get(measure.key());
      if (value == null
          || (snapshot.completeness() == ReferralReportingCompleteness.COMPLETE)
              != value.isPresent()) {
        throw invalidOwnerResponse();
      }
      return new OwnerMeasureSnapshot(
          measure,
          snapshot.organization(),
          snapshot.facility(),
          snapshot.periodStart(),
          snapshot.periodEnd(),
          Optional.of(snapshot.sourceSnapshotReference()),
          Optional.of(snapshot.sourceCutoff()),
          Optional.of("referral-reporting-v" + snapshot.sourceVersion()),
          completeness(snapshot.completeness().name()),
          value,
          Optional.of(snapshot.generatedAt()));
    } catch (PlatformProblemException unavailable) {
      if (unavailable.status().is5xxServerError()
          && unavailable.code() == PlatformErrorCode.REFERRAL_UNAVAILABLE) {
        return missing(measure, request);
      }
      throw unavailable;
    }
  }

  private OwnerMeasureSnapshot transfer(
      ExecutionContext context, SourceMeasure measure, OwnerReportingRequest request) {
    try {
      TransferReportingSnapshotPort.Snapshot snapshot =
          transfer.snapshot(
              context,
              new TransferReportingSnapshotPort.SnapshotRequest(
                  stableInternalId(request, measure),
                  transferMeasure(measure),
                  TransferReportingSnapshotPort.Attribution.valueOf(measure.attribution().name()),
                  request.period().startInclusiveUtc(),
                  request.period().endExclusiveUtc()));
      ExternalReference returnedOrganization =
          snapshot
              .scope()
              .organization()
              .orElseThrow(OwnerReportingSnapshotAdapter::invalidOwnerResponse);
      Optional<ExternalReference> returnedFacility = snapshot.scope().facility();
      OptionalLong value =
          snapshot.completeness() == TransferReportingSnapshotPort.Completeness.COMPLETE
              ? OptionalLong.of(snapshot.value())
              : OptionalLong.empty();
      if (!measure.key().equals(snapshot.measureKey())
          || !measure.attribution().name().equals(snapshot.attribution().name())) {
        throw invalidOwnerResponse();
      }
      return new OwnerMeasureSnapshot(
          measure,
          returnedOrganization,
          returnedFacility,
          snapshot.periodStart(),
          snapshot.periodEnd(),
          Optional.of(snapshot.sourceSnapshotId().value()),
          Optional.of(snapshot.sourceCutoff()),
          Optional.of(snapshot.ownerContractVersion()),
          completeness(snapshot.completeness().name()),
          value,
          Optional.of(snapshot.generatedAt()));
    } catch (PlatformProblemException unavailable) {
      if (unavailable.status().is5xxServerError()
          && unavailable.code() == PlatformErrorCode.TRANSFER_UNAVAILABLE) {
        return missing(measure, request);
      }
      throw unavailable;
    }
  }

  private static OwnerMeasureSnapshot missing(
      SourceMeasure measure, OwnerReportingRequest request) {
    return new OwnerMeasureSnapshot(
        measure,
        request.organization(),
        request.facility(),
        request.period().startInclusiveUtc(),
        request.period().endExclusiveUtc(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        AggregateCompleteness.MISSING_SOURCE,
        OptionalLong.empty(),
        Optional.empty());
  }

  private static AggregateCompleteness completeness(String status) {
    return AggregateCompleteness.valueOf(status);
  }

  private static TransferReportingSnapshotPort.Measure transferMeasure(SourceMeasure measure) {
    return java.util.Arrays.stream(TransferReportingSnapshotPort.Measure.values())
        .filter(candidate -> candidate.key().equals(measure.key()))
        .findFirst()
        .orElseThrow(OwnerReportingSnapshotAdapter::invalidOwnerResponse);
  }

  private static AnalyticsComputationException invalidOwnerResponse() {
    return new AnalyticsComputationException(
        AnalyticsComputationException.Reason.OWNER_CONTRACT_INVALID,
        "An owner reporting contract returned invalid aggregate evidence.");
  }

  private static String requestIdentity(OwnerReportingRequest request, SourceMeasure measure) {
    String identity =
        request.computationRequestId()
            + "\n"
            + request.organization().issuer().value()
            + "\n"
            + request.organization().resourceType()
            + "\n"
            + request.organization().value()
            + "\n"
            + request.period().startInclusiveUtc()
            + "\n"
            + request.period().endExclusiveUtc()
            + "\n"
            + request.referralSourceCutoff()
            + "\n"
            + measure.stableKey();
    return request
        .facility()
        .map(
            facility ->
                request.computationRequestId()
                    + "\n"
                    + request.organization().issuer().value()
                    + "\n"
                    + request.organization().resourceType()
                    + "\n"
                    + request.organization().value()
                    + "\n"
                    + facility.value()
                    + "\n"
                    + request.period().startInclusiveUtc()
                    + "\n"
                    + request.period().endExclusiveUtc()
                    + "\n"
                    + request.referralSourceCutoff()
                    + "\n"
                    + measure.stableKey())
        .orElse(identity);
  }

  private static InternalId stableInternalId(OwnerReportingRequest request, SourceMeasure measure) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256")
              .digest(requestIdentity(request, measure).getBytes(StandardCharsets.UTF_8));
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      long mostSignificantBits = buffer.getLong();
      long leastSignificantBits = buffer.getLong();
      mostSignificantBits = (mostSignificantBits & 0xffffffffffff0fffL) | 0x0000000000004000L;
      leastSignificantBits = (leastSignificantBits & 0x3fffffffffffffffL) | 0x8000000000000000L;
      return new InternalId(new UUID(mostSignificantBits, leastSignificantBits));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }
}
