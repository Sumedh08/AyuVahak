package org.ayuvahak.transfer.adapter.transport;

import java.util.Optional;
import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CausationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.application.TransferStore;
import org.ayuvahak.transfer.application.TransportCancellationPort;
import org.ayuvahak.transfer.application.TransportCancellationPort.CancellationOutcome;
import org.ayuvahak.transfer.application.TransportCancellationPort.OperationState;
import org.ayuvahak.transfer.application.TransportCancellationPort.Outcome;
import org.ayuvahak.transport.application.TransportCancellationCommands;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Adapts Transfer's selected-link decision to Transport's scoped owner command. */
@Component
public final class TransferCancellationAdapter implements TransportCancellationPort {

  private final TransferStore transfers;
  private final TransportCancellationCommands transport;
  private final ExecutionContextFactory contexts;
  private final AuthenticationProperties authentication;
  private final org.ayuvahak.transfer.adapter.configuration.TransferProperties properties;
  private final ObjectMapper mapper;

  public TransferCancellationAdapter(
      TransferStore transfers,
      TransportCancellationCommands transport,
      ExecutionContextFactory contexts,
      AuthenticationProperties authentication,
      org.ayuvahak.transfer.adapter.configuration.TransferProperties properties,
      ObjectMapper mapper) {
    this.transfers = transfers;
    this.transport = transport;
    this.contexts = contexts;
    this.authentication = authentication;
    this.properties = properties;
    this.mapper = mapper;
  }

  @Override
  public boolean requestIfSelected(
      ExecutionContext initiatingContext,
      InternalId transferId,
      ActorReference initiatingActor,
      InternalId cancellationFactId,
      String reasonCode) {
    Optional<InternalId> selected = transfers.findSelectedTransportRequest(transferId);
    if (selected.isEmpty()) return false;
    var target =
        transfers
            .findProgressTarget(transferId)
            .orElseThrow(() -> new IllegalStateException("Selected Transfer was not found"));
    var scope =
        RequestedExecutionScope.facility(
            target.originOrganization(),
            target
                .originFacility()
                .orElseThrow(
                    () -> new IllegalStateException("Selected Transfer has no origin Facility")));
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
    command.put("transportRequestId", selected.orElseThrow().toString());
    command.put("cancellationFactId", cancellationFactId.toString());
    command.put("reasonCode", reasonCode);
    transport.request(
        serviceContext,
        selected.orElseThrow(),
        initiatingActor,
        reasonCode,
        "transfer-cancellation-" + cancellationFactId,
        RequestFingerprint.from(command));
    return true;
  }

  @Override
  public Optional<CancellationOutcome> getOutcomeIfSelected(
      ExecutionContext initiatingContext, InternalId transferId) {
    Optional<InternalId> selected = transfers.findSelectedTransportRequest(transferId);
    if (selected.isEmpty()) return Optional.empty();
    var target =
        transfers
            .findProgressTarget(transferId)
            .orElseThrow(() -> new IllegalStateException("Selected Transfer was not found"));
    var scope =
        RequestedExecutionScope.facility(
            target.originOrganization(),
            target
                .originFacility()
                .orElseThrow(
                    () -> new IllegalStateException("Selected Transfer has no origin Facility")));
    ActorReference service =
        ActorReference.service(
            new IssuerId(authentication.issuer()), properties.getTransportWorkerClientId());
    ExecutionContext serviceContext =
        contexts.forBackgroundWork(
            service, scope, initiatingContext.correlationId(), initiatingContext.causationId());
    return transport
        .getOutcome(serviceContext, transferId, selected.orElseThrow())
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
