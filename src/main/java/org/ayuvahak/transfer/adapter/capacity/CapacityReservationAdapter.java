package org.ayuvahak.transfer.adapter.capacity;

import java.util.Objects;
import org.ayuvahak.capacity.application.CapacityReservationCommands;
import org.ayuvahak.capacity.domain.CapacityReleaseResult;
import org.ayuvahak.capacity.domain.CapacityReservationOutcome;
import org.ayuvahak.capacity.domain.CapacityReservationRequest;
import org.ayuvahak.capacity.domain.CapacityReservationResult;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.transfer.application.CapacityReservationPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** In-process adapter that delegates reservation ownership to the Capacity application command. */
@Component
public final class CapacityReservationAdapter implements CapacityReservationPort {

  private final ObjectProvider<CapacityReservationCommands> commands;

  public CapacityReservationAdapter(ObjectProvider<CapacityReservationCommands> commands) {
    this.commands = Objects.requireNonNull(commands, "Capacity reservation command");
  }

  @Override
  public CapacityReservationResult reserve(
      ExecutionContext serviceContext, CapacityReservationRequest request, String idempotencyKey) {
    return commands.getObject().reserve(serviceContext, request, idempotencyKey);
  }

  @Override
  public CapacityReleaseResult release(ExecutionContext serviceContext, InternalId transferId) {
    return commands.getObject().release(serviceContext, transferId);
  }

  @Override
  public CapacityReleaseResult release(
      ExecutionContext serviceContext,
      InternalId releaseOperationId,
      InternalId reservationOperationId,
      InternalId transferId) {
    return commands
        .getObject()
        .release(serviceContext, releaseOperationId, reservationOperationId, transferId);
  }

  @Override
  public CapacityReservationOutcome getReservationOutcome(
      ExecutionContext serviceContext, InternalId operationId, InternalId transferId) {
    return commands.getObject().getReservationOutcome(serviceContext, operationId, transferId);
  }
}
