package org.ayuvahak.transfer.application;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
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
