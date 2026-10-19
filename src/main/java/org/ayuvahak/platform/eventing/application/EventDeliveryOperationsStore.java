package org.ayuvahak.platform.eventing.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.InternalId;

/** Persistence boundary owned by internal event delivery operations. */
public interface EventDeliveryOperationsStore {
  List<EventDeliverySummary> listCurrent(Optional<UUID> afterId, int limit);

  Optional<ReplayCandidate> findCurrent(InternalId eventId);

  void configureBoundedReceiptWait();

  DeliveryReceiptClaim claimReceipt(
      String scopeDigest, String keyDigest, String fingerprint, Instant now);

  Optional<Integer> replay(InternalId eventId, int expectedGeneration, Instant now);

  void completeReceipt(
      String scopeDigest, String keyDigest, InternalId eventId, int generation, Instant now);

  boolean createRetentionHold(
      UUID holdId,
      InternalId eventId,
      RetentionHoldPurpose purpose,
      Instant reviewBy,
      ActorReference actor,
      Instant createdAt);

  boolean releaseRetentionHold(
      UUID holdId,
      InternalId eventId,
      RetentionHoldReleaseReason reason,
      ActorReference actor,
      Instant releasedAt);
}
