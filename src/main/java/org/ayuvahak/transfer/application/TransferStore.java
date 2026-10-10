package org.ayuvahak.transfer.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.ReceivingAssessment;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;

/** Transfer-owned persistence boundary for Journey, first request and command receipts. */
public interface TransferStore {

  Optional<ReceiptClaim> findReceipt(CommandReceiptKey key, RequestFingerprint fingerprint);

  ReceiptClaim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt);

  void configureBoundedReceiptWait();

  void insertFirstRequest(TransferJourney journey, TransferRequestAttempt attempt);

  void completeReceipt(
      CommandReceiptKey key,
      int responseStatus,
      InternalId resultReference,
      long resultVersion,
      Instant recordedAt);

  Optional<TransferJourney> findJourney(InternalId journeyId);

  Optional<TransferJourney> findJourneyByReferral(InternalId referralId);

  Optional<TransferRequestAttempt> findFirstRequest(InternalId journeyId);

  Optional<TransferRequestAttempt> findRequestAttempt(InternalId requestAttemptId);

  List<TransferRequestAttempt> findDueRequestAttempts(Instant dueAt, int limit);

  Optional<ReceivingAssessment> findLatestAssessment(
      InternalId requestAttemptId, ReceivingAssessment.Kind kind);

  Optional<ReceivingAssessment> findAssessmentByVersion(
      InternalId requestAttemptId, ReceivingAssessment.Kind kind, long requestVersion);

  Optional<DecisionResult> findDecisionByVersion(InternalId requestAttemptId, long requestVersion);

  boolean advancePendingRequest(
      InternalId requestAttemptId, long expectedVersion, String status, Instant now);

  boolean withdrawPendingRequest(
      InternalId requestAttemptId, long expectedVersion, Instant occurredAt, Instant recordedAt);

  boolean expirePendingRequest(
      InternalId requestAttemptId, long expectedVersion, Instant recordedAt);

  void insertAssessment(ReceivingAssessment assessment);

  void insertDecisionAndOptionalTransfer(
      org.ayuvahak.transfer.domain.TransferDecision decision,
      Optional<CoordinatedTransfer> transfer);

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

  record DecisionResult(
      org.ayuvahak.transfer.domain.TransferDecision decision,
      Optional<CoordinatedTransfer> transfer) {}
}
