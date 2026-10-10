package org.ayuvahak.transfer.application;

import java.util.List;
import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.TransferProgressCorrection;
import org.ayuvahak.transfer.domain.TransferProgressFact;

/** Bounded, Transfer-owned progress history for one authorized participant. */
public record TransferProgressHistory(
    InternalId journeyId,
    CoordinatedTransfer transfer,
    List<TransferProgressFact> facts,
    List<TransferProgressCorrection> corrections) {

  public TransferProgressHistory {
    Objects.requireNonNull(journeyId, "TransferJourney id");
    Objects.requireNonNull(transfer, "Transfer");
    facts = List.copyOf(Objects.requireNonNull(facts, "progress facts"));
    corrections = List.copyOf(Objects.requireNonNull(corrections, "progress corrections"));
  }
}
