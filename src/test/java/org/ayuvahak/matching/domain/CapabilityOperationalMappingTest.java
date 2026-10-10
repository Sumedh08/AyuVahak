package org.ayuvahak.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CapabilityOperationalMappingTest {

  @Test
  void resolvesSharedDimensionsOnceInStableOrderAndKeepsMappingAttribution() {
    CapabilityOperationalMapping mapping =
        new CapabilityOperationalMapping(
            "SYNTHETIC_MAPPING",
            3,
            Map.of("SYN-NEURO", Set.of("SYN-TEAM", "SYN-OR"), "SYN-EMERGENCY", Set.of("SYN-OR")));

    ResolvedOperationalRequirements resolved =
        mapping.resolve(List.of("SYN-NEURO", "SYN-EMERGENCY"));

    assertThat(resolved.complete()).isTrue();
    assertThat(resolved.mappingCode()).isEqualTo("SYNTHETIC_MAPPING");
    assertThat(resolved.mappingVersion()).isEqualTo(3);
    assertThat(resolved.dimensionsByCapability())
        .containsEntry("SYN-NEURO", List.of("SYN-OR", "SYN-TEAM"))
        .containsEntry("SYN-EMERGENCY", List.of("SYN-OR"));
    assertThat(resolved.uniqueDimensionCodes()).containsExactly("SYN-OR", "SYN-TEAM");
  }

  @Test
  void missingMappingRemainsExplicitAndCannotBecomeAnEmptyRequirement() {
    CapabilityOperationalMapping mapping =
        new CapabilityOperationalMapping(
            "SYNTHETIC_MAPPING", 1, Map.of("SYN-NEURO", Set.of("SYN-OR")));

    ResolvedOperationalRequirements resolved = mapping.resolve(List.of("SYN-NEURO", "SYN-TRAUMA"));

    assertThat(resolved.complete()).isFalse();
    assertThat(resolved.missingCapabilityCodes()).containsExactly("SYN-TRAUMA");
    assertThat(resolved.uniqueDimensionCodes()).containsExactly("SYN-OR");
  }

  @Test
  void mappingAndResolutionAreDefensivelyImmutable() {
    var source = new java.util.HashMap<String, Set<String>>();
    source.put("SYN-NEURO", new java.util.HashSet<>(Set.of("SYN-OR")));
    CapabilityOperationalMapping mapping =
        new CapabilityOperationalMapping("SYNTHETIC_MAPPING", 1, source);
    source.get("SYN-NEURO").add("SYN-ALTERED");

    ResolvedOperationalRequirements resolved = mapping.resolve(List.of("SYN-NEURO"));

    assertThat(resolved.uniqueDimensionCodes()).containsExactly("SYN-OR");
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> resolved.uniqueDimensionCodes().add("SYN-ALTERED"));
  }

  @Test
  void rejectsInvalidVersionsAndMalformedOrEmptyMappings() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new CapabilityOperationalMapping("SYNTHETIC_MAPPING", 0, Map.of()));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new CapabilityOperationalMapping("bad code", 1, Map.of()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new CapabilityOperationalMapping(
                    "SYNTHETIC_MAPPING", 1, Map.of("SYN-NEURO", Set.of())));
  }
}
