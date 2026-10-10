package org.ayuvahak.transfer.application;

import java.util.Objects;
import org.ayuvahak.transfer.domain.TransferProgressFact;

/** Safe result of recording or replaying one Transfer progress command. */
public record TransferProgressCommandResult(TransferProgressFact fact, boolean commandReplayed) {

  public TransferProgressCommandResult {
    Objects.requireNonNull(fact, "progress fact");
  }
}
