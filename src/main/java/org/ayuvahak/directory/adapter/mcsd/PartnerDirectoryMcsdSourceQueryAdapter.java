package org.ayuvahak.directory.adapter.mcsd;

import ca.uhn.fhir.context.FhirContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.ayuvahak.directory.application.DirectoryMcsdResourceType;
import org.ayuvahak.directory.application.DirectoryMcsdSourcePage;
import org.ayuvahak.directory.application.DirectoryMcsdSourceQueryPort;
import org.ayuvahak.directory.application.DirectoryMcsdSourceQueryPurpose;
import org.ayuvahak.directory.application.DirectoryMcsdSourceUnavailableException;
import org.ayuvahak.directory.application.DirectorySourceProjection;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.application.PartnerOutboundReadPort;
import org.ayuvahak.platform.exchange.application.PartnerOutboundReadRequest;
import org.ayuvahak.platform.exchange.application.PartnerOutboundReadResponse;
import org.ayuvahak.platform.exchange.application.PartnerTransportException;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.OwnerContext;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Protocol;
import org.ayuvahak.platform.fhir.FhirBoundaryException;
import org.ayuvahak.platform.fhir.FhirProfileContext;
import org.ayuvahak.platform.fhir.FhirR4ParsedResource;
import org.ayuvahak.platform.fhir.FhirR4PayloadParser;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Directory-owned mCSD read adapter; all network access remains behind the platform exchange port.
 */
@Component
final class PartnerDirectoryMcsdSourceQueryAdapter implements DirectoryMcsdSourceQueryPort {

  private static final String FHIR_RELEASE = "4.0.1";
  private static final String FHIR_JSON = "application/fhir+json";
  private static final String BUNDLE_PROFILE =
      "http://hl7.org/fhir/StructureDefinition/Bundle|4.0.1";
  private static final String MCSD_PROFILE = "IHE-mCSD";
  private static final int MAX_PAGE_SIZE = 200;

  private final DirectoryMcsdPartnerBindings partnerBindings;
  private final DirectoryMcsdMappingPolicy mappingPolicy;
  private final DirectoryMcsdMapper mapper;
  private final FhirR4PayloadParser parser;
  private final PartnerOutboundReadPort outbound;

  PartnerDirectoryMcsdSourceQueryAdapter(
      DirectoryMcsdPartnerBindings partnerBindings,
      DirectoryMcsdMappingPolicy mappingPolicy,
      DirectoryMcsdMapper mapper,
      @Qualifier("directoryFhirR4PayloadParser") FhirR4PayloadParser parser,
      PartnerOutboundReadPort outbound) {
    this.partnerBindings = Objects.requireNonNull(partnerBindings, "partnerBindings");
    this.mappingPolicy = Objects.requireNonNull(mappingPolicy, "mappingPolicy");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.parser = Objects.requireNonNull(parser, "parser");
    this.outbound = Objects.requireNonNull(outbound, "outbound");
  }

  @Override
  public void requireSourceAvailable(
      IssuerId sourceNamespace, DirectoryMcsdSourceQueryPurpose purpose) {
    try {
      outbound.requireActive(
          partnerBindings.requirePartner(sourceNamespace), operationFor(purpose));
    } catch (PartnerTransportException unavailable) {
      throw new DirectoryMcsdSourceUnavailableException(
          "The configured mCSD source is not available through an active trusted partner profile.");
    }
  }

