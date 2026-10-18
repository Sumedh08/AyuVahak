package org.ayuvahak.analytics.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.ayuvahak.analytics.domain.AggregateCompleteness;
import org.ayuvahak.analytics.domain.AggregateInput;
import org.ayuvahak.analytics.domain.SourceMeasure;
import org.ayuvahak.platform.core.ExternalReference;

/** Normalized, aggregate-only response from one owner application reporting boundary. */
public record OwnerMeasureSnapshot(
    SourceMeasure measure,
    ExternalReference organization,
    Instant periodStart,
    Instant periodEnd,
    Optional<UUID> sourceSnapshotReference,
    Optional<Instant> sourceCutoff,
    Optional<String> ownerContractVersion,
    AggregateCompleteness completeness,
    OptionalLong value,
    Optional<Instant> generatedAt) {

  public OwnerMeasureSnapshot {
    Objects.requireNonNull(measure, "measure");
    Objects.requireNonNull(organization, "organization");
    Objects.requireNonNull(periodStart, "period start");
    Objects.requireNonNull(periodEnd, "period end");
    Objects.requireNonNull(sourceSnapshotReference, "source snapshot reference");
    Objects.requireNonNull(sourceCutoff, "source cutoff");
    Objects.requireNonNull(ownerContractVersion, "owner contract version");
    Objects.requireNonNull(completeness, "completeness");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(generatedAt, "generated at");
    if (!periodStart.isBefore(periodEnd)
        || ((completeness == AggregateCompleteness.COMPLETE) != value.isPresent())
        || (value.isPresent() && value.getAsLong() < 0)) {
      throw new IllegalArgumentException("owner reporting snapshot is invalid");
    }
    if (completeness == AggregateCompleteness.COMPLETE
        && (sourceSnapshotReference.isEmpty()
            || sourceCutoff.isEmpty()
            || ownerContractVersion.isEmpty()
            || generatedAt.isEmpty())) {
      throw new IllegalArgumentException("complete owner snapshot requires provenance");
    }
  }

  public AggregateInput toAggregateInput() {
    return new AggregateInput(
        measure,
        sourceSnapshotReference,
        sourceCutoff,
        ownerContractVersion,
        completeness,
        value,
        generatedAt);
  }
}
