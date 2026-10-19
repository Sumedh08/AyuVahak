package org.ayuvahak.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.junit.jupiter.api.Test;

class FederatedAggregateSnapshotTest {

  private static final Instant COMPUTED_AT = Instant.parse("2026-02-03T04:05:06Z");
  private static final DeploymentId SOURCE = new DeploymentId("SYN-DEPLOYMENT-A");

  @Test
  void createsAggregateOnlyMessageWithExactDefinitionAndAttributionProvenance() {
    IndicatorDefinitionVersion definition = definition(SourceMeasure.Attribution.ORIGIN);
    AggregateSnapshot snapshot = snapshot(definition, 7, AggregateCompleteness.COMPLETE);
    AggregateSnapshotRevision revision = revision(snapshot.snapshotId(), 3);

    FederatedAggregateSnapshot message =
        FederatedAggregateSnapshot.from(snapshot, definition, revision, SOURCE);

    assertThat(message.sourceSnapshotId()).isEqualTo(snapshot.snapshotId());
    assertThat(message.sourceDeployment()).isEqualTo(SOURCE);
    assertThat(message.sourceOrganization().issuer().value())
        .isEqualTo("urn:ayuvahak:deployment:SYN-DEPLOYMENT-A:directory");
    assertThat(message.definitionFingerprint()).isEqualTo(definition.fingerprint());
    assertThat(message.revisionSeriesId()).isEqualTo(revision.seriesId());
    assertThat(message.revisionNumber()).isEqualTo(3);
    assertThat(message.value()).isEqualTo(7);
    assertThat(message.attribution()).isEqualTo(SourceMeasure.Attribution.ORIGIN);
    assertThat(message.completeness()).isEqualTo(AggregateCompleteness.COMPLETE);
    assertThat(message.contentFingerprint()).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(message.toString()).doesNotContain("SYN-PATIENT-PHASE12-PRIVATE-91F4");
  }

  @Test
  void contentFingerprintChangesWhenImmutableRevisionValueChanges() {
    IndicatorDefinitionVersion definition = definition(SourceMeasure.Attribution.ORIGIN);
    AggregateSnapshot first = snapshot(definition, 7, AggregateCompleteness.COMPLETE);
    AggregateSnapshot second = snapshot(definition, 8, AggregateCompleteness.COMPLETE);

    FederatedAggregateSnapshot firstMessage =
        FederatedAggregateSnapshot.from(first, definition, revision(first.snapshotId(), 1), SOURCE);
    FederatedAggregateSnapshot secondMessage =
        FederatedAggregateSnapshot.from(
            second, definition, revision(second.snapshotId(), 2), SOURCE);

    assertThat(secondMessage.sourceSnapshotId()).isNotEqualTo(firstMessage.sourceSnapshotId());
    assertThat(secondMessage.contentFingerprint()).isNotEqualTo(firstMessage.contentFingerprint());
  }

  @Test
  void contentFingerprintIncludesStableSourceSnapshotIdentity() {
    IndicatorDefinitionVersion definition = definition(SourceMeasure.Attribution.ORIGIN);
    AggregateSnapshot first = snapshot(definition, 7, AggregateCompleteness.COMPLETE);
    AggregateSnapshot second = snapshot(definition, 7, AggregateCompleteness.COMPLETE);
    AggregateSnapshotRevision firstRevision = revision(first.snapshotId(), 1);
    AggregateSnapshotRevision secondRevision = revision(second.snapshotId(), 1);

    FederatedAggregateSnapshot firstMessage =
        FederatedAggregateSnapshot.from(first, definition, firstRevision, SOURCE);
    FederatedAggregateSnapshot secondMessage =
        FederatedAggregateSnapshot.from(second, definition, secondRevision, SOURCE);

    assertThat(firstMessage.contentFingerprint()).isNotEqualTo(secondMessage.contentFingerprint());
  }

