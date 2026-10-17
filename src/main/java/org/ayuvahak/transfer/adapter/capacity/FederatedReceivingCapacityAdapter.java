package org.ayuvahak.transfer.adapter.capacity;

import java.util.Objects;
import org.ayuvahak.capacity.application.CapacityReservationCommands;
import org.ayuvahak.capacity.domain.CapacityReservationResult;
import org.ayuvahak.capacity.domain.FederatedReceivingCapacityReservationOutcome;
import org.ayuvahak.capacity.domain.FederatedReceivingCapacityReservationRequest;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.transfer.application.FederatedReceivingCapacityPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Calls the Capacity owner contract while keeping its types out of Transfer domain. */
@Component
public final class FederatedReceivingCapacityAdapter implements FederatedReceivingCapacityPort {

  private final ObjectProvider<CapacityReservationCommands> commands;

  public FederatedReceivingCapacityAdapter(ObjectProvider<CapacityReservationCommands> commands) {
    this.commands = Objects.requireNonNull(commands, "Capacity reservation commands");
  }

  @Override
  public CapacityReservationResult reserve(
      ExecutionContext serviceContext,
      FederatedReceivingCapacityReservationRequest request,
      String idempotencyKey) {
    return commands.getObject().reserveFederatedReceiving(serviceContext, request, idempotencyKey);
  }

  @Override
  public FederatedReceivingCapacityReservationOutcome currentOutcome(
      ExecutionContext serviceContext,
      InternalId capacityOperationId,
      InternalId receivingRequestId) {
    return commands
        .getObject()
        .getFederatedReceivingReservationOutcome(
            serviceContext, capacityOperationId, receivingRequestId);
  }
}
