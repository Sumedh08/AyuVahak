package org.ayuvahak.directory.adapter.mcsd;

import ca.uhn.fhir.context.FhirVersionEnum;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.directory.application.DirectorySourceProjection;
import org.ayuvahak.directory.domain.Wgs84Coordinate;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.fhir.FhirR4ParsedResource;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Endpoint;
import org.hl7.fhir.r4.model.HealthcareService;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

/** Maps only the configured mCSD resource allowlist into Directory-owned projection facts. */
public final class DirectoryMcsdMapper {

  private static final String SUPPORTED_RELEASE = "4.0.1";
  private final DirectoryMcsdMappingPolicy policy;

  public DirectoryMcsdMapper(DirectoryMcsdMappingPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  public DirectorySourceProjection map(FhirR4ParsedResource parsed, String sourceNamespace) {
    Objects.requireNonNull(parsed, "parsed resource");
    if (!SUPPORTED_RELEASE.equals(parsed.fhirRelease())) {
      throw new DirectoryMcsdMappingException(
          "Directory mCSD import requires the configured FHIR R4 release.");
    }
    IssuerId issuer;
    try {
      issuer = new IssuerId(sourceNamespace);
    } catch (IllegalArgumentException invalidNamespace) {
      throw new DirectoryMcsdMappingException("The Directory source namespace is invalid.");
    }
    Resource resource = asR4Resource(parsed.resource());
    String resourceType = resource.fhirType();
    if (!policy.allowsProfile(resourceType, parsed.profileUri())) {
      throw new DirectoryMcsdMappingException(
          "The source profile is not configured for Directory import.");
    }
    String id = resource.getIdElement().getIdPart();
    if (id == null || !id.matches("[A-Za-z0-9.-]{1,64}")) {
      throw new DirectoryMcsdMappingException(
          "The source resource does not have a supported logical identifier.");
    }
    ExternalReference identity = new ExternalReference(issuer, resourceType, id);
    Optional<String> versionId =
        Optional.ofNullable(resource.getMeta().getVersionId()).filter(value -> !value.isBlank());
    Optional<Instant> sourceUpdatedAt =
        Optional.ofNullable(resource.getMeta().getLastUpdated()).map(java.util.Date::toInstant);
    return switch (resource) {
      case Organization organization ->
          mapOrganization(organization, identity, issuer, parsed, versionId, sourceUpdatedAt);
      case Location location ->
          mapLocation(location, identity, issuer, parsed, versionId, sourceUpdatedAt);
      case HealthcareService service ->
          mapHealthcareService(service, identity, issuer, parsed, versionId, sourceUpdatedAt);
      case Endpoint endpoint ->
          mapEndpoint(endpoint, identity, issuer, parsed, versionId, sourceUpdatedAt);
      default ->
          throw new DirectoryMcsdMappingException(
              "The FHIR resource type is not in the Directory mCSD allowlist.");
    };
  }

  private DirectorySourceProjection.OrganizationFact mapOrganization(
      Organization source,
      ExternalReference identity,
      IssuerId issuer,
      FhirR4ParsedResource parsed,
      Optional<String> versionId,
      Optional<Instant> sourceUpdatedAt) {
    if (!source.hasActive()) {
      throw statusMissing();
    }
    Optional<ExternalReference> parent =
        source.hasPartOf()
            ? Optional.of(localReference(source.getPartOf(), "Organization", issuer))
            : Optional.empty();
    String name = requiredName(source.getName());
    boolean active = source.getActive();
    return new DirectorySourceProjection.OrganizationFact(
        metadata(identity, versionId, sourceUpdatedAt, parsed.profileUri(), name, parent, active),
        name,
        parent,
        active);
  }

  private DirectorySourceProjection mapLocation(
      Location source,
      ExternalReference identity,
      IssuerId issuer,
      FhirR4ParsedResource parsed,
      Optional<String> versionId,
      Optional<Instant> sourceUpdatedAt) {
    if (!source.hasStatus()) {
      throw statusMissing();
    }
    String name = requiredName(source.getName());
    boolean active = source.getStatus() == Location.LocationStatus.ACTIVE;
    Optional<Wgs84Coordinate> coordinate = coordinate(source);
    if (source.hasPartOf()) {
      ExternalReference parent = localReference(source.getPartOf(), "Location", issuer);
      return new DirectorySourceProjection.LocationFact(
          metadata(
              identity,
              versionId,
              sourceUpdatedAt,
              parsed.profileUri(),
              name,
              parent,
              coordinate,
              active),
          name,
          parent,
          coordinate,
          active);
    }
    if (source.hasManagingOrganization()
        && source.hasPhysicalType()
        && isConfiguredFacilityType(source.getPhysicalType())) {
      ExternalReference organization =
          localReference(source.getManagingOrganization(), "Organization", issuer);
      return new DirectorySourceProjection.FacilityFact(
          metadata(
              identity,
              versionId,
              sourceUpdatedAt,
              parsed.profileUri(),
              name,
              organization,
              coordinate,
              active),
          name,
          organization,
          coordinate,
          active);
    }
    throw new DirectoryMcsdMappingException(
        "The source Location cannot be classified by the configured Directory profile.");
  }

  private DirectorySourceProjection.HealthcareServiceFact mapHealthcareService(
      HealthcareService source,
      ExternalReference identity,
      IssuerId issuer,
      FhirR4ParsedResource parsed,
      Optional<String> versionId,
      Optional<Instant> sourceUpdatedAt) {
    if (!source.hasActive()) {
      throw statusMissing();
    }
    if (!source.hasProvidedBy() || source.getLocation().size() != 1) {
      throw new DirectoryMcsdMappingException(
          "The source HealthcareService must identify one Organization and one Location.");
    }
    String name = requiredName(source.getName());
    ExternalReference organization = localReference(source.getProvidedBy(), "Organization", issuer);
    ExternalReference facility = localReference(source.getLocationFirstRep(), "Location", issuer);
    List<DirectorySourceProjection.Coding> serviceTypes = serviceTypes(source);
    boolean active = source.getActive();
    return new DirectorySourceProjection.HealthcareServiceFact(
        metadata(
            identity,
            versionId,
            sourceUpdatedAt,
            parsed.profileUri(),
            name,
            organization,
            facility,
            serviceTypes,
            active),
        name,
        organization,
        facility,
        serviceTypes,
        active);
  }

  private static List<DirectorySourceProjection.Coding> serviceTypes(HealthcareService source) {
    List<DirectorySourceProjection.Coding> result =
        source.getType().stream()
            .flatMap(
                concept -> {
                  if (!concept.hasCoding()) {
                    throw new DirectoryMcsdMappingException(
                        "A source HealthcareService type has no coded value.");
                  }
                  return concept.getCoding().stream();
                })
            .map(
                coding -> {
                  if (coding.getSystem() == null
                      || coding.getSystem().isBlank()
                      || coding.getCode() == null
                      || coding.getCode().isBlank()) {
                    throw new DirectoryMcsdMappingException(
                        "A source HealthcareService type is missing its system or code.");
                  }
                  return new DirectorySourceProjection.Coding(
                      coding.getSystem(),
                      coding.getCode(),
                      Optional.ofNullable(coding.getVersion()).filter(value -> !value.isBlank()));
                })
            .distinct()
            .sorted(
                java.util.Comparator.comparing(DirectorySourceProjection.Coding::system)
                    .thenComparing(DirectorySourceProjection.Coding::code)
                    .thenComparing(value -> value.version().orElse("")))
            .toList();
    if (result.size() > 16) {
      throw new DirectoryMcsdMappingException(
          "The source HealthcareService exceeds the supported type-code bound.");
    }
    return result;
  }

  private DirectorySourceProjection.EndpointMetadata mapEndpoint(
      Endpoint source,
      ExternalReference identity,
      IssuerId issuer,
      FhirR4ParsedResource parsed,
      Optional<String> versionId,
      Optional<Instant> sourceUpdatedAt) {
    if (!source.hasStatus()) {
      throw statusMissing();
    }
    String address = source.getAddress();
    if (address == null || address.isBlank() || address.length() > 2048) {
      throw new DirectoryMcsdMappingException(
          "The source Endpoint does not contain bounded discovery metadata.");
    }
    Optional<ExternalReference> organization =
        source.hasManagingOrganization()
            ? Optional.of(localReference(source.getManagingOrganization(), "Organization", issuer))
            : Optional.empty();
    Optional<DirectorySourceProjection.Coding> connectionType =
        Optional.ofNullable(source.getConnectionType())
            .filter(coding -> !coding.isEmpty())
            .map(
                coding ->
                    new DirectorySourceProjection.Coding(
                        coding.getSystem(),
                        coding.getCode(),
                        Optional.ofNullable(coding.getVersion())
                            .filter(value -> !value.isBlank())));
    boolean active = source.getStatus() == Endpoint.EndpointStatus.ACTIVE;
    return new DirectorySourceProjection.EndpointMetadata(
        metadata(
            identity,
            versionId,
            sourceUpdatedAt,
            parsed.profileUri(),
            address,
            organization,
            connectionType,
            active),
        address,
        organization,
        connectionType,
        active);
  }

  private boolean isConfiguredFacilityType(CodeableConcept physicalType) {
    return physicalType.getCoding().stream()
        .filter(coding -> coding.hasSystem() && coding.hasCode())
        .anyMatch(
            coding ->
                policy.classifiesFacility(
                    new DirectoryMcsdMappingPolicy.LocationCoding(
                        coding.getSystem(), coding.getCode())));
  }

  private static Optional<Wgs84Coordinate> coordinate(Location source) {
    if (!source.hasPosition()) {
      return Optional.empty();
    }
    if (!source.getPosition().hasLongitude() || !source.getPosition().hasLatitude()) {
      throw new DirectoryMcsdMappingException(
          "The source Location has an incomplete coordinate pair.");
    }
    try {
      return Optional.of(
          new Wgs84Coordinate(
              source.getPosition().getLongitude().doubleValue(),
              source.getPosition().getLatitude().doubleValue()));
    } catch (IllegalArgumentException invalidCoordinate) {
      throw new DirectoryMcsdMappingException(
          "The source Location coordinate is outside the WGS84 range.");
    }
  }

  private static ExternalReference localReference(
      Reference reference, String expectedType, IssuerId issuer) {
    String value = reference.getReference();
    String prefix = expectedType + "/";
    if (value == null || !value.startsWith(prefix)) {
      throw new DirectoryMcsdMappingException(
          "The source reference is not a supported local resource reference.");
    }
    String id = value.substring(prefix.length());
    if (!id.matches("[A-Za-z0-9.-]{1,64}")) {
      throw new DirectoryMcsdMappingException(
          "The source reference is not a supported local resource reference.");
    }
    return new ExternalReference(issuer, expectedType, id);
  }

  private static <T extends Resource> T asR4Resource(IBaseResource resource) {
    if (!(resource instanceof Resource r4Resource)
        || FhirVersionEnum.R4 != r4Resource.getStructureFhirVersionEnum()) {
      throw new DirectoryMcsdMappingException(
          "Directory mCSD import requires a FHIR R4 resource instance.");
    }
    return (T) r4Resource;
  }

  private static String requiredName(String name) {
    if (name == null || name.isBlank() || !name.equals(name.strip()) || name.length() > 200) {
      throw new DirectoryMcsdMappingException(
          "The source resource does not contain a usable Directory display name.");
    }
    return name;
  }

  private static DirectoryMcsdMappingException statusMissing() {
    return new DirectoryMcsdMappingException(
        "The source resource does not provide an explicit lifecycle status.");
  }

  private DirectorySourceProjection.Metadata metadata(
      ExternalReference identity,
      Optional<String> versionId,
      Optional<Instant> sourceUpdatedAt,
      String profileUri,
      Object... projectedFields) {
    String canonical =
        identity.issuer().value()
            + "\n"
            + identity.resourceType()
            + "\n"
            + identity.value()
            + "\n"
            + profileUri
            + "\n"
            + java.util.Arrays.stream(projectedFields).map(DirectoryMcsdMapper::canonical).toList();
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return new DirectorySourceProjection.Metadata(
          identity,
          versionId,
          sourceUpdatedAt,
          policy.treatsLastUpdatedAsOrderedRevision(identity.resourceType(), profileUri),
          profileUri,
          HexFormat.of().formatHex(digest));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
    }
  }

  private static String canonical(Object value) {
    return switch (value) {
      case null -> "<null>";
      case Optional<?> optional -> optional.map(DirectoryMcsdMapper::canonical).orElse("<empty>");
      case ExternalReference reference ->
          reference.issuer().value() + ":" + reference.resourceType() + ":" + reference.value();
      case Wgs84Coordinate coordinate ->
          Double.toHexString(coordinate.longitude())
              + ","
              + Double.toHexString(coordinate.latitude());
      case DirectorySourceProjection.Coding coding ->
          coding.system() + ":" + coding.code() + ":" + canonical(coding.version());
      case List<?> list -> list.stream().map(DirectoryMcsdMapper::canonical).toList().toString();
      default -> value.toString();
    };
  }
}
