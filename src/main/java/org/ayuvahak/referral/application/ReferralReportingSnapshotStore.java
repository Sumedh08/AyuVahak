package org.ayuvahak.referral.application;

import java.time.Instant;
import java.util.UUID;

/** Referral-owned persistence port for immutable, aggregate-only reporting snapshots. */
public interface ReferralReportingSnapshotStore {

  ReferralReportingSnapshot getOrCreate(
      UUID organizationId,
      ReferralReportingSnapshotRequest request,
      String requestFingerprint,
      Instant generatedAt);
}