  @Test
  void rejectsIncompleteOrDefinitionMismatchedSnapshots() {
    IndicatorDefinitionVersion definition = definition(SourceMeasure.Attribution.ORIGIN);
    AggregateSnapshot partial = snapshot(definition, null, AggregateCompleteness.MISSING_SOURCE);

    assertThatThrownBy(
            () ->
                FederatedAggregateSnapshot.from(
                    partial, definition, revision(partial.snapshotId(), 1), SOURCE))
        .isInstanceOf(IllegalArgumentException.class);

    AggregateSnapshot complete = snapshot(definition, 7, AggregateCompleteness.COMPLETE);
    IndicatorDefinitionVersion incompatible = definition(SourceMeasure.Attribution.DESTINATION);
    assertThatThrownBy(
            () ->
                FederatedAggregateSnapshot.from(
                    complete, incompatible, revision(complete.snapshotId(), 1), SOURCE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesImplicitMixedOriginAndDestinationAttribution() {
    SourceMeasure origin =
        SourceMeasure.transfer("TRANSFER_ACCEPTED", SourceMeasure.Attribution.ORIGIN);
    SourceMeasure destination =
        SourceMeasure.transfer("TRANSFER_COMPLETED", SourceMeasure.Attribution.DESTINATION);

    assertThatThrownBy(
            () ->
                new IndicatorDefinitionVersion(
                    "SYN-INDICATOR-FEDERATED-001",
                    1,
                    "Synthetic mixed attribution",
                    ZoneId.of("UTC"),
                    List.of(origin, destination),
                    true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void derivesFederatedAttributionFromDenominatorMeasuresToo() {
    IndicatorDefinitionVersion ratio =
        new IndicatorDefinitionVersion(
            "SYN-INDICATOR-DENOMINATOR-ATTRIBUTION",
            1,
            "Synthetic ratio denominator attribution",
            AggregationFormula.RATIO_6DP_HALF_EVEN,
            ZoneId.of("UTC"),
            List.of(SourceMeasure.referral("SUBMITTED_COUNT")),
            true,
            List.of(SourceMeasure.transfer("TRANSFER_ACCEPTED", SourceMeasure.Attribution.ORIGIN)),
            IndicatorDefinitionGovernance.syntheticFixture("Synthetic ratio demonstration"));

    assertThat(FederatedAggregateSnapshot.attribution(ratio))
        .isEqualTo(SourceMeasure.Attribution.ORIGIN);
  }

  private static IndicatorDefinitionVersion definition(SourceMeasure.Attribution attribution) {
    return new IndicatorDefinitionVersion(
        "SYN-INDICATOR-FEDERATED-001",
        1,
        "Synthetic transfer aggregate",
        ZoneId.of("UTC"),
        List.of(SourceMeasure.transfer("TRANSFER_ACCEPTED", attribution)),
        true);
  }

  private static AggregateSnapshot snapshot(
      IndicatorDefinitionVersion definition, Integer value, AggregateCompleteness completeness) {
    OptionalLong optionalValue =
        value == null ? OptionalLong.empty() : OptionalLong.of(value.longValue());
    SourceMeasure measure = definition.sourceMeasures().getFirst();
    AggregateInput input =
        new AggregateInput(
            measure,
            completeness == AggregateCompleteness.COMPLETE
                ? Optional.of(UUID.randomUUID())
                : Optional.empty(),
            completeness == AggregateCompleteness.COMPLETE
                ? Optional.of(Instant.parse("2026-02-02T00:00:00Z"))
                : Optional.empty(),
            completeness == AggregateCompleteness.COMPLETE
                ? Optional.of("synthetic-transfer-reporting-v1")
                : Optional.empty(),
            completeness,
            optionalValue,
            completeness == AggregateCompleteness.COMPLETE
                ? Optional.of(Instant.parse("2026-02-02T01:00:00Z"))
                : Optional.empty());
    return AggregateSnapshot.create(
        InternalId.generate(),
        definition,
        organization(),
        ReportingPeriod.resolve(
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"), ZoneId.of("UTC")),
        completeness,
        optionalValue,
        List.of(input),
        COMPUTED_AT);
  }

  private static AggregateSnapshotRevision revision(UUID snapshotId, int version) {
    return new AggregateSnapshotRevision(
        UUID.randomUUID(),
        version,
        snapshotId,
        version == 1 ? Optional.empty() : Optional.of(UUID.randomUUID()),
        version == 1 ? AnalyticsRevisionCause.INITIAL : AnalyticsRevisionCause.LATE_OWNER_FACT,
        AggregateSnapshotRevision.State.PROVISIONAL,
        Optional.of(Instant.parse("2026-02-02T00:00:00Z")),
        Instant.parse("2026-03-01T00:00:00Z"),
        COMPUTED_AT,
        Optional.empty(),
        false,
        false);
  }

  private static ExternalReference organization() {
    return new ExternalReference(
        new IssuerId("urn:ayuvahak:directory"), "Organization", UUID.randomUUID().toString());
  }
}
