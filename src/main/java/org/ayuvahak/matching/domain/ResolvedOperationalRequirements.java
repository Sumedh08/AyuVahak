package org.ayuvahak.matching.domain;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Resolved capability mapping retained as evidence for one Matching evaluation. */
public record ResolvedOperationalRequirements(
    String mappingCode,
    long mappingVersion,
    Map<String, List<String>> dimensionsByCapability,
    List<String> uniqueDimensionCodes,
    List<String> missingCapabilityCodes) {

  public ResolvedOperationalRequirements {
    Objects.requireNonNull(mappingCode, "mapping code");
    if (mappingVersion < 1) {
      throw new IllegalArgumentException("mapping version must be positive");
    }
    Objects.requireNonNull(dimensionsByCapability, "dimensions by capability");
    TreeMap<String, List<String>> normalized = new TreeMap<>();
    dimensionsByCapability.forEach(
        (capability, dimensions) -> normalized.put(capability, List.copyOf(dimensions)));
    dimensionsByCapability = Collections.unmodifiableMap(normalized);
    uniqueDimensionCodes = List.copyOf(uniqueDimensionCodes);
    missingCapabilityCodes = List.copyOf(missingCapabilityCodes);
  }

  public boolean complete() {
    return missingCapabilityCodes.isEmpty();
  }
}
