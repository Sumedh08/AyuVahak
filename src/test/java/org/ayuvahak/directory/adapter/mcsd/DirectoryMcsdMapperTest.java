package org.ayuvahak.directory.adapter.mcsd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.ayuvahak.directory.application.DirectorySourceProjection;
import org.ayuvahak.platform.fhir.FhirPayloadLimits;
import org.ayuvahak.platform.fhir.FhirProfileContext;
import org.ayuvahak.platform.fhir.FhirR4ParsedResource;
import org.ayuvahak.platform.fhir.FhirR4PayloadParser;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Endpoint;
import org.hl7.fhir.r4.model.HealthcareService;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.Test;

class DirectoryMcsdMapperTest {

  private static final String SOURCE = "urn:synthetic:mcsd:directory-a";
  private static final String PROFILE_BASE = "urn:synthetic:profile:mcsd:";
  private static final String MCSD_ORGANIZATION_PROFILE =
      "https://profiles.ihe.net/ITI/mCSD/StructureDefinition/IHE.mCSD.Organization|4.0.0";
  private static final String PHYSICAL_TYPE_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/location-physical-type";
  private final DirectoryMcsdMappingPolicy policy =
      new DirectoryMcsdMappingPolicy(
          Map.of(
              "Organization", Set.of(PROFILE_BASE + "organization"),
              "Location", Set.of(PROFILE_BASE + "location"),
              "HealthcareService", Set.of(PROFILE_BASE + "healthcare-service"),
              "Endpoint", Set.of(PROFILE_BASE + "endpoint")),
          Set.of(
              new DirectoryMcsdMappingPolicy.CodingKey("urn:synthetic:facility-kind", "care-site")),
          Set.of(
              new DirectoryMcsdMappingPolicy.OrderedLastUpdatedProfile(
                  "Organization", PROFILE_BASE + "organization"),
              new DirectoryMcsdMappingPolicy.OrderedLastUpdatedProfile(
                  "Location", PROFILE_BASE + "location"),
              new DirectoryMcsdMappingPolicy.OrderedLastUpdatedProfile(
                  "HealthcareService", PROFILE_BASE + "healthcare-service"),
              new DirectoryMcsdMappingPolicy.OrderedLastUpdatedProfile(
                  "Endpoint", PROFILE_BASE + "endpoint")));
  private final DirectoryMcsdMapper mapper = new DirectoryMcsdMapper(policy);

  @Test
  void mapsOrganizationToSourceScopedDirectoryOrganizationFact() {
    Organization source = new Organization();
    source.setId("org-a");
    source.setActive(true);
    source.setName("Synthetic health authority");
    source.setPartOf(new Reference("Organization/org-parent"));
    source.setMeta(meta("v2", "2026-10-14T10:15:30Z"));

    var mapped = mapper.map(parsed(source, "organization"), SOURCE);

    assertThat(mapped).isInstanceOf(DirectorySourceProjection.OrganizationFact.class);
    var organization = (DirectorySourceProjection.OrganizationFact) mapped;
    assertThat(organization.metadata().sourceIdentity().issuer().value()).isEqualTo(SOURCE);
    assertThat(organization.metadata().sourceIdentity().resourceType()).isEqualTo("Organization");
    assertThat(organization.metadata().sourceIdentity().value()).isEqualTo("org-a");
    assertThat(organization.name()).isEqualTo("Synthetic health authority");
    assertThat(organization.parentOrganization().orElseThrow().value()).isEqualTo("org-parent");
    assertThat(organization.active()).isTrue();
    assertThat(organization.metadata().opaqueVersionId()).contains("v2");
    assertThat(organization.metadata().sourceUpdatedAt())
        .contains(Instant.parse("2026-10-14T10:15:30Z"));
  }

