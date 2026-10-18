package org.ayuvahak.analytics.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Minimal durable metadata for one aggregate export attempt; no payload is archived. */
public record Dhis2ExportOperation(
    UUID operationId,
    String fingerprint,
    UUID snapshotId,
    UUID revisionSeriesId,
    int revisionNumber,
    String mappingKey,
    int mappingVersion,
    String partnerKey,
    Optional<Integer> partnerConfigurationVersion,
    Dhis2DataValueTuple targetTuple,
    Optional<String> dataSetUid,
    Optional<UUID> disclosureReservationId,
    Dhis2ExportState state,
    String resultClass,
    int attemptCount,
    Optional<Dhis2ImportCounts> importCounts,
    Optional<UUID> auditRecordId,
    Instant createdAt,
    Instant updatedAt) {

  public Dhis2ExportOperation {
    Objects.requireNonNull(operationId, "operation id");
    if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("export operation fingerprint is invalid");
    }
    Objects.requireNonNull(snapshotId, "snapshot id");
    Objects.requireNonNull(revisionSeriesId, "revision series id");
    Objects.requireNonNull(mappingKey, "mapping key");
    Objects.requireNonNull(partnerKey, "partner key");
    partnerConfigurationVersion =
        Objects.requireNonNull(partnerConfigurationVersion, "partner configuration version");
    Objects.requireNonNull(targetTuple, "target tuple");
    dataSetUid = Objects.requireNonNull(dataSetUid, "data set UID");
    disclosureReservationId =
        Objects.requireNonNull(disclosureReservationId, "disclosure reservation id");
    Objects.requireNonNull(state, "operation state");
    if (resultClass == null || !resultClass.matches("[A-Z][A-Z0-9_]{0,47}")) {
      throw new IllegalArgumentException("export result class is invalid");
    }
    if (revisionNumber < 1 || mappingVersion < 1 || attemptCount < 0 || attemptCount > 1) {
      throw new IllegalArgumentException("export operation version or attempt is invalid");
    }
    importCounts = Objects.requireNonNull(importCounts, "import counts");
    auditRecordId = Objects.requireNonNull(auditRecordId, "audit record id");
    Objects.requireNonNull(createdAt, "created at");
    Objects.requireNonNull(updatedAt, "updated at");
  }

  @Override
  public String toString() {
    return "Dhis2ExportOperation[operationId="
        + operationId
        + ", snapshotId="
        + snapshotId
        + ", revision="
        + revisionNumber
        + ", mapping="
        + mappingKey
        + "@"
        + mappingVersion
        + ", state="
        + state
        + ", resultClass="
        + resultClass
        + ", target=redacted]";
  }

  public record Dhis2ImportCounts(int imported, int updated, int ignored, int deleted) {
    public Dhis2ImportCounts {
      if (imported < 0
          || updated < 0
          || ignored < 0
          || deleted < 0
          || imported > 1
          || updated > 1
          || ignored > 1
          || deleted > 1) {
        throw new IllegalArgumentException("one-value import counts are invalid");
      }
    }
  }
}
