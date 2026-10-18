package org.ayuvahak.analytics.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.domain.AggregateSnapshot;
import org.ayuvahak.analytics.domain.AggregateSnapshotFinalization;
import org.ayuvahak.analytics.domain.AggregateSnapshotRevision;

/** Analytics-owned persistence boundary for immutable snapshot revisions and finalization. */
public interface AnalyticsRevisionStore {

  Optional<AggregateSnapshotRevision> currentRevision(UUID anySnapshotId);

  AggregateSnapshotRevision registerInitial(
      AggregateSnapshot snapshot,
      UUID operationId,
      Instant recordedAt,
      Instant correctionWindowEndsAt);

  AggregateSnapshotRevision appendRevision(
      AggregateSnapshot previousSnapshot,
      AggregateSnapshot replacementSnapshot,
      AppendAggregateSnapshotRevisionRequest request,
      Instant recordedAt,
      boolean lateRevisionAuthorized,
      boolean correctionAuthorized);

  AggregateSnapshotFinalization finalizeSnapshot(
      AggregateSnapshot snapshot, FinalizeAggregateSnapshotRequest request, Instant finalizedAt);

  void attachRevisionAudit(UUID operationId, UUID auditRecordId);

  void attachFinalizationAudit(UUID operationId, UUID auditRecordId);
}
