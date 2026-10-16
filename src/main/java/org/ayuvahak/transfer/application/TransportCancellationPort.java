package org.ayuvahak.transfer.application;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Transfer-to-Transport owner command boundary for a linked Transport cancellation. */
public interface TransportCancellationPort {

  /**
   * Requests cancellation through a dedicated Transport service identity. Returns false only if
   * Transfer has no selected Transport request; unavailable Transport outcomes must throw.
   */
  boolean requestIfSelected(
      ExecutionContext initiatingContext,
      InternalId transferId,
      ActorReference initiatingActor,
      InternalId cancellationFactId,
      String reasonCode);

  /** Queries Transport's minimal owner outcome through the configured scoped service identity. */
  Optional<CancellationOutcome> getOutcomeIfSelected(
      ExecutionContext initiatingContext, InternalId transferId);

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
