package org.ayuvahak.analytics.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** The minimized owner-produced scalar inputs retained for reproducibility. */
public record AggregateInput(
    SourceMeasure measure,
    Optional<UUID> sourceSnapshotReference,
    Optional<Instant> sourceCutoff,
    Optional<String> ownerContractVersion,
    AggregateCompleteness completeness,
    OptionalLong value,
    Optional<Instant> generatedAt) {

  public AggregateInput {
    Objects.requireNonNull(measure, "source measure");
    Objects.requireNonNull(sourceSnapshotReference, "source snapshot reference");
    Objects.requireNonNull(sourceCutoff, "source cutoff");
    Objects.requireNonNull(ownerContractVersion, "owner contract version");
    Objects.requireNonNull(completeness, "completeness");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(generatedAt, "generated at");
    sourceSnapshotReference.ifPresent(Objects::requireNonNull);
    sourceCutoff.ifPresent(Objects::requireNonNull);
    ownerContractVersion.ifPresent(Objects::requireNonNull);
    generatedAt.ifPresent(Objects::requireNonNull);
    if ((completeness == AggregateCompleteness.COMPLETE) != value.isPresent()) {
      throw new IllegalArgumentException("only complete owner inputs may carry numeric values");
    }
    if (value.isPresent() && value.getAsLong() < 0) {
      throw new IllegalArgumentException("aggregate source values cannot be negative");
    }
    if (completeness == AggregateCompleteness.COMPLETE
        && (sourceSnapshotReference.isEmpty()
            || sourceCutoff.isEmpty()
            || ownerContractVersion.isEmpty()
            || generatedAt.isEmpty())) {
      throw new IllegalArgumentException("complete owner input requires source provenance");
    }
  }
}
