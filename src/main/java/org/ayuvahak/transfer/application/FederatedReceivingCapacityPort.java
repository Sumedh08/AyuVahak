package org.ayuvahak.transfer.application;

import org.ayuvahak.capacity.domain.CapacityReservationResult;
import org.ayuvahak.capacity.domain.FederatedReceivingCapacityReservationOutcome;
import org.ayuvahak.capacity.domain.FederatedReceivingCapacityReservationRequest;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Typed Transfer-to-Capacity contract for receiver-local federated reservation subjects. */
public interface FederatedReceivingCapacityPort {

  CapacityReservationResult reserve(
      ExecutionContext serviceContext,
      FederatedReceivingCapacityReservationRequest request,
      String idempotencyKey);

  FederatedReceivingCapacityReservationOutcome currentOutcome(
      ExecutionContext serviceContext,
      InternalId capacityOperationId,
      InternalId receivingRequestId);
}
