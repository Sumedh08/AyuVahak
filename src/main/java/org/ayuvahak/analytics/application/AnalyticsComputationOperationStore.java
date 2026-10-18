package org.ayuvahak.analytics.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.InternalId;

/** Analytics-owned durable request queue and recovery boundary for aggregate computation. */
public interface AnalyticsComputationOperationStore {

  AnalyticsComputationOperation createOrReplay(AnalyticsComputationOperation proposed);

  Optional<AnalyticsComputationOperation> claimNext(Instant now, Instant leaseUntil);

  AnalyticsComputationOperation complete(
      InternalId requestId, int expectedAttemptCount, UUID snapshotId, Instant completedAt);

  AnalyticsComputationOperation retry(
      InternalId requestId,
      int expectedAttemptCount,
      String failureClass,
      int maxAttempts,
      Instant nextAttemptAt,
      Instant updatedAt);

  Optional<AnalyticsComputationOperation> find(InternalId requestId);
}
