package org.ayuvahak.matching.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.matching.domain.RecommendationSnapshot;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;

/** Matching-owned persistence for immutable recommendation history and command receipts. */
public interface MatchingStore {

  void insertSnapshot(RecommendationSnapshot snapshot);

  Optional<RecommendationSnapshot> findSnapshot(InternalId recommendationId);

  List<RecommendationSnapshot> findHistory(
      InternalId referralId,
      InternalId originOrganizationId,
      Optional<InternalId> originFacilityId,
      int limit);

  void configureBoundedReceiptWait();

  Optional<ReceiptClaim> findReceipt(CommandReceiptKey key, RequestFingerprint fingerprint);

  ReceiptClaim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt);

  void completeReceipt(
      CommandReceiptKey key, int responseStatus, InternalId resultReference, Instant recordedAt);

  enum ReceiptState {
    OWNER,
    REPLAY,
    KEY_REUSED,
    IN_PROGRESS
  }

  record ReceiptClaim(
      ReceiptState state, Optional<Integer> responseStatus, Optional<InternalId> resultReference) {}
}
