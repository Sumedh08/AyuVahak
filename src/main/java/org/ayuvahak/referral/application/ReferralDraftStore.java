package org.ayuvahak.referral.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.referral.domain.ReferralCancellation;
import org.ayuvahak.referral.domain.ReferralDraft;
import org.ayuvahak.referral.domain.ReferralResolution;
import org.ayuvahak.referral.domain.SubmittedReferralRevision;

/** Referral-owned persistence boundary for current state, revisions, resolutions and receipts. */
public interface ReferralDraftStore {

  void insertDraft(ReferralDraft draft);

  Optional<ReferralDraft> lockDraft(InternalId referralId);

  Optional<ReferralDraft> findDraft(InternalId referralId);

  boolean updateDraft(ReferralDraft revised, long expectedVersion);

  boolean submitDraft(
      ReferralDraft submitted, long expectedVersion, SubmittedReferralRevision revision);

  boolean amendSubmitted(
      ReferralDraft amended, long expectedVersion, SubmittedReferralRevision revision);

  boolean resolveDraft(ReferralDraft resolved, long expectedVersion, ReferralResolution resolution);

  boolean cancelDraft(
      ReferralDraft cancelled, long expectedVersion, ReferralCancellation cancellation);

  Optional<SubmittedReferralRevision> findSubmittedRevision(
      InternalId referralId, long referralVersion);

  Optional<SubmittedReferralRevision> findSubmittedRevisionNumber(
      InternalId referralId, long revisionNumber);

  Optional<SubmittedReferralRevision> findLatestSubmittedRevision(InternalId referralId);

  List<ReferralListItem> findReferralPage(
      ExecutionScope scope, ReferralReadFilter filter, int limit, long offset);

  Optional<ReferralResolution> findResolution(InternalId referralId);

  Optional<ReferralCancellation> findCancellation(InternalId referralId);

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
