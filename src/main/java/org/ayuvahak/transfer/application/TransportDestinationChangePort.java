package org.ayuvahak.transfer.application;

import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Transfer-owned command port for a Transport-owned physical destination-change operation. */
public interface TransportDestinationChangePort {

  Result request(
      ExecutionContext serviceContext,
      InternalId transportOperationId,
      InternalId transferDestinationChangeId,
      InternalId transferId,
      InternalId transportRequestId,
      ExternalReference oldDestination,
      ExternalReference proposedDestination,
      String reasonCode,
      ActorReference initiatingActor);

  Optional<OutcomeSnapshot> getOutcome(
      ExecutionContext initiatingContext,
      InternalId transportOperationId,
      InternalId transferDestinationChangeId,
      InternalId transferId,
      InternalId transportRequestId,
      ExternalReference originOrganization,
      ExternalReference originFacility);

  record Result(InternalId operationId, String state, boolean commandReplayed) {}

  record OutcomeSnapshot(
      InternalId transportOperationId,
      InternalId transferDestinationChangeId,
      InternalId transferId,
      InternalId transportRequestId,
      OutcomeState state,
      Optional<Outcome> outcome,
      Optional<InternalId> resolutionEventId,
      CorrelationId correlationId) {}

  enum OutcomeState {
    PENDING,
    OUTCOME_UNKNOWN,
    RECONCILIATION_REQUIRED,
    RESOLVED
  }

  enum Outcome {
    ACCEPTED,
    REJECTED
  }
}
