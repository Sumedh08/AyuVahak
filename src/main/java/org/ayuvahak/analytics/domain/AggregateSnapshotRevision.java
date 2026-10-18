package org.ayuvahak.analytics.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable position in an Analytics snapshot revision chain. */
public record AggregateSnapshotRevision(
    UUID seriesId,
    int revisionNumber,
    UUID snapshotId,
    Optional<UUID> previousSnapshotId,
    AnalyticsRevisionCause cause,
    State state,
    Optional<Instant> latestSourceCutoff,
    Instant correctionWindowEndsAt,
    Instant recordedAt,
    Optional<UUID> auditRecordId,
    boolean authorizedCorrection,
    boolean replayed) {

  public enum State {
    PROVISIONAL,
    FINALIZED
  }

  public AggregateSnapshotRevision {
    Objects.requireNonNull(seriesId, "revision series id");
    Objects.requireNonNull(snapshotId, "snapshot id");
    previousSnapshotId = Objects.requireNonNull(previousSnapshotId, "previous snapshot id");
    Objects.requireNonNull(cause, "revision cause");
    Objects.requireNonNull(state, "series state");
    latestSourceCutoff = Objects.requireNonNull(latestSourceCutoff, "latest source cutoff");
    Objects.requireNonNull(correctionWindowEndsAt, "correction window end");
    Objects.requireNonNull(recordedAt, "recorded at");
    auditRecordId = Objects.requireNonNull(auditRecordId, "audit record id");
    if (revisionNumber < 1
        || (revisionNumber == 1) != previousSnapshotId.isEmpty()
        || (revisionNumber == 1) != (cause == AnalyticsRevisionCause.INITIAL)) {
      throw new IllegalArgumentException("aggregate revision lineage is invalid");
    }
  }
}
