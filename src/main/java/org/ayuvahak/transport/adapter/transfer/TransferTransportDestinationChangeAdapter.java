package org.ayuvahak.transport.adapter.transfer;

import java.util.Objects;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.transfer.application.TransportDestinationChangePort;
import org.ayuvahak.transport.application.TransportDestinationChangeCommands;
import org.springframework.stereotype.Component;

/** Transfer-to-Transport application adapter; Transport retains operation ownership. */
@Component
public final class TransferTransportDestinationChangeAdapter
    implements TransportDestinationChangePort {

  private final TransportDestinationChangeCommands commands;

  public TransferTransportDestinationChangeAdapter(TransportDestinationChangeCommands commands) {
    this.commands = Objects.requireNonNull(commands, "Transport destination-change commands");
  }

  @Override
  public Result request(
      ExecutionContext serviceContext,
      InternalId transportOperationId,
      InternalId transferDestinationChangeId,
      InternalId transferId,
      InternalId transportRequestId,
      ExternalReference oldDestination,
      ExternalReference proposedDestination,
      String reasonCode,
      ActorReference initiatingActor) {
    var result =
        commands.request(
            serviceContext,
            transportOperationId,
            transferDestinationChangeId,
            transferId,
            transportRequestId,
            oldDestination,
            proposedDestination,
            reasonCode,
            initiatingActor);
    return new Result(
        result.operationId(), result.operation().state().name(), result.commandReplayed());
  }
}
