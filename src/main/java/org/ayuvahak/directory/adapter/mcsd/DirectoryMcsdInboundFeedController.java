package org.ayuvahak.directory.adapter.mcsd;

import ca.uhn.fhir.context.FhirContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.ayuvahak.directory.application.DirectoryMcsdFeedEntry;
import org.ayuvahak.directory.application.DirectoryMcsdFeedImportPort;
import org.ayuvahak.directory.application.DirectoryMcsdFeedImportResult;
import org.ayuvahak.directory.application.DirectoryMcsdSourceDeletion;
import org.ayuvahak.directory.application.DirectorySourceProjectionReconciliation;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.application.ActiveInboundPartnerProfileLookupPort;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.fhir.FhirBoundaryException;
import org.ayuvahak.platform.fhir.FhirPayloadLimits;
import org.ayuvahak.platform.fhir.FhirProfileContext;
import org.ayuvahak.platform.fhir.FhirR4ParsedResource;
import org.ayuvahak.platform.fhir.FhirR4PayloadParser;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Named, authenticated ITI-130 ingress; owner mapping and writes remain inside Directory. */
@RestController
@RequestMapping("/api/v1/directory/mcsd/iti130")
final class DirectoryMcsdInboundFeedController {

  private static final String FHIR_JSON = "application/fhir+json";
  private static final String FHIR_RELEASE = "4.0.1";
  private static final String BUNDLE_PROFILE =
      "http://hl7.org/fhir/StructureDefinition/Bundle|4.0.1";
  private static final String OPERATION_CODE = "mcsd.iti130.feed";
  private static final int HARD_MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;
  private static final int HARD_MAX_RESOURCES = 250;
  private static final int HARD_MAX_NESTING = 64;
  private static final Duration HARD_MAX_PROCESSING_TIME = Duration.ofSeconds(2);
  private static final Set<String> DIRECTORY_RESOURCE_TYPES =
      Set.of("Organization", "Location", "HealthcareService", "Endpoint");
  private static final Set<String> SOURCE_STATUS_RESOURCE_TYPES =
      Set.of("Organization", "Location", "HealthcareService");
  private static final Map<DirectorySourceProjectionReconciliation.Outcome, String>
      RESPONSE_STATUS =
          Map.of(
              DirectorySourceProjectionReconciliation.Outcome.CREATE, "201 Created",
              DirectorySourceProjectionReconciliation.Outcome.UPDATE, "200 OK",
              DirectorySourceProjectionReconciliation.Outcome.UNCHANGED, "200 OK",
              DirectorySourceProjectionReconciliation.Outcome.DEACTIVATE, "200 OK",
              DirectorySourceProjectionReconciliation.Outcome.METADATA_ONLY, "200 OK",
              DirectorySourceProjectionReconciliation.Outcome.STALE, "202 Accepted",
              DirectorySourceProjectionReconciliation.Outcome.CONFLICT, "202 Accepted",
              DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED,
                  "202 Accepted");

  private final ActiveInboundPartnerProfileLookupPort inboundProfiles;
  private final DirectoryMcsdPartnerBindings partnerBindings;
  private final DirectoryMcsdMappingPolicy mappingPolicy;
  private final DirectoryMcsdMapper mapper;
  private final DirectoryMcsdFeedImportPort feedImporter;
  private final ExecutionContextFactory contextFactory;

  DirectoryMcsdInboundFeedController(
      ActiveInboundPartnerProfileLookupPort inboundProfiles,
      DirectoryMcsdPartnerBindings partnerBindings,
      DirectoryMcsdMappingPolicy mappingPolicy,
      DirectoryMcsdMapper mapper,
      DirectoryMcsdFeedImportPort feedImporter,
      ExecutionContextFactory contextFactory) {
    this.inboundProfiles = Objects.requireNonNull(inboundProfiles, "inboundProfiles");
    this.partnerBindings = Objects.requireNonNull(partnerBindings, "partnerBindings");
    this.mappingPolicy = Objects.requireNonNull(mappingPolicy, "mappingPolicy");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.feedImporter = Objects.requireNonNull(feedImporter, "feedImporter");
    this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
  }

