package org.ayuvahak.transfer.application;

import java.util.Objects;
import org.ayuvahak.transfer.domain.TransferDestinationChangeOperation;

/** Safe result of creating or replaying a Transfer-owned destination-change intent. */
public record TransferDestinationChangeCommandResult(
    TransferDestinationChangeOperation operation, boolean commandReplayed) {

  public TransferDestinationChangeCommandResult {
    Objects.requireNonNull(operation, "destination-change operation");
  }
}
