package org.ayuvahak.transfer.adapter.capacity;

import java.util.Objects;
import org.ayuvahak.capacity.application.CapacityReservationCommands;
import org.ayuvahak.capacity.domain.CapacityReservationRequest;
import org.ayuvahak.capacity.domain.CapacityReservationResult;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.transfer.application.CapacityReservationPort;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** In-process adapter that delegates reservation ownership to the Capacity application command. */
@Component
@Lazy
public final class CapacityReservationAdapter implements CapacityReservationPort {

  private final CapacityReservationCommands commands;

  public CapacityReservationAdapter(@Lazy CapacityReservationCommands commands) {
    this.commands = Objects.requireNonNull(commands, "Capacity reservation command");
  }

  @Override
  public CapacityReservationResult reserve(
      ExecutionContext serviceContext, CapacityReservationRequest request, String idempotencyKey) {
    return commands.reserve(serviceContext, request, idempotencyKey);
  }
}
