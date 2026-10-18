package org.ayuvahak.analytics.application;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import org.ayuvahak.analytics.domain.Dhis2DataValueTuple;
import org.ayuvahak.analytics.domain.Dhis2ExportReconciliation;

/** Exact-target operator evidence; it contains no free text or remote endpoint. */
public record Dhis2ExportReconciliationRequest(
    UUID operationId,
    Dhis2DataValueTuple targetTuple,
    Dhis2ExportReconciliation.Resolution resolution,
    UUID evidenceReference,
    OptionalLong observedValue) {

  public Dhis2ExportReconciliationRequest {
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(targetTuple, "target tuple");
    Objects.requireNonNull(resolution, "resolution");
    Objects.requireNonNull(evidenceReference, "evidence reference");
    observedValue = Objects.requireNonNull(observedValue, "observed value");
    if ((resolution == Dhis2ExportReconciliation.Resolution.CONFIRMED_APPLIED)
        != observedValue.isPresent()) {
      throw new IllegalArgumentException("observed value does not match the resolution");
    }
  }
}
