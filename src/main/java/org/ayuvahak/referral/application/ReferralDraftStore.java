package org.ayuvahak.referral.application;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.referral.domain.ReferralDraft;

/** Referral-owned persistence boundary for drafts and command receipts. */
public interface ReferralDraftStore {

  void insertDraft(ReferralDraft draft);

  Optional<ReferralDraft> lockDraft(InternalId referralId);

  Optional<ReferralDraft> findDraft(InternalId referralId);

  boolean updateDraft(ReferralDraft revised, long expectedVersion);

  ReceiptClaim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt);

  void configureBoundedReceiptWait();

  void completeReceipt(
      CommandReceiptKey key,
      int responseStatus,
      InternalId resultReference,
      long resultVersion,
      Instant recordedAt);

  enum ReceiptState {
    OWNER,
    REPLAY,
    KEY_REUSED,
    IN_PROGRESS
  }

  record ReceiptClaim(
      ReceiptState state,
      Optional<Integer> responseStatus,
      Optional<InternalId> resultReference,
      Optional<Long> resultVersion) {}
}
