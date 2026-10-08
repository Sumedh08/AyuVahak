package org.ayuvahak.platform.audit.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Stable keyset position for descending audit chronology. */
public record AuditRecordPosition(Instant recordedAt, UUID id) {

  public AuditRecordPosition {
    Objects.requireNonNull(recordedAt, "recordedAt");
    Objects.requireNonNull(id, "id");
  }
}
