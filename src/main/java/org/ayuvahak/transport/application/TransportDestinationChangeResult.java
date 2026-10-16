package org.ayuvahak.transport.application;

import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.transport.domain.TransportDestinationChangeOperation;

public record TransportDestinationChangeResult(
    TransportDestinationChangeOperation operation, boolean commandReplayed) {

  public TransportDestinationChangeResult {
    Objects.requireNonNull(operation, "operation");
  }

  public InternalId operationId() {
    return operation.id();
  }
}
