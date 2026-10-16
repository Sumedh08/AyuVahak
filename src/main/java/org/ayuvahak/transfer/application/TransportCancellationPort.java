package org.ayuvahak.transfer.application;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Transfer-owned port for a selected linked Transport cancellation command/outcome. */
public interface TransportCancellationPort {

  /** Requests cancellation through a dedicated Transport service identity. */
  void request(
      ExecutionContext initiatingContext,
      InternalId transferId,
      InternalId transportRequestId,
      ExternalReference originOrganization,
      ExternalReference originFacility,
      ActorReference initiatingActor,
      InternalId cancellationFactId,
      String reasonCode);

  /** Queries Transport's minimal owner outcome through the configured scoped service identity. */
  Optional<CancellationOutcome> getOutcome(
      ExecutionContext initiatingContext,
      InternalId transferId,
      InternalId transportRequestId,
      ExternalReference originOrganization,
      ExternalReference originFacility);

  record CancellationOutcome(
      InternalId transferId,
      InternalId transportRequestId,
      InternalId cancellationOperationId,
      OperationState state,
      Optional<Outcome> outcome,
      Optional<InternalId> resolutionEventId,
      Optional<String> sourceNamespace,
      Optional<String> sourceObservationId,
      Optional<Instant> sourceOccurredAt,
      Optional<Instant> resolvedAt,
      CorrelationId correlationId) {}

  enum OperationState {
    PENDING,
    OUTCOME_UNKNOWN,
    RECONCILIATION_REQUIRED,
    RESOLVED
  }

  enum Outcome {
    CANCELLED_BEFORE_MOVEMENT,
    CANCEL_REJECTED_ACTIVE,
    MOVEMENT_ALREADY_STARTED
  }
}