  @PostMapping(path = "/feed")
  ResponseEntity<byte[]> receive(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-External-Message-ID", required = false) String externalMessageId,
      HttpServletRequest request) {
    if (actor == null || actor.kind() != org.ayuvahak.platform.core.ActorKind.SERVICE) {
      throw denied();
    }
    requireMessageId(externalMessageId);
    requireFhirJson(request);
    CorrelationId correlationId = correlationId();
    PartnerProfileVersion profile =
        inboundProfiles
            .findActive(actor, AllowedOperation.directoryIti130Feed())
            .orElseThrow(DirectoryMcsdInboundFeedController::denied);
    PartnerKey partnerKey = profile.configuration().partnerKey();
    IssuerId sourceNamespace = partnerBindings.requireNamespace(partnerKey);
    byte[] body =
        readBounded(
            request,
            Math.min(profile.configuration().limits().maxResponseBytes(), HARD_MAX_PAYLOAD_BYTES));
    List<DirectoryMcsdFeedEntry> entries =
        parseAndMap(body, sourceNamespace, profile.configuration().limits());
    var organization = profile.configuration().organization();
    var context =
        contextFactory.forIntegrationRequest(
            actor, RequestedExecutionScope.organization(organization), correlationId);
    String fingerprint = sha256(body);
    DirectoryMcsdFeedImportResult result =
        feedImporter.importFeed(context, sourceNamespace, externalMessageId, fingerprint, entries);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(FHIR_JSON))
        .header(CorrelationIdFilter.HEADER_NAME, correlationId.value())
        .body(encodeTransactionResponse(result));
  }

  private List<DirectoryMcsdFeedEntry> parseAndMap(
      byte[] body,
      IssuerId sourceNamespace,
      org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Limits partnerLimits) {
    int resourceLimit = Math.min(partnerLimits.maxResources(), HARD_MAX_RESOURCES);
    FhirPayloadLimits limits =
        new FhirPayloadLimits(
            Math.min(partnerLimits.maxResponseBytes(), HARD_MAX_PAYLOAD_BYTES),
            Math.min(partnerLimits.maxNestingDepth(), HARD_MAX_NESTING),
            resourceLimit + 1,
            Math.min(1000, Math.max(1, resourceLimit * 4)),
            minDuration(partnerLimits.operationTimeoutMillis(), HARD_MAX_PROCESSING_TIME));
    FhirR4PayloadParser parser = FhirR4PayloadParser.withCoreProfiles(limits);
    FhirR4ParsedResource parsed;
    try {
      parsed = parser.parse(body, FHIR_JSON, new FhirProfileContext(FHIR_RELEASE, BUNDLE_PROFILE));
    } catch (FhirBoundaryException invalid) {
      throw malformedFeed();
    }
    if (!(parsed.resource() instanceof Bundle bundle)
        || bundle.getType() != Bundle.BundleType.TRANSACTION
        || bundle.getEntry().isEmpty()
        || bundle.getEntry().size() > resourceLimit) {
      throw malformedFeed();
    }

    List<DirectoryMcsdFeedEntry> entries = new ArrayList<>(bundle.getEntry().size());
    for (int index = 0; index < bundle.getEntry().size(); index++) {
      Bundle.BundleEntryComponent entry = bundle.getEntry().get(index);
      if (!entry.hasRequest()) {
        throw malformedFeed();
      }
      Bundle.BundleEntryRequestComponent request = entry.getRequest();
      String requestUrl = request.getUrl();
      if (request.getMethod() == Bundle.HTTPVerb.DELETE) {
        if (entry.hasResource()
            || request.hasIfNoneExist()
            || !request.hasIfMatch()
            || requestUrl == null) {
          throw malformedFeed();
        }
        String[] target = requestUrl.split("/", -1);
        if (target.length != 2
            || !SOURCE_STATUS_RESOURCE_TYPES.contains(target[0])
            || !target[1].matches("[A-Za-z0-9.-]{1,64}")) {
          throw malformedFeed();
        }
        String expectedVersionId = parseExpectedVersionId(request.getIfMatch());
        ExternalReference sourceIdentity =
            new ExternalReference(sourceNamespace, target[0], target[1]);
        String deletionFingerprint =
            sha256(
                ("DELETE\n"
                        + sourceIdentity.issuer().value()
                        + "\n"
                        + sourceIdentity.resourceType()
                        + "\n"
                        + sourceIdentity.value()
                        + "\n"
                        + expectedVersionId)
                    .getBytes(StandardCharsets.UTF_8));
        entries.add(
            DirectoryMcsdFeedEntry.deletion(
                index,
                new DirectoryMcsdSourceDeletion(
                    sourceIdentity, expectedVersionId, deletionFingerprint)));
        continue;
      }
      if (!entry.hasResource()) {
        throw malformedFeed();
      }
      Resource resource = entry.getResource();
      String resourceType = resource.fhirType();
      String logicalId = resource.getIdElement().getIdPart();
      if (!DIRECTORY_RESOURCE_TYPES.contains(resourceType)
          || request.getMethod() != Bundle.HTTPVerb.PUT
          || request.hasIfMatch()
          || request.hasIfNoneExist()
          || requestUrl == null
          || !requestUrl.matches(resourceType + "/[A-Za-z0-9.-]{1,64}")
          || logicalId == null
          || !requestUrl.equals(resourceType + "/" + logicalId)) {
        throw malformedFeed();
      }
      String profileUri = selectProfile(resource);
      byte[] resourceJson =
          FhirContext.forR4Cached()
              .newJsonParser()
              .encodeResourceToString(resource)
              .getBytes(StandardCharsets.UTF_8);
      FhirR4ParsedResource parsedResource;
      try {
        parsedResource =
            parser.parse(
                resourceJson,
                FHIR_JSON,
                new FhirProfileContext(
                    FHIR_RELEASE,
                    profileUri,
                    mappingPolicy.allowedProfilesByResourceType().get(resourceType)));
      } catch (FhirBoundaryException invalid) {
        throw malformedFeed();
      }
      try {
        entries.add(
            new DirectoryMcsdFeedEntry(index, mapper.map(parsedResource, sourceNamespace.value())));
      } catch (DirectoryMcsdMappingException invalid) {
        throw malformedFeed();
      }
    }

    // Directory facts have owner-side dependencies. Apply parents before their children while
    // retaining the original Bundle entry order in the transaction-response.
    return List.copyOf(entries);
  }

  private String selectProfile(Resource resource) {
    List<String> declared =
        resource.getMeta().getProfile().stream()
            .map(profile -> profile.getValue())
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
      throw malformedFeed();
    }
    return allowed.getFirst();
  }

  private static byte[] encodeTransactionResponse(DirectoryMcsdFeedImportResult result) {
    Bundle response = new Bundle().setType(Bundle.BundleType.TRANSACTIONRESPONSE);
    for (var outcome : result.outcomes()) {
      String status = RESPONSE_STATUS.get(outcome);
      if (status == null) {
        throw new IllegalStateException("Directory returned an unsupported feed outcome");
      }
      response.addEntry().setResponse(new Bundle.BundleEntryResponseComponent().setStatus(status));
    }
    return FhirContext.forR4Cached()
        .newJsonParser()
        .encodeResourceToString(response)
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] readBounded(HttpServletRequest request, int maximumBytes) {
    long contentLength = request.getContentLengthLong();
    if (contentLength > maximumBytes) {
      throw payloadTooLarge();
    }
    try (var input = request.getInputStream();
        var output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int total = 0;
      int read;
      while ((read = input.read(buffer)) != -1) {
        total = Math.addExact(total, read);
        if (total > maximumBytes) {
          throw payloadTooLarge();
        }
        output.write(buffer, 0, read);
      }
      if (total == 0) {
        throw malformedFeed();
      }
      return output.toByteArray();
    } catch (IOException | ArithmeticException failure) {
      throw malformedFeed();
    }
  }

  private static void requireMessageId(String messageId) {
    if (messageId == null || !messageId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MALFORMED_REQUEST,
          "A valid external feed message identifier is required.");
    }
  }

  private static void requireFhirJson(HttpServletRequest request) {
    String contentType = request.getContentType();
    String contentEncoding = request.getHeader("Content-Encoding");
    if (contentEncoding != null
        && !contentEncoding.isBlank()
        && !"identity".equalsIgnoreCase(contentEncoding)) {
      throw new PlatformProblemException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          PlatformErrorCode.UNSUPPORTED_MEDIA_TYPE,
          "Compressed feed request bodies are not supported.");
    }
    try {
      if (contentType == null
          || !MediaType.parseMediaType(contentType)
              .isCompatibleWith(MediaType.parseMediaType(FHIR_JSON))) {
        throw new IllegalArgumentException("unsupported media type");
      }
    } catch (IllegalArgumentException unsupported) {
      throw new PlatformProblemException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          PlatformErrorCode.UNSUPPORTED_MEDIA_TYPE,
          "The Directory feed accepts application/fhir+json only.");
    }
  }

  private static String sha256(byte[] body) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
    }
  }

  private static String parseExpectedVersionId(String ifMatch) {
    if (ifMatch == null) {
      throw malformedFeed();
    }
    String versionId;
    if (ifMatch.startsWith("W/\"") && ifMatch.endsWith("\"")) {
      versionId = ifMatch.substring(3, ifMatch.length() - 1);
    } else if (ifMatch.startsWith("\"") && ifMatch.endsWith("\"")) {
      versionId = ifMatch.substring(1, ifMatch.length() - 1);
    } else {
      throw malformedFeed();
    }
    if (!versionId.matches("[A-Za-z0-9.-]{1,64}")) {
      throw malformedFeed();
    }
    return versionId;
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static Duration minDuration(int partnerMillis, Duration hardLimit) {
    return Duration.ofMillis(Math.min(partnerMillis, hardLimit.toMillis()));
  }

  private static PlatformProblemException malformedFeed() {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST,
        PlatformErrorCode.MALFORMED_REQUEST,
        "The Directory feed is malformed or uses an unsupported mCSD profile or request form.");
  }

  private static PlatformProblemException payloadTooLarge() {
    return new PlatformProblemException(
        HttpStatus.PAYLOAD_TOO_LARGE,
        PlatformErrorCode.VALIDATION_FAILED,
        "The Directory feed exceeds its configured payload limit.");
  }

  private static PlatformProblemException denied() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated service is not authorized for this Directory feed operation.");
  }
}
