package org.ayuvahak.platform.eventing.application;

import java.time.Instant;
import java.util.UUID;

/** Platform-owned persistence seam for retention export and restore reapplication. */
public interface PlatformOutboxRetentionReapplicationStore {

  PlatformOutboxRetentionManifest exportSince(
      UUID restoreId, Instant backupCompletedAt, Instant generatedAt);

  void reapply(PlatformOutboxRetentionManifest manifest, Instant completedAt);

  boolean hasCompleted(UUID restoreId);
}
