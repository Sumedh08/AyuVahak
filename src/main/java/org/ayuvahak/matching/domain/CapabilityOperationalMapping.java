package org.ayuvahak.matching.domain;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Immutable policy mapping from explicit care capabilities to generic capacity dimensions. */
public record CapabilityOperationalMapping(
    String mappingCode, long version, Map<String, Set<String>> dimensionsByCapability) {

  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9._-]{0,63}");

  public CapabilityOperationalMapping {
    requireCode(mappingCode, "mapping code");
    if (version < 1) {
      throw new IllegalArgumentException("mapping version must be positive");
    }
    Objects.requireNonNull(dimensionsByCapability, "dimensions by capability");
    TreeMap<String, Set<String>> normalized = new TreeMap<>();
    dimensionsByCapability.forEach(
        (capability, dimensions) -> {
          requireCode(capability, "CareCapability code");
          Objects.requireNonNull(dimensions, "mapped capacity dimensions");
          if (dimensions.isEmpty()) {
            throw new IllegalArgumentException("a configured capability mapping cannot be empty");
          }
          TreeSet<String> sortedDimensions = new TreeSet<>();
          for (String dimension : dimensions) {
            requireCode(dimension, "CapacityDimension code");
            sortedDimensions.add(dimension);
          }
          normalized.put(capability, Collections.unmodifiableSet(sortedDimensions));
        });
    dimensionsByCapability = Collections.unmodifiableMap(normalized);
  }

  public ResolvedOperationalRequirements resolve(List<String> requiredCapabilityCodes) {
    Objects.requireNonNull(requiredCapabilityCodes, "required capability codes");
    if (requiredCapabilityCodes.isEmpty()) {
      throw new IllegalArgumentException("at least one required CareCapability must be provided");
    }

    TreeSet<String> requested = new TreeSet<>();
    requiredCapabilityCodes.forEach(
        code -> {
          requireCode(code, "CareCapability code");
          requested.add(code);
        });

    TreeMap<String, List<String>> resolved = new TreeMap<>();
    TreeSet<String> missing = new TreeSet<>();
    TreeSet<String> dimensions = new TreeSet<>();
    for (String capability : requested) {
      Set<String> mappedDimensions = dimensionsByCapability.get(capability);
      if (mappedDimensions == null) {
        missing.add(capability);
        continue;
      }
      List<String> ordered = List.copyOf(mappedDimensions);
      resolved.put(capability, ordered);
      dimensions.addAll(mappedDimensions);
    }
    return new ResolvedOperationalRequirements(
        mappingCode, version, resolved, List.copyOf(dimensions), List.copyOf(missing));
  }

  private static void requireCode(String value, String label) {
    if (value == null || !CODE.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be a stable uppercase identifier");
    }
  }
}