  @Override
  public DirectoryMcsdSourcePage search(SearchRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.pageSize() > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("mCSD page size exceeds the adapter limit");
    }
    String resourceTypes =
        request.resourceTypes().stream()
            .sorted(Comparator.comparing(DirectoryMcsdResourceType::fhirType))
            .map(DirectoryMcsdResourceType::fhirType)
            .collect(java.util.stream.Collectors.joining(","));
    PartnerOutboundReadRequest outboundRequest =
        new PartnerOutboundReadRequest(
            partnerBindings.requirePartner(request.sourceNamespace()),
            operation("mcsd.iti90.search"),
            List.of(),
            Map.of(
                "_count",
                List.of(Integer.toString(request.pageSize())),
                "_type",
                List.of(resourceTypes)),
            request.pageToken(),
            Optional.of(request.executionContext().correlationId()));
    return readPage(
        outboundRequest, request.sourceNamespace().value(), request.resourceTypes(), false);
  }

  @Override
  public DirectoryMcsdSourcePage history(HistoryRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.pageSize() > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("mCSD page size exceeds the adapter limit");
    }
    PartnerOutboundReadRequest outboundRequest =
        new PartnerOutboundReadRequest(
            partnerBindings.requirePartner(request.sourceNamespace()),
            operation("mcsd.iti91.history"),
            List.of("_history"),
            Map.of(
                "_count", List.of(Integer.toString(request.pageSize())),
                "_since", List.of(request.sinceCursor())),
            request.pageToken(),
            Optional.of(request.executionContext().correlationId()));
    return readPage(outboundRequest, request.sourceNamespace().value(), Set.of(), true);
  }

  private DirectoryMcsdSourcePage readPage(
      PartnerOutboundReadRequest request,
      String sourceNamespace,
      Set<DirectoryMcsdResourceType> permittedTypes,
      boolean history) {
    PartnerOutboundReadResponse response;
    try {
      response = outbound.read(request);
    } catch (PartnerTransportException unavailable) {
      throw new DirectoryMcsdSourceUnavailableException(
          "The configured mCSD source could not be read through the trusted partner boundary.");
    }
    if (response.contentType().isEmpty()) {
      throw new DirectoryMcsdMappingException(
          "The configured mCSD source returned an invalid media type.");
    }

    Bundle bundle = parseBundle(response.body(), response.contentType().orElseThrow());
    Bundle.BundleType expectedType =
        history ? Bundle.BundleType.HISTORY : Bundle.BundleType.SEARCHSET;
    if (bundle.getType() != expectedType || bundle.getEntry().size() > MAX_PAGE_SIZE) {
      throw new DirectoryMcsdMappingException(
          "The configured mCSD source returned an invalid Bundle.");
    }

    List<DirectorySourceProjection> projections = new ArrayList<>(bundle.getEntry().size());
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      if (!entry.hasResource()) {
        throw new DirectoryMcsdMappingException(
            "The configured mCSD history contains an entry without a resource.");
      }
      Resource resource = entry.getResource();
      DirectoryMcsdResourceType resourceType =
          java.util.Arrays.stream(DirectoryMcsdResourceType.values())
              .filter(candidate -> candidate.fhirType().equals(resource.fhirType()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new DirectoryMcsdMappingException(
                          "The configured mCSD source returned a resource outside the Directory allowlist."));
      if (!permittedTypes.isEmpty() && !permittedTypes.contains(resourceType)) {
        throw new DirectoryMcsdMappingException(
            "The configured mCSD source returned an unrequested resource type.");
      }
      String profileUri = selectProfile(resource);
      byte[] resourceJson =
          FhirContext.forR4Cached()
              .newJsonParser()
              .encodeResourceToString(resource)
              .getBytes(StandardCharsets.UTF_8);
      FhirR4ParsedResource parsed =
          parser.parse(
              resourceJson,
              FHIR_JSON,
              new FhirProfileContext(
                  FHIR_RELEASE,
                  profileUri,
                  mappingPolicy.allowedProfilesByResourceType().get(resourceType.fhirType())));
      projections.add(mapper.map(parsed, sourceNamespace));
    }

    Optional<String> nextPageToken = nextPageLink(bundle);
    Optional<String> nextCursor =
        history && nextPageToken.isEmpty() && bundle.hasTimestamp()
            ? Optional.of(bundle.getTimestamp().toInstant().toString())
            : Optional.empty();
    return new DirectoryMcsdSourcePage(
        projections, nextPageToken, nextCursor, nextPageToken.isEmpty());
  }

  private Bundle parseBundle(byte[] body, String contentType) {
    try {
      FhirR4ParsedResource parsed =
          parser.parse(body, contentType, new FhirProfileContext(FHIR_RELEASE, BUNDLE_PROFILE));
      if (parsed.resource() instanceof Bundle bundle) {
        return bundle;
      }
    } catch (FhirBoundaryException invalid) {
      throw new DirectoryMcsdMappingException(
          "The configured mCSD source returned a malformed or unsupported FHIR Bundle.");
    }
    throw new DirectoryMcsdMappingException("The configured mCSD source did not return a Bundle.");
  }

  private String selectProfile(Resource resource) {
    List<String> declared =
        resource.getMeta().getProfile().stream()
            .map(org.hl7.fhir.r4.model.CanonicalType::getValue)
            .filter(Objects::nonNull)
            .toList();
    List<String> allowed =
        mappingPolicy
            .allowedProfilesByResourceType()
            .getOrDefault(resource.fhirType(), Set.of())
            .stream()
            .filter(declared::contains)
            .sorted()
            .toList();
    if (allowed.size() != 1) {
      throw new DirectoryMcsdMappingException(
          "The source resource does not declare exactly one configured Directory profile.");
    }
    return allowed.getFirst();
  }

  private static Optional<String> nextPageLink(Bundle bundle) {
    List<String> nextLinks =
        bundle.getLink().stream()
            .filter(link -> "next".equals(link.getRelation()))
            .map(Bundle.BundleLinkComponent::getUrl)
            .filter(Objects::nonNull)
            .toList();
    if (nextLinks.size() > 1) {
      throw new DirectoryMcsdMappingException(
          "The configured mCSD Bundle contains ambiguous continuation links.");
    }
    return nextLinks.stream().findFirst();
  }

  private static AllowedOperation operation(String operationCode) {
    return new AllowedOperation(
        OwnerContext.DIRECTORY,
        operationCode,
        Direction.OUTBOUND,
        Protocol.FHIR_R4_JSON,
        MCSD_PROFILE);
  }

  private static AllowedOperation operationFor(DirectoryMcsdSourceQueryPurpose purpose) {
    return switch (Objects.requireNonNull(purpose, "purpose")) {
      case INITIAL_DISCOVERY -> operation("mcsd.iti90.search");
      case INCREMENTAL_HISTORY -> operation("mcsd.iti91.history");
    };
  }
}
