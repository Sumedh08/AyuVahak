package org.ayuvahak.transfer.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.domain.CapacityReservationIntent;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.ReceivingAssessment;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.ayuvahak.transfer.domain.TransferJourneyClosure;
import org.ayuvahak.transfer.domain.TransferProgressCorrection;
import org.ayuvahak.transfer.domain.TransferProgressFact;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;

/** Transfer-owned persistence boundary for Journey, first request and command receipts. */
public interface TransferStore {

  Optional<ReceiptClaim> findReceipt(CommandReceiptKey key, RequestFingerprint fingerprint);

  ReceiptClaim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt);

  void configureBoundedReceiptWait();

  /** Serializes Transfer request creation and Referral-cancellation reconciliation per Referral. */
  void lockReferralCoordination(InternalId referralId);

  void insertFirstRequest(TransferJourney journey, TransferRequestAttempt attempt);

  void completeReceipt(
      CommandReceiptKey key,
      int responseStatus,
      InternalId resultReference,
      long resultVersion,
      Instant recordedAt);

  Optional<TransferJourney> findJourney(InternalId journeyId);

  Optional<TransferJourney> lockJourney(InternalId journeyId);

  Optional<TransferJourney> findJourneyByReferral(InternalId referralId);

  Optional<TransferJourneyClosure> findJourneyClosure(InternalId journeyId);

  boolean canCloseJourneyUnfulfilled(InternalId journeyId);

  boolean recordJourneyClosure(TransferJourneyClosure closure, long expectedJourneyVersion);

  Optional<TransferRequestAttempt> findFirstRequest(InternalId journeyId);

  Optional<TransferRequestAttempt> findLatestRequest(InternalId journeyId);

  Optional<TransferRequestAttempt> findRequestAttempt(InternalId journeyId, int attemptNumber);

  Optional<TransferAttemptState> findAttemptState(InternalId requestAttemptId);

  boolean insertNextRequest(
      InternalId journeyId, long expectedJourneyVersion, TransferRequestAttempt attempt);

  Optional<TransferRequestAttempt> findRequestAttempt(InternalId requestAttemptId);

  List<TransferRequestAttempt> findDueRequestAttempts(Instant dueAt, int limit);

  Optional<ReceivingAssessment> findLatestAssessment(
      InternalId requestAttemptId, ReceivingAssessment.Kind kind);

  Optional<ReceivingAssessment> findAssessmentByVersion(
      InternalId requestAttemptId, ReceivingAssessment.Kind kind, long requestVersion);

  Optional<DecisionResult> findDecisionByVersion(InternalId requestAttemptId, long requestVersion);

  Optional<CoordinatedTransfer> findTransfer(InternalId transferId);

  Optional<CapacityReservationIntent> findReservationIntent(InternalId transferId);

  Optional<TransferProgressTarget> lockProgressTarget(InternalId transferId);

  Optional<TransferProgressTarget> findProgressTarget(InternalId transferId);

  List<TransferProgressFact> findProgressFacts(InternalId transferId);

  List<TransferProgressCorrection> findProgressCorrections(InternalId transferId);

  Optional<TransferProgressFact> findProgressFact(InternalId transferId, InternalId progressFactId);

  Optional<TransferProgressCorrection> findProgressCorrectionBySequence(
      InternalId transferId, long aggregateSequence);

  Optional<TransferProgressFact> findProgressFactByVersion(
      InternalId transferId, long transferVersion);

  boolean recordProgress(TransferProgressFact fact);

  boolean recordProgressCorrection(TransferProgressCorrection correction);

  Optional<ReceiptClaim> findProgressReceipt(CommandReceiptKey key, RequestFingerprint fingerprint);

  ReceiptClaim claimProgressReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt);

  void completeProgressReceipt(
      CommandReceiptKey key,
      int responseStatus,
      InternalId transferId,
      long resultVersion,
      Instant recordedAt);

  List<CapacityReservationIntent> findDueReservationIntents(Instant dueAt, int limit);

  /**
   * Commits a Transfer-owned lease before calling Capacity; the external owner call must not share
   * this transaction.
   */
  boolean claimReservationAttempt(
      InternalId transferId,
      int expectedAttemptCount,
      int claimedAttemptCount,
      Instant leaseUntil,
      Instant claimedAt);

  boolean claimReservationOutcomeCheck(
      InternalId transferId,
      int expectedAttemptCount,
      int claimedAttemptCount,
      Instant leaseUntil,
      Instant claimedAt);

  boolean deferReservationOutcomeCheck(
      InternalId transferId, int expectedAttemptCount, Instant nextAttemptAt, Instant recordedAt);

  List<InternalId> findPendingCancellationTransfers(int limit);

  boolean claimConsumerEvent(InternalId eventId, EventType eventType, Instant recordedAt);

  boolean isConsumerEventCompleted(InternalId eventId, EventType eventType);

  void completeConsumerEvent(
      InternalId eventId, Optional<InternalId> transferId, String outcome, Instant recordedAt);

  boolean advanceCapacityEventSequence(InternalId transferId, long sequence);

  boolean recordReservationLifecycle(
      InternalId transferId,
      long sequence,
      CapacityReservationIntent.Outcome outcome,
      Optional<String> reasonCode);

  boolean recordReservationOutcome(
      InternalId transferId,
      int expectedAttemptCount,
      CapacityReservationIntent.Outcome outcome,
      Optional<InternalId> reservationId,
      Optional<String> reasonCode,
      boolean mayMarkReady,
      Instant nextAttemptAt,
      Instant recordedAt);

  boolean advancePendingRequest(
      InternalId requestAttemptId, long expectedVersion, String status, Instant now);

  boolean withdrawPendingRequest(
      InternalId requestAttemptId, long expectedVersion, Instant occurredAt, Instant recordedAt);

  boolean expirePendingRequest(
      InternalId requestAttemptId, long expectedVersion, Instant recordedAt);

  void insertAssessment(ReceivingAssessment assessment);

  void insertDecisionAndOptionalTransfer(
      org.ayuvahak.transfer.domain.TransferDecision decision,
      Optional<CoordinatedTransfer> transfer,
      Optional<CapacityReservationIntent> reservationIntent);

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

  record TransferAttemptState(
      Optional<CoordinatedTransfer.Status> transferStatus,
      Optional<CapacityReservationIntent.Outcome> reservationOutcome) {}
}
