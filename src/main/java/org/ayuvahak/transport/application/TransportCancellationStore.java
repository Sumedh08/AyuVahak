package org.ayuvahak.transport.application;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transport.domain.TransportCancellationOperation;

/** Transport-owned persistence boundary for durable cancellation intent and resolution. */
public interface TransportCancellationStore {

  ReceiptClaim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt);

  void completeReceipt(CommandReceiptKey key, InternalId operationId, Instant recordedAt);

  boolean insert(
      TransportCancellationOperation operation,
      String actorKind,
      String actorIssuer,
      String actorIdentifierType,
      String actorIdentifier,
      String organizationId,
      String facilityId,
      String correlationId,
      String authorityCode);

  Optional<TransportCancellationOperation> find(InternalId operationId);

  Optional<TransportCancellationOperation> lock(InternalId operationId);

  Optional<TransportCancellationOperation> findUnresolvedForRequest(InternalId requestId);

  Optional<TransportCancellationOperation> findLatestForRequest(InternalId requestId);

  boolean markState(
      InternalId operationId, long expectedVersion, TransportCancellationOperation.State state);

  boolean resolve(
      InternalId operationId,
      long expectedVersion,
      TransportCancellationOperation.Outcome outcome,
      Instant resolvedAt,
      String sourceNamespace,
      String sourceObservationId,
      String fingerprint,
      Instant sourceOccurredAt,
      String resolutionCorrelationId,
      InternalId resolutionEventId);

  enum ClaimState {
    OWNER,
    REPLAY,
    KEY_REUSED,
    IN_PROGRESS
  }

  record ReceiptClaim(ClaimState state, Optional<InternalId> operationId) {}
}
