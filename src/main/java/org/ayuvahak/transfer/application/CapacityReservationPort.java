package org.ayuvahak.transfer.application;

import org.ayuvahak.capacity.domain.CapacityReservationRequest;
import org.ayuvahak.capacity.domain.CapacityReservationResult;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Transfer-owned need for a Capacity reservation; Capacity retains command and state ownership. */
public interface CapacityReservationPort {

  CapacityReservationResult reserve(
      ExecutionContext serviceContext, CapacityReservationRequest request, String idempotencyKey);
}