  @Test
  void mapsConfiguredFacilityLocationAndDoesNotInferCapabilityOrCapacity() {
    Location source = new Location();
    source.setId("facility-a");
    source.setStatus(Location.LocationStatus.ACTIVE);
    source.setName("Synthetic receiving facility");
    source.setManagingOrganization(new Reference("Organization/org-a"));
    source.setPhysicalType(
        new CodeableConcept()
            .addCoding(new Coding("urn:synthetic:facility-kind", "care-site", null)));
    source.setPosition(
        new Location.LocationPositionComponent().setLongitude(12.5).setLatitude(-4.25));
    source.setMeta(meta("opaque-version-10", "2026-10-14T10:15:30Z"));

    var mapped = mapper.map(parsed(source, "location"), SOURCE);

    assertThat(mapped).isInstanceOf(DirectorySourceProjection.FacilityFact.class);
    var facility = (DirectorySourceProjection.FacilityFact) mapped;
    assertThat(facility.metadata().sourceIdentity().resourceType()).isEqualTo("Location");
    assertThat(facility.managingOrganization().value()).isEqualTo("org-a");
    assertThat(facility.coordinate())
        .contains(new org.ayuvahak.directory.domain.Wgs84Coordinate(12.5, -4.25));
    assertThat(facility.active()).isTrue();
    assertThat(facility.metadata().sourceIdentity().issuer().value()).isEqualTo(SOURCE);
  }

  @Test
  void mapsSubordinateLocationOnlyWhenItReferencesConfiguredFacilityLocation() {
    Location source = new Location();
    source.setId("ward-a");
    source.setStatus(Location.LocationStatus.ACTIVE);
    source.setName("Synthetic ward");
    source.setPartOf(new Reference("Location/facility-a"));
    source.setPhysicalType(
        new CodeableConcept().addCoding(new Coding(PHYSICAL_TYPE_SYSTEM, "wa", "Ward")));

    var mapped = mapper.map(parsed(source, "location"), SOURCE);

    assertThat(mapped).isInstanceOf(DirectorySourceProjection.LocationFact.class);
    var location = (DirectorySourceProjection.LocationFact) mapped;
    assertThat(location.metadata().sourceIdentity().value()).isEqualTo("ward-a");
    assertThat(location.facilityReference().value()).isEqualTo("facility-a");
    assertThat(location.coordinate()).isEmpty();
  }

  @Test
  void mapsHealthcareServiceWithoutTreatingItsTypeAsCapabilityEvidence() {
    HealthcareService source = new HealthcareService();
    source.setId("service-a");
    source.setActive(true);
    source.setName("Synthetic imaging service");
    source.setProvidedBy(new Reference("Organization/org-a"));
    source.addLocation(new Reference("Location/facility-a"));
    source.addType(
        new CodeableConcept().addCoding(new Coding("urn:synthetic:service", "ct", "CT")));
    source.setMeta(meta("7", "2026-10-14T10:15:30Z"));

    var mapped = mapper.map(parsed(source, "healthcare-service"), SOURCE);

    assertThat(mapped).isInstanceOf(DirectorySourceProjection.HealthcareServiceFact.class);
    var service = (DirectorySourceProjection.HealthcareServiceFact) mapped;
    assertThat(service.organizationReference().value()).isEqualTo("org-a");
    assertThat(service.facilityReference().value()).isEqualTo("facility-a");
    assertThat(service.name()).isEqualTo("Synthetic imaging service");
    assertThat(service.active()).isTrue();
    assertThat(service.metadata().sourceIdentity().resourceType()).isEqualTo("HealthcareService");
  }

  @Test
  void retainsEndpointAddressOnlyAsInertSourceMetadata() {
    Endpoint source = new Endpoint();
    source.setId("endpoint-a");
    source.setStatus(Endpoint.EndpointStatus.ACTIVE);
    source.setAddress("http://169.254.169.254/metadata");
    source.setManagingOrganization(new Reference("Organization/org-a"));
    source.setConnectionType(new Coding("urn:synthetic:connection", "fhir", "FHIR"));

    var mapped = mapper.map(parsed(source, "endpoint"), SOURCE);

    assertThat(mapped).isInstanceOf(DirectorySourceProjection.EndpointMetadata.class);
    var endpoint = (DirectorySourceProjection.EndpointMetadata) mapped;
    assertThat(endpoint.address()).isEqualTo("http://169.254.169.254/metadata");
    assertThat(endpoint.trustedForNetworkUse()).isFalse();
    assertThat(endpoint.managingOrganization().orElseThrow().value()).isEqualTo("org-a");
  }

