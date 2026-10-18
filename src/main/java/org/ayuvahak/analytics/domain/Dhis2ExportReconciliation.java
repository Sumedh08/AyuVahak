package org.ayuvahak.analytics.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/** Auditable, reference-only operator evidence resolving one ambiguous target-tuple operation. */
public record Dhis2ExportReconciliation(
    UUID operationId,
    Resolution resolution,
    UUID evidenceReference,
    OptionalLong observedValue,
    UUID auditRecordId,
    Instant resolvedAt) {

  public Dhis2ExportReconciliation {
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(resolution, "resolution");
    Objects.requireNonNull(evidenceReference, "evidence reference");
    observedValue = Objects.requireNonNull(observedValue, "observed value");
    Objects.requireNonNull(auditRecordId, "audit record id");
    Objects.requireNonNull(resolvedAt, "resolved at");
    if ((resolution == Resolution.CONFIRMED_APPLIED) != observedValue.isPresent()) {
      throw new IllegalArgumentException("reconciliation value does not match its resolution");
    }
  }

  @Override
  public String toString() {
    return "Dhis2ExportReconciliation[operationId="
        + operationId
        + ", resolution="
        + resolution
        + ", evidenceReference=redacted, observedValue="
        + observedValue.isPresent()
        + "]";
  }

  public enum Resolution {
    CONFIRMED_APPLIED,
    CONFIRMED_NOT_APPLIED_AND_QUIESCED
  }
}
