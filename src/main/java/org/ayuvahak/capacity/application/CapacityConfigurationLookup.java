package org.ayuvahak.capacity.application;

import java.util.Optional;
import org.ayuvahak.capacity.domain.CapacityDimension;
import org.ayuvahak.capacity.domain.CapacityPolicy;

/** Read-only Capacity-owned view of the active dimension and policy versions. */
public interface CapacityConfigurationLookup {

  Optional<CapacityDimension> currentDimension(String code);

  Optional<CapacityPolicy> currentPolicy(String dimensionCode);
}
