package org.ayuvahak.transport.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.transport.domain.TransportDestinationChangeEvidence;
import org.ayuvahak.transport.domain.TransportDestinationChangeOperation;

/** Transport-owned persistence boundary for reroute requests and acknowledgements. */
public interface TransportDestinationChangeStore {

  boolean insert(
      TransportDestinationChangeOperation operation,
      String initiatedByIssuer,
      String initiatedByIdentifierType);

  Optional<TransportDestinationChangeOperation> find(InternalId operationId);

  Optional<TransportDestinationChangeOperation> lock(InternalId operationId);

  Optional<TransportDestinationChangeOperation> findByTransferOperation(
      InternalId transferOperationId);

  Optional<TransportDestinationChangeOperation> findLatestAcceptedForRequest(
      InternalId transportRequestId);

  List<TransportDestinationChangeOperation> findAcceptedHistoryForRequest(
      InternalId transportRequestId);

  List<TransportDestinationChangeOperation> findResolvedHistoryForRequest(
      InternalId transportRequestId);

  Optional<TransportDestinationChangeEvidence> findEvidence(
      InternalId operationId, String sourceNamespace, String sourceObservationId);

  boolean recordEvidence(TransportDestinationChangeEvidence evidence);

  boolean markUncertain(
      InternalId operationId,
      long expectedVersion,
      TransportDestinationChangeOperation.State state,
      Instant updatedAt);

  boolean resolve(
      InternalId operationId,
      long expectedVersion,
      TransportDestinationChangeOperation.Outcome outcome,
      ActorReference recordedBy,
      String sourceNamespace,
      String sourceObservationId,
      String fingerprint,
      Instant sourceOccurredAt,
      Instant resolvedAt,
      InternalId eventId);
}
