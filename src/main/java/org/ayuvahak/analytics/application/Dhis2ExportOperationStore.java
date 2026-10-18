package org.ayuvahak.analytics.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.domain.Dhis2ExportOperation;
import org.ayuvahak.analytics.domain.Dhis2ExportState;
import org.ayuvahak.analytics.domain.Dhis2MappingVersion;
import org.ayuvahak.analytics.domain.ReportingPeriod;

/**
 * Durable metadata boundary for one-shot DHIS2 operations; no tuple serialization is owned here.
 */
public interface Dhis2ExportOperationStore {

  CreateResult createOrReplay(Dhis2ExportOperation operation);

  Optional<Dhis2ExportOperation> find(UUID operationId);

  /**
   * Acquires a transaction-scoped lock for this organization's export history and reports whether
   * an overlapping prior release cannot be represented by the current source-cell equations. The
   * caller must invoke this inside the same transaction that reserves the shared disclosure gate,
   * so concurrent overlapping exports cannot both pass the history check.
   */
  boolean hasUnrepresentableOverlappingRelease(
      Dhis2MappingVersion mapping, ReportingPeriod period, UUID currentOperationId);

  Dhis2ExportOperation update(
      UUID operationId,
      Dhis2ExportState expectedState,
      Dhis2ExportState state,
      String resultClass,
      int attemptCount,
      Optional<Integer> partnerConfigurationVersion,
      Optional<Dhis2ExportOperation.Dhis2ImportCounts> importCounts,
      Optional<UUID> disclosureReservationId,
      Instant changedAt,
      UUID auditRecordId);

  record CreateResult(Dhis2ExportOperation operation, boolean created) {}
}
