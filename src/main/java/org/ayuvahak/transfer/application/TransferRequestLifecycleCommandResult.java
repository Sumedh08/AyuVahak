package org.ayuvahak.transfer.application;

import org.ayuvahak.transfer.domain.TransferRequestAttempt;

/** Result of an idempotent Transfer request withdrawal or expiry command. */
public record TransferRequestLifecycleCommandResult(
    TransferRequestAttempt attempt, boolean commandReplayed) {}
