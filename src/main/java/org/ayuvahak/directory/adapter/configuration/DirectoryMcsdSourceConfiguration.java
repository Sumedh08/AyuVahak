package org.ayuvahak.directory.adapter.configuration;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.ayuvahak.directory.adapter.mcsd.DirectoryMcsdMapper;
import org.ayuvahak.directory.adapter.mcsd.DirectoryMcsdMappingPolicy;
import org.ayuvahak.directory.adapter.mcsd.DirectoryMcsdPartnerBindings;
import org.ayuvahak.platform.fhir.FhirPayloadLimits;
import org.ayuvahak.platform.fhir.FhirR4PayloadParser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runtime wiring for Directory-owned mCSD mapping and its locally configured source bindings. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DirectoryMcsdSourceProperties.class)
public class DirectoryMcsdSourceConfiguration {

  private static final String PROFILE_BASE =
      "https://profiles.ihe.net/ITI/mCSD/StructureDefinition/IHE.mCSD.";
  private static final String PROFILE_VERSION = "|4.0.0";

  @Bean
  DirectoryMcsdPartnerBindings directoryMcsdPartnerBindings(
      DirectoryMcsdSourceProperties properties) {
    return new DirectoryMcsdPartnerBindings(properties.getSourceBindings());
  }

  @Bean("directoryFhirR4PayloadParser")
  FhirR4PayloadParser directoryFhirR4PayloadParser() {
    return FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults());
  }

  @Bean
  DirectoryMcsdMappingPolicy directoryMcsdMappingPolicy(DirectoryMcsdSourceProperties properties) {
    Map<String, Set<String>> profiles =
        Map.of(
            "Organization",
            Set.of(profile("Organization"), profile("FacilityOrganization")),
            "Location",
            Set.of(profile("Location"), profile("FacilityLocation")),
            "HealthcareService",
            Set.of(profile("HealthcareService")),
            "Endpoint",
            Set.of(profile("Endpoint")));
    Set<DirectoryMcsdMappingPolicy.CodingKey> facilityTypes =
        properties.getFacilityLocationTypes().stream()
            .map(value -> new DirectoryMcsdMappingPolicy.CodingKey(value.system(), value.code()))
            .collect(Collectors.toUnmodifiableSet());
    Set<DirectoryMcsdMappingPolicy.OrderedLastUpdatedProfile> orderedProfiles =
        properties.getOrderedLastUpdatedProfiles().stream()
            .map(
                value ->
                    new DirectoryMcsdMappingPolicy.OrderedLastUpdatedProfile(
                        value.resourceType(), value.profileUri()))
            .collect(Collectors.toUnmodifiableSet());
    return new DirectoryMcsdMappingPolicy(profiles, facilityTypes, orderedProfiles);
  }

  @Bean
  DirectoryMcsdMapper directoryMcsdMapper(DirectoryMcsdMappingPolicy policy) {
    return new DirectoryMcsdMapper(policy);
  }

  private static String profile(String name) {
    return PROFILE_BASE + name + PROFILE_VERSION;
  }
}
