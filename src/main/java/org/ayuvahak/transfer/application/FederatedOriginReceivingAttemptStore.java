package org.ayuvahak.transfer.application;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt;

/** Transfer-owned persistence contract for stable origin-side peer operations. */
public interface FederatedOriginReceivingAttemptStore {

  TransferStore.ReceiptClaim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt);

  void completeReceipt(
      CommandReceiptKey key,
      int responseStatus,
      InternalId attemptId,
      long attemptNumber,
      Instant recordedAt);

  void insert(FederatedOriginReceivingAttempt attempt);

  Optional<FederatedOriginReceivingAttempt> findById(InternalId attemptId);

  Optional<FederatedOriginReceivingAttempt> lockByOperationId(InternalId operationId);

  int latestAttemptNumber(InternalId journeyId);

  boolean update(FederatedOriginReceivingAttempt attempt, long expectedVersion);
}
