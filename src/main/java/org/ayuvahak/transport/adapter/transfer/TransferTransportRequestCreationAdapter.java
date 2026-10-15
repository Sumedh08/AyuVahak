package org.ayuvahak.transport.adapter.transfer;

import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.application.TransportRequestCreationPort;
import org.ayuvahak.transport.application.TransportRequestCommands;
import org.springframework.stereotype.Component;

/** Bridges Transfer's owner-facing request port to the Transport application boundary. */
@Component
public final class TransferTransportRequestCreationAdapter implements TransportRequestCreationPort {

  private final TransportRequestCommands commands;

  public TransferTransportRequestCreationAdapter(TransportRequestCommands commands) {
    this.commands = java.util.Objects.requireNonNull(commands, "Transport request commands");
  }

  @Override
  public Result create(
      ExecutionContext serviceContext,
      InternalId transferId,
      ActorReference initiatingActor,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    var result =
        commands.create(serviceContext, transferId, initiatingActor, idempotencyKey, fingerprint);
    return new Result(
        result.transportRequestId(),
        result.transferId(),
        result.status(),
        result.coordinationOutcome(),
        result.commandReplayed());
  }
}
