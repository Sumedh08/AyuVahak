package org.ayuvahak.transfer.application;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Narrow owner contract for Analytics to request approved, bounded Transfer measures. */
public interface TransferReportingSnapshotPort {

  String OWNER_CONTRACT_VERSION = "transfer-reporting-v1";

  /** Returns or recovers an immutable owner-produced snapshot for this request identity. */
  Snapshot snapshot(ExecutionContext context, SnapshotRequest request);

  enum Measure {
    ACCEPTED("TRANSFER_ACCEPTED"),
    COMPLETED("TRANSFER_COMPLETED"),
    UNFULFILLED("TRANSFER_UNFULFILLED");

    private final String key;

    Measure(String key) {
      this.key = key;
    }

    public String key() {
      return key;
    }
  }

  enum Attribution {
    ORIGIN,
    DESTINATION
  }

  enum Completeness {
    COMPLETE,
    PARTIAL,
    MISSING_SOURCE,
    STALE_SOURCE,
    NOT_APPLICABLE
  }

  record SnapshotRequest(
      InternalId requestId,
      Measure measure,
      Attribution attribution,
      Instant periodStart,
      Instant periodEnd) {
    public SnapshotRequest {
      Objects.requireNonNull(requestId, "requestId");
      Objects.requireNonNull(measure, "measure");
      Objects.requireNonNull(attribution, "attribution");
      Objects.requireNonNull(periodStart, "periodStart");
      Objects.requireNonNull(periodEnd, "periodEnd");
      if (!periodEnd.isAfter(periodStart)) {
        throw new IllegalArgumentException(
            "reporting period must be a non-empty half-open interval");
      }
    }
  }

  /** Contains only a scalar measure and bounded attribution metadata; it has no case identity. */
  record Snapshot(
      InternalId sourceSnapshotId,
      String measureKey,
      Attribution attribution,
      ExecutionScope scope,
      Instant periodStart,
      Instant periodEnd,
      Instant sourceCutoff,
      String ownerContractVersion,
      Completeness completeness,
      long value,
      Map<String, String> dimensions,
      Instant generatedAt) {
    public Snapshot {
      Objects.requireNonNull(sourceSnapshotId, "sourceSnapshotId");
      Objects.requireNonNull(measureKey, "measureKey");
      Objects.requireNonNull(attribution, "attribution");
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(periodStart, "periodStart");
      Objects.requireNonNull(periodEnd, "periodEnd");
      Objects.requireNonNull(sourceCutoff, "sourceCutoff");
      Objects.requireNonNull(ownerContractVersion, "ownerContractVersion");
      Objects.requireNonNull(completeness, "completeness");
      dimensions = Map.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
      Objects.requireNonNull(generatedAt, "generatedAt");
      if (value < 0 || !periodEnd.isAfter(periodStart)) {
        throw new IllegalArgumentException("snapshot value and reporting interval are invalid");
      }
    }
  }
}
