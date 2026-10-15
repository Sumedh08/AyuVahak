package org.ayuvahak.transport.application;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transport.domain.TransportRequest;

/** Transport-owned persistence boundary for requests and command receipts. */
public interface TransportRequestStore {

  void configureBoundedReceiptWait();

  Claim claimReceipt(CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt);

  void completeReceipt(CommandReceiptKey key, InternalId requestId, Instant recordedAt);

  boolean insert(
      TransportRequest request,
      String initiatingActorIssuer,
      String initiatingActorType,
      String recordingActorIssuer,
      String recordingActorType);

  Optional<TransportRequest> find(InternalId requestId);

  enum ClaimState {
    OWNER,
    REPLAY,
    KEY_REUSED,
    IN_PROGRESS
  }

  record Claim(ClaimState state, Optional<InternalId> resultReference) {}
}
