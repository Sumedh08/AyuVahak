package org.ayuvahak.capacity.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.capacity.domain.CapacityObservationFact;
import org.ayuvahak.platform.core.ExternalReference;

/** Capacity-owned read boundary for immutable evidence and its correction chain. */
public interface CapacityEvidenceStore {

  List<CapacityObservationFact> findForProjection(ExternalReference facility, String dimensionCode);

  List<CapacityObservationFact> findHistory(
      ExternalReference facility,
      Optional<String> dimensionCode,
      Optional<String> sourceCode,
      Optional<Instant> observedFromInclusive,
      Optional<Instant> observedUntilExclusive,
      int limit,
      long offset);
}
