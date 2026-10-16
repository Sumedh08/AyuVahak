package org.ayuvahak.transport.adapter.transfer;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CausationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.transfer.application.TransferSettings;
import org.ayuvahak.transfer.application.TransportDestinationChangePort;
import org.ayuvahak.transfer.application.TransportDestinationChangePort.Outcome;
import org.ayuvahak.transfer.application.TransportDestinationChangePort.OutcomeState;
import org.ayuvahak.transport.application.TransportDestinationChangeCommands;
import org.springframework.stereotype.Component;

/** Transfer-to-Transport application adapter; Transport retains operation ownership. */
@Component
public final class TransferTransportDestinationChangeAdapter
    implements TransportDestinationChangePort {

  private final TransportDestinationChangeCommands commands;
  private final ExecutionContextFactory contexts;
  private final AuthenticationProperties authentication;
  private final TransferSettings properties;

  public TransferTransportDestinationChangeAdapter(
      TransportDestinationChangeCommands commands,
      ExecutionContextFactory contexts,
      AuthenticationProperties authentication,
      TransferSettings properties) {
    this.commands = Objects.requireNonNull(commands, "Transport destination-change commands");
    this.contexts = Objects.requireNonNull(contexts, "execution context factory");
    this.authentication = Objects.requireNonNull(authentication, "authentication properties");
    this.properties = Objects.requireNonNull(properties, "Transfer properties");
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

  @Override
  public Optional<OutcomeSnapshot> getOutcome(
      ExecutionContext initiatingContext,
      InternalId transportOperationId,
      InternalId transferDestinationChangeId,
      InternalId transferId,
      InternalId transportRequestId,
      ExternalReference originOrganization,
      ExternalReference originFacility) {
    ExecutionContext serviceContext =
        contexts.forBackgroundWork(
            ActorReference.service(
                new IssuerId(authentication.issuer()), properties.getTransportWorkerClientId()),
            RequestedExecutionScope.facility(originOrganization, originFacility),
            initiatingContext.correlationId(),
            Optional.of(new CausationId(transferDestinationChangeId.toString())));
    return commands
        .getOutcome(
            serviceContext,
            transportOperationId,
            transferDestinationChangeId,
            transferId,
            transportRequestId)
        .map(
            result ->
                new OutcomeSnapshot(
                    result.operationId(),
                    result.transferDestinationChangeId(),
                    result.transferId(),
                    result.transportRequestId(),
                    OutcomeState.valueOf(result.state().name()),
                    result.outcome().map(value -> Outcome.valueOf(value.name())),
                    result.resolutionEventId(),
                    result.correlationId()));
  }
}
