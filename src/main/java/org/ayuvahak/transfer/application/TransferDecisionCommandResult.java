package org.ayuvahak.transfer.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.TransferDecision;

/** Stable result of recording or replaying an explicit receiving decision. */
public record TransferDecisionCommandResult(
    TransferDecision decision, Optional<CoordinatedTransfer> transfer, boolean commandReplayed) {

  public TransferDecisionCommandResult {
    Objects.requireNonNull(decision, "decision");
    transfer = Objects.requireNonNull(transfer, "transfer");
    if ((decision.kind() == TransferDecision.Kind.ACCEPTED) != transfer.isPresent()) {
      throw new IllegalArgumentException("only Acceptance returns a Transfer");
    }
  }
}
