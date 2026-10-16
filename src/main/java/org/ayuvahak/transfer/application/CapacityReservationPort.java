package org.ayuvahak.transfer.application;

import org.ayuvahak.capacity.domain.CapacityReleaseResult;
import org.ayuvahak.capacity.domain.CapacityReservationOutcome;
import org.ayuvahak.capacity.domain.CapacityReservationRequest;
import org.ayuvahak.capacity.domain.CapacityReservationResult;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Transfer-owned need for a Capacity reservation; Capacity retains command and state ownership. */
public interface CapacityReservationPort {

  CapacityReservationResult reserve(
      ExecutionContext serviceContext, CapacityReservationRequest request, String idempotencyKey);

  CapacityReleaseResult release(ExecutionContext serviceContext, InternalId transferId);

  CapacityReleaseResult release(
      ExecutionContext serviceContext,
      InternalId releaseOperationId,
      InternalId reservationOperationId,
      InternalId transferId);

  CapacityReservationOutcome getReservationOutcome(
      ExecutionContext serviceContext, InternalId operationId, InternalId transferId);
}
