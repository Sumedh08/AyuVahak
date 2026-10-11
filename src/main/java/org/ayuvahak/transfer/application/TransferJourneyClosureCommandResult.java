package org.ayuvahak.transfer.application;

import org.ayuvahak.transfer.domain.TransferJourneyClosure;

public record TransferJourneyClosureCommandResult(
    TransferJourneyClosure closure, boolean commandReplayed) {}
