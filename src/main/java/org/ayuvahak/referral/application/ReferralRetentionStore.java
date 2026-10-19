package org.ayuvahak.referral.application;

import java.time.Instant;
import java.util.UUID;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.InternalId;

/** Referral-owned persistence boundary for holds and identifier access restrictions. */
public interface ReferralRetentionStore {

  boolean isIdentifierRestricted(InternalId referralId);

  boolean hasActiveHold(InternalId referralId);

  boolean createHold(
      UUID holdId,
      InternalId referralId,
      ReferralRetentionHoldPurpose purpose,
      Instant reviewBy,
      ActorReference actor,
      Instant createdAt);

  boolean releaseHold(
      UUID holdId,
      InternalId referralId,
      ReferralRetentionHoldReleaseReason reason,
      ActorReference actor,
      Instant releasedAt);

  boolean restrictIdentifier(
      UUID restrictionId,
      InternalId referralId,
      ReferralIdentifierRestrictionReason reason,
      ActorReference actor,
      Instant restrictedAt);

  ReferralRetentionManifest exportSince(UUID restoreId, Instant backupCompletedAt, Instant generatedAt);

  void reapply(ReferralRetentionManifest manifest, Instant completedAt);

  boolean hasCompleted(UUID restoreId);
}
