package org.ayuvahak.referral.adapter.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScopeDeniedException;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.referral.application.ReferralDraftCommandResult;
import org.ayuvahak.referral.application.ReferralDraftCommands;
import org.ayuvahak.referral.domain.CareRequirement;
import org.ayuvahak.referral.domain.PatientReference;
import org.ayuvahak.referral.domain.ReferralDraft;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Authenticated HTTP boundary for creating and editing Referral drafts. */
@RestController
@RequestMapping("/api/v1/referrals")
public final class ReferralDraftController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  private static final String ORGANIZATION_SCOPE_HEADER = "X-AyuVahak-Organization-Id";
  private static final String FACILITY_SCOPE_HEADER = "X-AyuVahak-Facility-Id";
  private static final Set<String> REQUEST_FIELDS = Set.of("patientReference", "careRequirement");
  private static final Set<String> PATIENT_REFERENCE_FIELDS =
      Set.of("issuer", "resourceType", "identifier", "encounterReference");
  private static final Set<String> CARE_REQUIREMENT_FIELDS =
      Set.of(
          "requiredCareCapabilityCodes",
          "requestedStart",
          "requestedEnd",
          "permittedGeographyCodes",
          "selectedDestinationOrganizationId",
          "priorityCode");
  private static final Set<String> EXTERNAL_REFERENCE_FIELDS =
      Set.of("issuer", "resourceType", "identifier");

  private final ReferralDraftCommands commands;
  private final ExecutionContextFactory contextFactory;
  private final ObjectMapper objectMapper;

  public ReferralDraftController(
      ReferralDraftCommands commands,
      ExecutionContextFactory contextFactory,
      ObjectMapper objectMapper) {
    this.commands = commands;
    this.contextFactory = contextFactory;
    this.objectMapper = objectMapper;
  }

  @PostMapping
  public ResponseEntity<ReferralDraftRepresentation> create(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    DraftContent content = read(body);
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    ReferralDraftCommandResult result =
        commands.create(
            context,
            content.patientReference(),
            content.careRequirement(),
            idempotencyKey,
            fingerprint(content, Optional.empty()));
    HttpHeaders headers = new HttpHeaders();
    if (result.commandReplayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    headers.setLocation(URI.create("/api/v1/referrals/" + result.draft().id()));
    return new ResponseEntity<>(
        ReferralDraftRepresentation.from(result.draft()),
        headers,
        org.springframework.http.HttpStatusCode.valueOf(result.responseStatus()));
  }

  @PatchMapping("/{referralId}/draft")
  public ResponseEntity<ReferralDraftRepresentation> update(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String referralId,
      @RequestBody JsonNode body) {
    long expectedVersion = expectedVersion(body);
    DraftContent content = read(body, true);
    InternalId id = parseReferralId(referralId);
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    ReferralDraftCommandResult result =
        commands.update(
            context,
            id,
            expectedVersion,
            content.patientReference(),
            content.careRequirement(),
            idempotencyKey,
            fingerprint(content, Optional.of(expectedVersion)));
    HttpHeaders headers = new HttpHeaders();
    if (result.commandReplayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    return new ResponseEntity<>(
        ReferralDraftRepresentation.from(result.draft()),
        headers,
        org.springframework.http.HttpStatusCode.valueOf(result.responseStatus()));
  }

  private DraftContent read(JsonNode body) {
    return read(body, false);
  }

  private DraftContent read(JsonNode body, boolean hasExpectedVersion) {
    if (body == null || !body.isObject()) {
      throw malformed();
    }
    Set<String> allowedRootFields =
        hasExpectedVersion ? union(REQUEST_FIELDS, Set.of("expectedVersion")) : REQUEST_FIELDS;
    rejectUnknownFields(body, allowedRootFields);
    JsonNode patient = body.get("patientReference");
    JsonNode requirement = body.get("careRequirement");
    if (patient == null || requirement == null || !patient.isObject() || !requirement.isObject()) {
      throw invalid("PatientReference and CareRequirement must be structured objects.");
    }
    rejectUnknownFields(patient, PATIENT_REFERENCE_FIELDS);
    rejectUnknownFields(requirement, CARE_REQUIREMENT_FIELDS);
    try {
      PatientReference patientReference = patientReference(patient);
      CareRequirement careRequirement = careRequirement(requirement);
      return new DraftContent(patientReference, careRequirement);
    } catch (PlatformProblemException problem) {
      throw problem;
    } catch (RuntimeException invalid) {
      throw invalid("PatientReference or CareRequirement contains invalid typed values.");
    }
  }

  private PatientReference patientReference(JsonNode node) {
    ExternalReference patient =
        externalReference(
            requiredText(node, "issuer"),
            requiredText(node, "resourceType"),
            requiredText(node, "identifier"));
    Optional<ExternalReference> encounter = Optional.empty();
    JsonNode encounterNode = node.get("encounterReference");
    if (encounterNode != null && !encounterNode.isNull()) {
      if (!encounterNode.isObject()) {
        throw invalid("The optional source encounter/case reference must be an object.");
      }
      rejectUnknownFields(encounterNode, EXTERNAL_REFERENCE_FIELDS);
      encounter =
          Optional.of(
              externalReference(
                  requiredText(encounterNode, "issuer"),
                  requiredText(encounterNode, "resourceType"),
                  requiredText(encounterNode, "identifier")));
    }
    return new PatientReference(patient, encounter);
  }

  private CareRequirement careRequirement(JsonNode node) {
    List<String> capabilityCodes = stringList(node.get("requiredCareCapabilityCodes"), true);
    List<String> geographyCodes = stringList(node.get("permittedGeographyCodes"), false);
    Optional<Instant> requestedStart = optionalInstant(node, "requestedStart");
    Optional<Instant> requestedEnd = optionalInstant(node, "requestedEnd");
    Optional<String> priorityCode = optionalText(node, "priorityCode");
    Optional<ExternalReference> selectedDestination = Optional.empty();
    Optional<String> destinationId = optionalText(node, "selectedDestinationOrganizationId");
    if (destinationId.isPresent()) {
      InternalId parsed = InternalId.parse(destinationId.orElseThrow());
      selectedDestination = Optional.of(directoryReference("Organization", parsed));
    }
    return new CareRequirement(
        capabilityCodes,
        requestedStart,
        requestedEnd,
        geographyCodes,
        selectedDestination,
        priorityCode);
  }

  private static List<String> stringList(JsonNode value, boolean required) {
    if (value == null || value.isNull()) {
      if (required) {
        throw invalid("At least one required CareCapability code must be provided.");
      }
      return List.of();
    }
    if (!value.isArray()) {
      throw invalid("A CareRequirement code list must be an array.");
    }
    ArrayNode array = (ArrayNode) value;
    return array
        .valueStream()
        .map(
            node -> {
              if (!node.isTextual()) {
                throw invalid("CareRequirement codes must be strings.");
              }
              return node.stringValue();
            })
        .toList();
  }

  private Optional<Instant> optionalInstant(JsonNode node, String name) {
    Optional<String> value = optionalText(node, name);
    if (value.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.parse(value.orElseThrow()));
    } catch (RuntimeException malformed) {
      throw invalid("CareRequirement times must be ISO-8601 UTC instants.");
    }
  }

  private static Optional<String> optionalText(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isTextual() || value.stringValue().isBlank()) {
      throw invalid("Optional CareRequirement text fields must be nonblank strings or null.");
    }
    return Optional.of(value.stringValue());
  }

  private static String requiredText(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isTextual() || value.stringValue().isBlank()) {
      throw invalid("Required typed reference fields are missing or invalid.");
    }
    return value.stringValue();
  }

  private static ExternalReference externalReference(String issuer, String type, String value) {
    return new ExternalReference(new IssuerId(issuer), type, value);
  }

  private ExecutionContext context(
      ActorReference actor, String scope, String organizationId, String facilityId) {
    CorrelationId correlationId = correlationId();
    try {
      RequestedExecutionScope requestedScope;
      if ("ORGANIZATION".equals(scope) && organizationId != null && facilityId == null) {
        requestedScope =
            RequestedExecutionScope.organization(
                directoryReference("Organization", InternalId.parse(organizationId)));
      } else if ("FACILITY".equals(scope) && organizationId != null && facilityId != null) {
        requestedScope =
            RequestedExecutionScope.facility(
                directoryReference("Organization", InternalId.parse(organizationId)),
                directoryReference("Facility", InternalId.parse(facilityId)));
      } else {
        commands.recordInvalidScope(actor, correlationId);
        throw invalidScope();
      }
      if (actor.kind() == ActorKind.HUMAN) {
        return contextFactory.forInteractiveRequest(actor, requestedScope, correlationId);
      }
      return contextFactory.forIntegrationRequest(actor, requestedScope, correlationId);
    } catch (
        org.ayuvahak.directory.application.DirectoryScopeLookupUnavailableException unavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.REFERRAL_UNAVAILABLE,
          "Directory could not validate the Referral origin scope.");
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      commands.recordInvalidScope(actor, correlationId);
      throw invalidScope();
    }
  }

  private static long expectedVersion(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw malformed();
    }
    JsonNode version = body.get("expectedVersion");
    if (version == null || !version.isIntegralNumber() || !version.canConvertToLong()) {
      throw invalid("A positive expectedVersion is required to edit a Referral draft.");
    }
    long result = version.longValue();
    if (result < 1) {
      throw invalid("A positive expectedVersion is required to edit a Referral draft.");
    }
    return result;
  }

  private RequestFingerprint fingerprint(DraftContent content, Optional<Long> expectedVersion) {
    ObjectNode root = objectMapper.createObjectNode();
    expectedVersion.ifPresent(value -> root.put("expectedVersion", value));
    ObjectNode patient = root.putObject("patientReference");
    putReference(patient, content.patientReference().patient());
    if (content.patientReference().encounterOrCase().isPresent()) {
      putReference(
          patient.putObject("encounterReference"),
          content.patientReference().encounterOrCase().orElseThrow());
    } else {
      patient.putNull("encounterReference");
    }
    CareRequirement requirement = content.careRequirement();
    ObjectNode care = root.putObject("careRequirement");
    ArrayNode capabilities = care.putArray("requiredCareCapabilityCodes");
    requirement.requiredCareCapabilityCodes().forEach(capabilities::add);
    putOptionalInstant(care, "requestedStart", requirement.requestedStart());
    putOptionalInstant(care, "requestedEnd", requirement.requestedEnd());
    ArrayNode geography = care.putArray("permittedGeographyCodes");
    requirement.permittedGeographyCodes().forEach(geography::add);
    if (requirement.selectedDestinationOrganization().isPresent()) {
      care.put(
          "selectedDestinationOrganizationId",
          requirement.selectedDestinationOrganization().orElseThrow().value());
    } else {
      care.putNull("selectedDestinationOrganizationId");
    }
    care.put("priorityCode", requirement.priorityCode().orElse(null));
    return RequestFingerprint.from(root);
  }

  private static void putReference(ObjectNode node, ExternalReference reference) {
    node.put("issuer", reference.issuer().value());
    node.put("resourceType", reference.resourceType());
    node.put("identifier", reference.value());
  }

  private static void putOptionalInstant(ObjectNode node, String name, Optional<Instant> instant) {
    if (instant.isPresent()) {
      node.put(name, instant.orElseThrow().toString());
    } else {
      node.putNull(name);
    }
  }

  private static void rejectUnknownFields(JsonNode object, Set<String> permitted) {
    object
        .properties()
        .forEach(
            field -> {
              if (!permitted.contains(field.getKey())) {
                throw invalid("The Referral request contains an unsupported field.");
              }
            });
  }

  private static Set<String> union(Set<String> left, Set<String> right) {
    java.util.HashSet<String> fields = new java.util.HashSet<>(left);
    fields.addAll(right);
    return Set.copyOf(fields);
  }

  private static InternalId parseReferralId(String id) {
    try {
      return InternalId.parse(id);
    } catch (RuntimeException malformed) {
      throw new PlatformProblemException(
          HttpStatus.NOT_FOUND,
          PlatformErrorCode.REFERRAL_NOT_FOUND,
          "The Referral draft does not exist in the authorized origin scope.");
    }
  }

  private static ExternalReference directoryReference(String type, InternalId id) {
    return new ExternalReference(new IssuerId(DIRECTORY_ISSUER), type, id.toString());
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException malformed() {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST,
        PlatformErrorCode.MALFORMED_REQUEST,
        "The Referral request body must be a JSON object.");
  }

  private static PlatformProblemException invalid(String detail) {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST, PlatformErrorCode.REFERRAL_REQUIREMENT_INVALID, detail);
  }

  private static PlatformProblemException invalidScope() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "Referral draft commands require an active Directory origin scope.");
  }

  private record DraftContent(PatientReference patientReference, CareRequirement careRequirement) {}

  public record PatientReferenceRepresentation(
      String issuer,
      String resourceType,
      String identifier,
      ExternalReferenceRepresentation encounterReference) {
    static PatientReferenceRepresentation from(PatientReference reference) {
      return new PatientReferenceRepresentation(
          reference.patient().issuer().value(),
          reference.patient().resourceType(),
          reference.patient().value(),
          reference.encounterOrCase().map(ExternalReferenceRepresentation::from).orElse(null));
    }
  }

  public record ExternalReferenceRepresentation(
      String issuer, String resourceType, String identifier) {
    static ExternalReferenceRepresentation from(ExternalReference reference) {
      return new ExternalReferenceRepresentation(
          reference.issuer().value(), reference.resourceType(), reference.value());
    }
  }

  public record CareRequirementRepresentation(
      List<String> requiredCareCapabilityCodes,
      Instant requestedStart,
      Instant requestedEnd,
      List<String> permittedGeographyCodes,
      String selectedDestinationOrganizationId,
      String priorityCode) {
    static CareRequirementRepresentation from(CareRequirement requirement) {
      return new CareRequirementRepresentation(
          requirement.requiredCareCapabilityCodes(),
          requirement.requestedStart().orElse(null),
          requirement.requestedEnd().orElse(null),
          requirement.permittedGeographyCodes(),
          requirement.selectedDestinationOrganization().map(ExternalReference::value).orElse(null),
          requirement.priorityCode().orElse(null));
    }
  }

  public record OriginRepresentation(String organizationId, String facilityId) {
    static OriginRepresentation from(ReferralDraft draft) {
      return new OriginRepresentation(
          draft.originOrganization().value(),
          draft.originFacility().map(ExternalReference::value).orElse(null));
    }
  }

  public record ReferralDraftRepresentation(
      String id,
      String status,
      long version,
      OriginRepresentation origin,
      PatientReferenceRepresentation patientReference,
      CareRequirementRepresentation careRequirement) {
    static ReferralDraftRepresentation from(ReferralDraft draft) {
      return new ReferralDraftRepresentation(
          draft.id().toString(),
          "DRAFT",
          draft.version(),
          OriginRepresentation.from(draft),
          PatientReferenceRepresentation.from(draft.patientReference()),
          CareRequirementRepresentation.from(draft.careRequirement()));
    }
  }
}
