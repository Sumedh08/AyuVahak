package org.ayuvahak.capacity.application;

import java.util.Optional;
import org.ayuvahak.capacity.domain.CapacityObservation;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Capacity-owned append and lookup boundary for immutable observations and corrections. */
public interface CapacityObservationStore {

  void lockSourceIdentity(String sourceCode, ExternalReference sourceObservation);

  Optional<CapacityObservation> findBySourceIdentity(
      String sourceCode, ExternalReference sourceObservation);

  Optional<CapacityObservation> lockObservation(InternalId observationId);

  boolean isSuperseded(InternalId observationId);

  void append(CapacityObservation observation);

  void appendCorrection(
      CapacityObservation observation, InternalId supersededObservationId, String correctionKind);
}
