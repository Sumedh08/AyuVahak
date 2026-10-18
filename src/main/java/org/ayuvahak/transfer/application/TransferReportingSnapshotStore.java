package org.ayuvahak.transfer.application;

import java.time.Instant;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Transfer-owned persistence seam for immutable reporting snapshots. */
public interface TransferReportingSnapshotStore {

  TransferReportingSnapshotPort.Snapshot createOrReplay(
      ExecutionScope scope,
      TransferReportingSnapshotPort.SnapshotRequest request,
      Instant generatedAt);
}