  @Test
  void refusesUnclassifiedLocationInsteadOfGuessingFacilityOrSubLocation() {
    Location source = new Location();
    source.setId("location-a");
    source.setStatus(Location.LocationStatus.ACTIVE);
    source.setName("Synthetic location");
    source.setManagingOrganization(new Reference("Organization/org-a"));

    assertThatThrownBy(() -> mapper.map(parsed(source, "location"), SOURCE))
        .isInstanceOf(DirectoryMcsdMappingException.class)
        .hasMessage(
            "The source Location cannot be classified by the configured Directory profile.");
  }

  @Test
  void rejectsAbsoluteReferencesWithoutResolvingOrImportingTheirHost() {
    HealthcareService source = new HealthcareService();
    source.setId("service-a");
    source.setActive(true);
    source.setName("Synthetic service");
    source.setProvidedBy(new Reference("https://attacker.example/fhir/Organization/org-a"));
    source.addLocation(new Reference("Location/facility-a"));

    assertThatThrownBy(() -> mapper.map(parsed(source, "healthcare-service"), SOURCE))
        .isInstanceOf(DirectoryMcsdMappingException.class)
        .hasMessage("The source reference is not a supported local resource reference.");
  }

  @Test
  void rejectsUnconfiguredProfileAndNonR4Resource() {
    Organization source = new Organization();
    source.setId("org-a");
    source.setName("Synthetic organization");

    assertThatThrownBy(
            () ->
                mapper.map(
                    new FhirR4ParsedResource(
                        source, "4.0.1", "urn:synthetic:profile:mcsd:unconfigured"),
                    SOURCE))
        .isInstanceOf(DirectoryMcsdMappingException.class)
        .hasMessage("The source profile is not configured for Directory import.");
    assertThatThrownBy(
            () ->
                mapper.map(
                    new FhirR4ParsedResource(source, "5.0.0", PROFILE_BASE + "organization"),
                    SOURCE))
        .isInstanceOf(DirectoryMcsdMappingException.class)
        .hasMessage("Directory mCSD import requires the configured FHIR R4 release.");
  }

  @Test
  void mapsAnOrganizationOnlyAfterThePinnedMcsdProfileParserValidatesIt() {
    FhirR4PayloadParser parser =
        FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults());
    String payload =
        """
        {"resourceType":"Organization","id":"org-a",
         "meta":{"profile":["%s"]},
         "identifier":[{"system":"urn:ayuvahak:synthetic-orgs","value":"SYN-ORG-001"}],
         "active":true,
         "type":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/organization-type","code":"dept","display":"Department"}]}],
         "name":"Synthetic Organization"}
        """
            .formatted(MCSD_ORGANIZATION_PROFILE);
    var parsed =
        parser.parse(
            payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "application/fhir+json",
            new FhirProfileContext("4.0.1", MCSD_ORGANIZATION_PROFILE));
    DirectoryMcsdMapper mcsdMapper =
        new DirectoryMcsdMapper(
            new DirectoryMcsdMappingPolicy(
                Map.of("Organization", Set.of(MCSD_ORGANIZATION_PROFILE)), Set.of(), Set.of()));

    var mapped = mcsdMapper.map(parsed, SOURCE);

    assertThat(parsed.profileUri()).isEqualTo(MCSD_ORGANIZATION_PROFILE);
    assertThat(mapped).isInstanceOf(DirectorySourceProjection.OrganizationFact.class);
    assertThat(((DirectorySourceProjection.OrganizationFact) mapped).name())
        .isEqualTo("Synthetic Organization");
  }

  private static FhirR4ParsedResource parsed(
      org.hl7.fhir.r4.model.Resource resource, String profile) {
    return new FhirR4ParsedResource(resource, "4.0.1", PROFILE_BASE + profile);
  }

  private static Meta meta(String version, String lastUpdated) {
    Meta meta = new Meta();
    meta.setVersionId(version);
    meta.setLastUpdated(java.util.Date.from(Instant.parse(lastUpdated)));
    return meta;
  }
}
