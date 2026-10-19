package org.ayuvahak.referral.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Referral-owned persistence port for immutable, aggregate-only reporting snapshots. */
public interface ReferralReportingSnapshotStore {

  ReferralReportingSnapshot getOrCreate(
      UUID organizationId,
      Optional<UUID> facilityId,
      ReferralReportingSnapshotRequest request,
      String requestFingerprint,
      Instant generatedAt);
}
