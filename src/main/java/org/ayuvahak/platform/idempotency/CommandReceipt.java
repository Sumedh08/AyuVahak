package org.ayuvahak.platform.idempotency;

import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.RecordedAt;

/** Minimal completed command outcome retained by the command-owning context. */
public record CommandReceipt(
    RequestFingerprint fingerprint,
    int responseStatus,
    InternalId resultReference,
    RecordedAt recordedAt) {

  public CommandReceipt {
    Objects.requireNonNull(fingerprint, "fingerprint");
    if (responseStatus < 200 || responseStatus > 299) {
      throw new IllegalArgumentException("completed command receipt requires a successful status");
    }
    Objects.requireNonNull(resultReference, "resultReference");
    Objects.requireNonNull(recordedAt, "recordedAt");
  }

  @Override
  public String toString() {
    return "CommandReceipt[fingerprint=redacted, responseStatus="
        + responseStatus
        + ", resultReference=redacted"
        + ", recordedAt="
        + recordedAt
        + "]";
  }
}
