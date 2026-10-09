package org.ayuvahak.referral.application;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.referral.domain.ReferralDraft;
import org.ayuvahak.referral.domain.SubmittedReferralRevision;

/** Referral-owned persistence boundary for drafts and command receipts. */
public interface ReferralDraftStore {

  void insertDraft(ReferralDraft draft);

  Optional<ReferralDraft> lockDraft(InternalId referralId);

  Optional<ReferralDraft> findDraft(InternalId referralId);

  boolean updateDraft(ReferralDraft revised, long expectedVersion);

  boolean submitDraft(
      ReferralDraft submitted, long expectedVersion, SubmittedReferralRevision revision);

  Optional<SubmittedReferralRevision> findSubmittedRevision(
      InternalId referralId, long referralVersion);

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
