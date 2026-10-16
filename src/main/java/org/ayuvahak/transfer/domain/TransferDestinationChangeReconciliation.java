package org.ayuvahak.transfer.domain;

import java.time.Instant;
import java.util.Objects;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;

/** Attributed operator evidence resolving a destination-change exception without changing route. */
public record TransferDestinationChangeReconciliation(
    InternalId operationId,
    Resolution resolution,
    String evidenceReference,
    Instant occurredAt,
    Instant recordedAt,
    ActorReference recordedBy,
    InternalId organizationId,
    InternalId facilityId,
    CorrelationId correlationId,
    String requestFingerprint) {

  public TransferDestinationChangeReconciliation {
    Objects.requireNonNull(operationId, "destination-change operation id");
    Objects.requireNonNull(resolution, "reconciliation resolution");
    if (evidenceReference == null || !evidenceReference.matches("[A-Za-z0-9:/._-]{1,128}")) {
      throw new IllegalArgumentException("destination-change evidence reference is invalid");
    }
    Objects.requireNonNull(occurredAt, "evidence occurrence time");
    Objects.requireNonNull(recordedAt, "recorded time");
    Objects.requireNonNull(recordedBy, "recording actor");
    if (recordedBy.kind() != org.ayuvahak.platform.core.ActorKind.HUMAN) {
      throw new IllegalArgumentException("destination-change reconciliation requires a human");
    }
    Objects.requireNonNull(organizationId, "organization scope");
    Objects.requireNonNull(facilityId, "facility scope");
    Objects.requireNonNull(correlationId, "correlation id");
    if (requestFingerprint == null || !requestFingerprint.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("destination-change fingerprint is invalid");
    }
  }

  public enum Resolution {
    RETAIN_OLD_DESTINATION
  }
}
