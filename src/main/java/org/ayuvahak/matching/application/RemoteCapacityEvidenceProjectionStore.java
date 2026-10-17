package org.ayuvahak.matching.application;

import java.util.Optional;
import org.ayuvahak.matching.domain.RemoteCapacityEvidence;
import org.ayuvahak.matching.domain.RemoteFacilityReference;

/** Matching-owned bounded projection of evidence that remains authoritative at a remote peer. */
public interface RemoteCapacityEvidenceProjectionStore {

  RecordResult record(RemoteCapacityEvidence evidence);

  Optional<RemoteCapacityEvidence> find(RemoteFacilityReference facility, String dimensionCode);

  enum RecordResult {
    APPLIED,
    DUPLICATE,
    STALE_IGNORED,
    CONFLICT
  }
}
