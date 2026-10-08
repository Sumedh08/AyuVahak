package org.ayuvahak.platform.idempotency;

import java.util.Objects;

/** The safe outcome when a command owner finds a completed receipt for the scoped key. */
public sealed interface CommandReceiptDecision
    permits CommandReceiptDecision.Replay, CommandReceiptDecision.KeyReused {

  static CommandReceiptDecision forRequest(
      CommandReceipt existing, RequestFingerprint requestedFingerprint) {
    Objects.requireNonNull(existing, "existing");
    Objects.requireNonNull(requestedFingerprint, "requestedFingerprint");
    return existing.fingerprint().equals(requestedFingerprint)
        ? new Replay(existing)
        : KeyReused.INSTANCE;
  }

  record Replay(CommandReceipt receipt) implements CommandReceiptDecision {
    public Replay {
      Objects.requireNonNull(receipt, "receipt");
    }
  }

  enum KeyReused implements CommandReceiptDecision {
    INSTANCE
  }
}
