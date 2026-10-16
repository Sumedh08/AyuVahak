package org.ayuvahak.transport.adapter.transfer;

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
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.application.TransferSettings;
import org.ayuvahak.transfer.application.TransportCancellationPort;
import org.ayuvahak.transfer.application.TransportCancellationPort.CancellationOutcome;
import org.ayuvahak.transfer.application.TransportCancellationPort.OperationState;
import org.ayuvahak.transfer.application.TransportCancellationPort.Outcome;
import org.ayuvahak.transport.application.TransportCancellationCommands;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Adapts Transfer-owned selected-link commands to Transport's scoped owner command. */
@Component
public final class TransportTransferCancellationAdapter implements TransportCancellationPort {

  private final TransportCancellationCommands transport;
  private final ExecutionContextFactory contexts;
  private final AuthenticationProperties authentication;
  private final TransferSettings properties;
  private final ObjectMapper mapper;

  public TransportTransferCancellationAdapter(
      TransportCancellationCommands transport,
      ExecutionContextFactory contexts,
      AuthenticationProperties authentication,
      TransferSettings properties,
      ObjectMapper mapper) {
    this.transport = transport;
    this.contexts = contexts;
    this.authentication = authentication;
    this.properties = properties;
    this.mapper = mapper;
  }

  @Override
  public void request(
      ExecutionContext initiatingContext,
      InternalId transferId,
      InternalId transportRequestId,
      ExternalReference originOrganization,
      ExternalReference originFacility,
      ActorReference initiatingActor,
      InternalId cancellationFactId,
      String reasonCode) {
    var scope = RequestedExecutionScope.facility(originOrganization, originFacility);
    ActorReference service =
        ActorReference.service(
            new IssuerId(authentication.issuer()), properties.getTransportWorkerClientId());
    ExecutionContext serviceContext =
        contexts.forBackgroundWork(
            service,
            scope,
            initiatingContext.correlationId(),
            Optional.of(new CausationId(cancellationFactId.toString())));
    var command = mapper.createObjectNode();
    command.put("transferId", transferId.toString());
    command.put("transportRequestId", transportRequestId.toString());
    command.put("cancellationFactId", cancellationFactId.toString());
    command.put("reasonCode", reasonCode);
    transport.request(
        serviceContext,
        transportRequestId,
        initiatingActor,
        reasonCode,
        "transfer-cancellation-" + cancellationFactId,
        RequestFingerprint.from(command));
  }

  @Override
  public Optional<CancellationOutcome> getOutcome(
      ExecutionContext initiatingContext,
      InternalId transferId,
      InternalId transportRequestId,
      ExternalReference originOrganization,
      ExternalReference originFacility) {
    var scope = RequestedExecutionScope.facility(originOrganization, originFacility);
    ActorReference service =
        ActorReference.service(
            new IssuerId(authentication.issuer()), properties.getTransportWorkerClientId());
    ExecutionContext serviceContext =
        contexts.forBackgroundWork(
            service, scope, initiatingContext.correlationId(), initiatingContext.causationId());
    return transport
        .getOutcome(serviceContext, transferId, transportRequestId)
        .map(
            result ->
                new CancellationOutcome(
                    result.transferId(),
                    result.transportRequestId(),
                    result.cancellationOperationId(),
                    OperationState.valueOf(result.state().name()),
                    result.outcome().map(value -> Outcome.valueOf(value.name())),
                    result.resolutionEventId(),
                    result.sourceNamespace(),
                    result.sourceObservationId(),
                    result.sourceOccurredAt(),
                    result.resolvedAt(),
                    result.correlationId()));
  }
}
