package org.ayuvahak.transport.application;

import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.transport.domain.TransportDestinationChangeOperation;

public record TransportDestinationChangeResult(
    TransportDestinationChangeOperation operation,
    boolean commandReplayed,
    boolean replayedEvidenceRequiresReconciliation) {

  public TransportDestinationChangeResult(
      TransportDestinationChangeOperation operation, boolean commandReplayed) {
    this(operation, commandReplayed, false);
  }

  public TransportDestinationChangeResult {
    Objects.requireNonNull(operation, "operation");
  }

  public InternalId operationId() {
    return operation.id();
  }
}
