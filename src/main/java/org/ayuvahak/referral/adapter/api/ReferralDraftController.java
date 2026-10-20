package org.ayuvahak.referral.adapter.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import org.ayuvahak.referral.application.ReferralAmendmentCommandResult;
import org.ayuvahak.referral.application.ReferralCurrentView;
import org.ayuvahak.referral.application.ReferralDraftCommandResult;
import org.ayuvahak.referral.application.ReferralDraftCommands;
import org.ayuvahak.referral.application.ReferralIdentifierRestrictionReason;
import org.ayuvahak.referral.application.ReferralListItem;
import org.ayuvahak.referral.application.ReferralListPage;
import org.ayuvahak.referral.application.ReferralQueries;
import org.ayuvahak.referral.application.ReferralReadFilter;
import org.ayuvahak.referral.application.ReferralResolutionCommandResult;
import org.ayuvahak.referral.application.ReferralRetentionHoldPurpose;
import org.ayuvahak.referral.application.ReferralRetentionHoldReleaseReason;
import org.ayuvahak.referral.application.ReferralRetentionManifest;
import org.ayuvahak.referral.application.ReferralRetentionOperations;
import org.ayuvahak.referral.application.ReferralSubmissionCommandResult;
import org.ayuvahak.referral.domain.CareRequirement;
import org.ayuvahak.referral.domain.PatientReference;
import org.ayuvahak.referral.domain.ReferralDraft;
import org.ayuvahak.referral.domain.ReferralResolution;
import org.ayuvahak.referral.domain.ReferralResolutionOutcome;
import org.ayuvahak.referral.domain.ReferralState;
import org.ayuvahak.referral.domain.SubmittedReferralRevision;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Authenticated HTTP boundary for Referral creation and lifecycle commands. */
@RestController
@RequestMapping("/api/v1/referrals")
public final class ReferralDraftController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  private static final String ORGANIZATION_SCOPE_HEADER = "X-AyuVahak-Organization-Id";
  private static final String FACILITY_SCOPE_HEADER = "X-AyuVahak-Facility-Id";
  private static final Set<String> REQUEST_FIELDS = Set.of("patientReference", "careRequirement");
  private static final Set<String> SUBMISSION_FIELDS = Set.of("expectedVersion");
  private static final Set<String> RESOLUTION_FIELDS =
      Set.of("expectedVersion", "outcome", "reasonCode");
  private static final Set<String> CANCELLATION_FIELDS = Set.of("expectedVersion", "reasonCode");
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
  private final ReferralQueries queries;
  private final ReferralRetentionOperations retention;
  private final ExecutionContextFactory contextFactory;
  private final ObjectMapper objectMapper;

  public ReferralDraftController(
      ReferralDraftCommands commands,
      ReferralQueries queries,
      ReferralRetentionOperations retention,
      ExecutionContextFactory contextFactory,
      ObjectMapper objectMapper) {
    this.commands = commands;
    this.queries = queries;
    this.retention = retention;
    this.contextFactory = contextFactory;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/{referralId}")
  public ReferralCurrentRepresentation getCurrent(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @PathVariable String referralId) {
    ExecutionContext context = readContext(actor, scope, organizationId, facilityId);
    return ReferralCurrentRepresentation.from(
        queries.current(context, parseReferralId(referralId)));
  }

  @GetMapping("/{referralId}/revisions/{revisionNumber}")
  public ReferralRevisionRepresentation getRevision(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @PathVariable String referralId,
      @PathVariable String revisionNumber) {
    long number = parseRevisionNumber(revisionNumber);
    ExecutionContext context = readContext(actor, scope, organizationId, facilityId);
    ReferralCurrentView view = queries.revision(context, parseReferralId(referralId), number);
    return ReferralRevisionRepresentation.from(
        view.current().id().toString(),
        view.submittedRevision().orElseThrow(),
        view.identifierRestricted());
  }

  @GetMapping
  public ReferralListRepresentation list(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestParam MultiValueMap<String, String> parameters) {
    ExecutionContext context = readContext(actor, scope, organizationId, facilityId);
    ReferralReadFilter filter = readFilter(parameters);
    return ReferralListRepresentation.from(queries.list(context, filter));
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
        ReferralDraftRepresentation.from(
            result.draft(), retention.isIdentifierRestricted(result.draft().id())),
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
        ReferralDraftRepresentation.from(
            result.draft(), retention.isIdentifierRestricted(result.draft().id())),
        headers,
        org.springframework.http.HttpStatusCode.valueOf(result.responseStatus()));
  }

  @PostMapping("/{referralId}/submission")
  public ResponseEntity<SubmittedReferralRepresentation> submit(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String referralId,
      @RequestBody JsonNode body) {
    long expectedVersion = submissionExpectedVersion(body);
    InternalId id = parseReferralId(referralId);
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    ReferralSubmissionCommandResult result =
        commands.submit(
            context, id, expectedVersion, idempotencyKey, submissionFingerprint(expectedVersion));
    HttpHeaders headers = new HttpHeaders();
    if (result.commandReplayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    return new ResponseEntity<>(
        SubmittedReferralRepresentation.from(
            result.revision(), retention.isIdentifierRestricted(result.revision().referralId())),
        headers,
        org.springframework.http.HttpStatusCode.valueOf(result.responseStatus()));
  }

  @PostMapping("/{referralId}/revisions")
  public ResponseEntity<SubmittedReferralRepresentation> amend(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String referralId,
      @RequestBody JsonNode body) {
    long expectedVersion = amendmentExpectedVersion(body);
    String reasonCode = requiredReasonCode(body, "amendment");
    DraftContent content = read(body, true, true);
    InternalId id = parseReferralId(referralId);
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    ReferralAmendmentCommandResult result =
        commands.amendSubmitted(
            context,
            id,
            expectedVersion,
            reasonCode,
            content.patientReference(),
            content.careRequirement(),
            idempotencyKey,
            amendmentFingerprint(content, expectedVersion, reasonCode));
    HttpHeaders headers = new HttpHeaders();
    if (result.commandReplayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    return new ResponseEntity<>(
        SubmittedReferralRepresentation.from(
            result.revision(), retention.isIdentifierRestricted(result.revision().referralId())),
        headers,
        org.springframework.http.HttpStatusCode.valueOf(result.responseStatus()));
  }

  @PostMapping("/{referralId}/resolution")
  public ResponseEntity<ReferralResolutionRepresentation> resolve(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String referralId,
      @RequestBody JsonNode body) {
    ResolutionContent content = resolutionContent(body);
    InternalId id = parseReferralId(referralId);
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    ReferralResolutionCommandResult result =
        commands.resolve(
            context,
            id,
            content.expectedVersion(),
            content.outcome(),
            content.reasonCode(),
            idempotencyKey,
            resolutionFingerprint(content));
    HttpHeaders headers = new HttpHeaders();
    if (result.commandReplayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    return new ResponseEntity<>(
        ReferralResolutionRepresentation.from(result.referral(), result.resolution()),
        headers,
        org.springframework.http.HttpStatusCode.valueOf(result.responseStatus()));
  }

  @PostMapping("/{referralId}/cancellation")
  public ResponseEntity<ReferralDraftRepresentation> cancel(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String referralId,
      @RequestBody JsonNode body) {
    CancellationContent content = cancellationContent(body);
    InternalId id = parseReferralId(referralId);
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    ReferralDraftCommandResult result =
        commands.cancel(
            context,
            id,
            content.expectedVersion(),
            content.reasonCode(),
            idempotencyKey,
            cancellationFingerprint(content));
    HttpHeaders headers = new HttpHeaders();
    if (result.commandReplayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    return new ResponseEntity<>(
        ReferralDraftRepresentation.from(
            result.draft(), retention.isIdentifierRestricted(result.draft().id())),
        headers,
        org.springframework.http.HttpStatusCode.valueOf(result.responseStatus()));
  }

  @PostMapping("/{referralId}/retention-holds")
  public ResponseEntity<ReferralRetentionHoldAccepted> placeRetentionHold(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @PathVariable String referralId,
      @RequestBody JsonNode body) {
    RetentionHoldRequest request = retentionHoldRequest(body);
    var result =
        retention.placeHold(
            context(actor, scope, organizationId, facilityId),
            parseReferralId(referralId),
            request.purpose(),
            request.reviewBy());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new ReferralRetentionHoldAccepted(
                result.holdId().toString(), result.referralId().toString(), result.reviewBy()));
  }

  @PostMapping("/{referralId}/retention-holds/{holdId}/release")
  public ResponseEntity<Void> releaseRetentionHold(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @PathVariable String referralId,
      @PathVariable String holdId,
      @RequestBody JsonNode body) {
    UUID parsedHoldId;
    try {
      parsedHoldId = UUID.fromString(holdId);
    } catch (IllegalArgumentException invalid) {
      throw invalid("A valid Referral retention hold id is required.");
    }
    ReferralRetentionHoldReleaseReason reason = retentionHoldReleaseReason(body);
    retention.releaseHold(
        context(actor, scope, organizationId, facilityId),
        parseReferralId(referralId),
        parsedHoldId,
        reason);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{referralId}/identifier-restriction")
  public ReferralIdentifierRestrictionAccepted restrictIdentifier(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @PathVariable String referralId,
      @RequestBody JsonNode body) {
    ReferralIdentifierRestrictionReason reason = identifierRestrictionReason(body);
    var result =
        retention.restrictIdentifier(
            context(actor, scope, organizationId, facilityId), parseReferralId(referralId), reason);
    return new ReferralIdentifierRestrictionAccepted(
        result.referralId().toString(),
        result.patientReferenceAccessRestricted(),
        result.created());
  }

  @PostMapping("/retention-reapplication/export")
  public ReferralRetentionManifest exportRetentionManifest(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestBody JsonNode body) {
    if (body == null
        || !body.isObject()
        || body.size() != 2
        || !body.has("restoreId")
        || !body.get("restoreId").isTextual()
        || !body.has("backupCompletedAt")
        || !body.get("backupCompletedAt").isTextual()) {
      throw invalid("A restoreId and backupCompletedAt are required.");
    }
    try {
      return retention.exportManifest(
          platformContext(actor, scope),
          UUID.fromString(body.get("restoreId").stringValue()),
          Instant.parse(body.get("backupCompletedAt").stringValue()));
    } catch (IllegalArgumentException malformed) {
      throw invalid("The Referral retention manifest export request is invalid.");
    }
  }

  @PostMapping("/retention-reapplication")
  public ResponseEntity<Void> reapplyRetentionManifest(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestBody JsonNode body) {
    if (body == null
        || !body.isObject()
        || body.size() != 5
        || !body.has("restoreId")
        || !body.has("backupCompletedAt")
        || !body.has("generatedAt")
        || !body.has("holds")
        || !body.has("identifierRestrictions")) {
      throw invalid("A Referral retention manifest object is required.");
    }
    final ReferralRetentionManifest manifest;
    try {
      manifest = objectMapper.treeToValue(body, ReferralRetentionManifest.class);
    } catch (tools.jackson.core.JacksonException
        | IllegalArgumentException
        | NullPointerException malformed) {
      throw invalid("The Referral retention manifest is malformed.");
    }
    retention.reapplyManifest(platformContext(actor, scope), manifest);
    return ResponseEntity.noContent().build();
  }

  private RetentionHoldRequest retentionHoldRequest(JsonNode body) {
    if (body == null
        || !body.isObject()
        || body.size() != 2
        || !body.has("purposeCode")
        || !body.get("purposeCode").isTextual()
        || !body.has("reviewBy")
        || !body.get("reviewBy").isTextual()) {
      throw invalid("A purposeCode and reviewBy are required for a Referral retention hold.");
    }
    try {
      return new RetentionHoldRequest(
          ReferralRetentionHoldPurpose.valueOf(body.get("purposeCode").stringValue()),
          Instant.parse(body.get("reviewBy").stringValue()));
    } catch (IllegalArgumentException malformed) {
      throw invalid("The Referral retention hold purpose or review time is invalid.");
    }
  }

  private ReferralRetentionHoldReleaseReason retentionHoldReleaseReason(JsonNode body) {
    if (body == null
        || !body.isObject()
        || body.size() != 1
        || !body.has("reasonCode")
        || !body.get("reasonCode").isTextual()) {
      throw invalid("A reasonCode is required to release a Referral retention hold.");
    }
    try {
      return ReferralRetentionHoldReleaseReason.valueOf(body.get("reasonCode").stringValue());
    } catch (IllegalArgumentException malformed) {
      throw invalid("The Referral retention hold release reason is invalid.");
    }
  }

  private ReferralIdentifierRestrictionReason identifierRestrictionReason(JsonNode body) {
    if (body == null
        || !body.isObject()
        || body.size() != 1
        || !body.has("reasonCode")
        || !body.get("reasonCode").isTextual()) {
      throw invalid("A reasonCode is required to restrict the Referral identifier.");
    }
    try {
      return ReferralIdentifierRestrictionReason.valueOf(body.get("reasonCode").stringValue());
    } catch (IllegalArgumentException malformed) {
      throw invalid("The Referral identifier restriction reason is invalid.");
    }
  }

  private long submissionExpectedVersion(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw malformed();
    }
    rejectUnknownFields(body, SUBMISSION_FIELDS);
    JsonNode version = body.get("expectedVersion");
    if (version == null || !version.isIntegralNumber() || !version.canConvertToLong()) {
      throw invalid("A positive expectedVersion is required to submit a Referral.");
    }
    long result = version.longValue();
    if (result < 1) {
      throw invalid("A positive expectedVersion is required to submit a Referral.");
    }
    return result;
  }

  private long amendmentExpectedVersion(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw malformed();
    }
    JsonNode version = body.get("expectedVersion");
    if (version == null || !version.isIntegralNumber() || !version.canConvertToLong()) {
      throw invalid("A positive expectedVersion is required to amend a Referral.");
    }
    long result = version.longValue();
    if (result < 1) {
      throw invalid("A positive expectedVersion is required to amend a Referral.");
    }
    return result;
  }

  private ResolutionContent resolutionContent(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw malformed();
    }
    rejectUnknownFields(body, RESOLUTION_FIELDS);
    JsonNode version = body.get("expectedVersion");
    if (version == null
        || !version.isIntegralNumber()
        || !version.canConvertToLong()
        || version.longValue() < 1) {
      throw invalid("A positive expectedVersion is required to resolve a Referral.");
    }
    JsonNode outcome = body.get("outcome");
    if (outcome == null || !outcome.isTextual()) {
      throw invalid("A Referral resolution outcome must be FULFILLED or UNFULFILLED.");
    }
    ReferralResolutionOutcome resolutionOutcome;
    try {
      resolutionOutcome = ReferralResolutionOutcome.valueOf(outcome.stringValue());
    } catch (IllegalArgumentException invalidOutcome) {
      throw invalid("A Referral resolution outcome must be FULFILLED or UNFULFILLED.");
    }
    return new ResolutionContent(
        version.longValue(), resolutionOutcome, requiredReasonCode(body, "resolution"));
  }

  private CancellationContent cancellationContent(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw malformed();
    }
    rejectUnknownFields(body, CANCELLATION_FIELDS);
    JsonNode version = body.get("expectedVersion");
    if (version == null
        || !version.isIntegralNumber()
        || !version.canConvertToLong()
        || version.longValue() < 1) {
      throw invalid("A positive expectedVersion is required to cancel a Referral.");
    }
    return new CancellationContent(version.longValue(), requiredReasonCode(body, "cancellation"));
  }

  private static String requiredReasonCode(JsonNode body, String operation) {
    JsonNode reason = body.get("reasonCode");
    if (reason == null
        || !reason.isTextual()
        || !reason.stringValue().matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw invalid("A valid coded reason is required for Referral " + operation + ".");
    }
    return reason.stringValue();
  }

  private RequestFingerprint submissionFingerprint(long expectedVersion) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("expectedVersion", expectedVersion);
    return RequestFingerprint.from(root);
  }

  private DraftContent read(JsonNode body) {
    return read(body, false);
  }

  private DraftContent read(JsonNode body, boolean hasExpectedVersion) {
    return read(body, hasExpectedVersion, false);
  }

  private DraftContent read(JsonNode body, boolean hasExpectedVersion, boolean hasReasonCode) {
    if (body == null || !body.isObject()) {
      throw malformed();
    }
    Set<String> allowedRootFields =
        hasExpectedVersion ? union(REQUEST_FIELDS, Set.of("expectedVersion")) : REQUEST_FIELDS;
    if (hasReasonCode) {
      allowedRootFields = union(allowedRootFields, Set.of("reasonCode"));
    }
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

  private ExecutionContext platformContext(ActorReference actor, String scope) {
    CorrelationId correlationId = correlationId();
    if (!"PLATFORM".equals(scope)) {
      commands.recordInvalidScope(actor, correlationId);
      throw invalidScope();
    }
    if (actor.kind() == ActorKind.HUMAN) {
      return contextFactory.forInteractiveRequest(
          actor, RequestedExecutionScope.platform(), correlationId);
    }
    return contextFactory.forIntegrationRequest(
        actor, RequestedExecutionScope.platform(), correlationId);
  }

  private ExecutionContext readContext(
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
        queries.recordInvalidScope(actor, correlationId);
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
          "Directory could not validate the Referral read scope.");
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      queries.recordInvalidScope(actor, correlationId);
      throw invalidScope();
    }
  }

  private static ReferralReadFilter readFilter(MultiValueMap<String, String> parameters) {
    Set<String> allowed = Set.of("status", "from", "until", "page", "size");
    if (parameters.keySet().stream().anyMatch(name -> !allowed.contains(name))) {
      throw invalid("The Referral list contains an unsupported filter.");
    }
    if (parameters.values().stream().anyMatch(values -> values == null || values.size() != 1)) {
      throw invalid("Referral list filters may be supplied only once.");
    }
    Optional<ReferralState> state =
        single(parameters, "status")
            .map(
                value -> {
                  try {
                    return ReferralState.valueOf(value);
                  } catch (IllegalArgumentException malformed) {
                    throw invalid("The Referral status filter is invalid.");
                  }
                });
    Optional<Instant> from = single(parameters, "from").map(ReferralDraftController::instant);
    Optional<Instant> until = single(parameters, "until").map(ReferralDraftController::instant);
    int page = integer(single(parameters, "page").orElse("0"), "page", 0);
    int size = integer(single(parameters, "size").orElse("50"), "size", 50);
    try {
      return new ReferralReadFilter(state, from, until, page, size);
    } catch (IllegalArgumentException invalid) {
      throw invalid("Referral pagination or time filters are outside the supported bounds.");
    }
  }

  private static Optional<String> single(MultiValueMap<String, String> parameters, String name) {
    return Optional.ofNullable(parameters.getFirst(name));
  }

  private static Instant instant(String value) {
    try {
      return Instant.parse(value);
    } catch (RuntimeException malformed) {
      throw invalid("Referral list time filters must be valid UTC instants.");
    }
  }

  private static int integer(String value, String name, int defaultValue) {
    try {
      if (!value.matches("[0-9]{1,10}")) {
        throw new NumberFormatException("not a bounded decimal integer");
      }
      return Integer.parseInt(value);
    } catch (NumberFormatException malformed) {
      throw invalid("Referral list " + name + " must be a bounded non-negative integer.");
    }
  }

  private static long parseRevisionNumber(String value) {
    try {
      if (!value.matches("[1-9][0-9]{0,18}")) {
        throw new NumberFormatException("not a positive revision number");
      }
      return Long.parseLong(value);
    } catch (NumberFormatException malformed) {
      throw invalid("A positive submitted Referral revision number is required.");
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
    addContentFingerprint(root, content);
    return RequestFingerprint.from(root);
  }

  private RequestFingerprint amendmentFingerprint(
      DraftContent content, long expectedVersion, String reasonCode) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("expectedVersion", expectedVersion);
    root.put("reasonCode", reasonCode);
    addContentFingerprint(root, content);
    return RequestFingerprint.from(root);
  }

  private RequestFingerprint resolutionFingerprint(ResolutionContent content) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("expectedVersion", content.expectedVersion());
    root.put("outcome", content.outcome().name());
    root.put("reasonCode", content.reasonCode());
    return RequestFingerprint.from(root);
  }

  private RequestFingerprint cancellationFingerprint(CancellationContent content) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("expectedVersion", content.expectedVersion());
    root.put("reasonCode", content.reasonCode());
    return RequestFingerprint.from(root);
  }

  private void addContentFingerprint(ObjectNode root, DraftContent content) {
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

  private record ResolutionContent(
      long expectedVersion, ReferralResolutionOutcome outcome, String reasonCode) {}

  private record CancellationContent(long expectedVersion, String reasonCode) {}

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

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ReferralDraftRepresentation(
      String id,
      String status,
      long version,
      OriginRepresentation origin,
      PatientReferenceRepresentation patientReference,
      CareRequirementRepresentation careRequirement) {
    static ReferralDraftRepresentation from(ReferralDraft draft, boolean identifierRestricted) {
      return new ReferralDraftRepresentation(
          draft.id().toString(),
          draft.state().name(),
          draft.version(),
          OriginRepresentation.from(draft),
          identifierRestricted
              ? null
              : PatientReferenceRepresentation.from(draft.patientReference()),
          CareRequirementRepresentation.from(draft.careRequirement()));
    }
  }

  /** Current origin-scoped detail; PatientReference appears only on this authorized view. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ReferralCurrentRepresentation(
      String id,
      String status,
      long version,
      OriginRepresentation origin,
      PatientReferenceRepresentation patientReference,
      CareRequirementRepresentation careRequirement,
      Instant createdAt,
      Instant updatedAt,
      SubmittedRevisionRepresentation submittedRevision) {
    static ReferralCurrentRepresentation from(ReferralCurrentView view) {
      ReferralDraft current = view.current();
      return new ReferralCurrentRepresentation(
          current.id().toString(),
          current.state().name(),
          current.version(),
          OriginRepresentation.from(current),
          view.identifierRestricted()
              ? null
              : PatientReferenceRepresentation.from(current.patientReference()),
          CareRequirementRepresentation.from(current.careRequirement()),
          current.createdAt(),
          current.updatedAt(),
          view.submittedRevision()
              .map(
                  revision ->
                      SubmittedRevisionRepresentation.from(revision, view.identifierRestricted()))
              .orElse(null));
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ReferralRevisionRepresentation(
      String id, SubmittedRevisionRepresentation submittedRevision) {
    static ReferralRevisionRepresentation from(
        String id, SubmittedReferralRevision revision, boolean identifierRestricted) {
      return new ReferralRevisionRepresentation(
          id, SubmittedRevisionRepresentation.from(revision, identifierRestricted));
    }
  }

  /** List projection intentionally has no PatientReference or clinical-context field. */
  public record ReferralListItemRepresentation(
      String id,
      String status,
      long version,
      OriginRepresentation origin,
      Instant relevantAt,
      Instant submittedAt) {
    static ReferralListItemRepresentation from(ReferralListItem item) {
      return new ReferralListItemRepresentation(
          item.id().toString(),
          item.state().name(),
          item.version(),
          new OriginRepresentation(
              item.originOrganization().value(),
              item.originFacility().map(ExternalReference::value).orElse(null)),
          item.relevantAt(),
          item.submittedAt().orElse(null));
    }
  }

  public record ReferralListRepresentation(
      List<ReferralListItemRepresentation> items, int page, int size, boolean hasMore) {
    static ReferralListRepresentation from(ReferralListPage page) {
      return new ReferralListRepresentation(
          page.items().stream().map(ReferralListItemRepresentation::from).toList(),
          page.page(),
          page.size(),
          page.hasMore());
    }
  }

  public record ActorRepresentation(
      String kind, String issuer, String identifierType, String identifier) {
    static ActorRepresentation from(ActorReference actor) {
      return new ActorRepresentation(
          actor.kind().name(),
          actor.identity().issuer().value(),
          actor.identity().resourceType(),
          actor.identity().value());
    }
  }

  public record SourceContextRepresentation(
      ActorRepresentation sourceActor, ActorRepresentation submittedBy) {
    static SourceContextRepresentation from(SubmittedReferralRevision revision) {
      return new SourceContextRepresentation(
          ActorRepresentation.from(revision.sourceActor()),
          ActorRepresentation.from(revision.submittedBy()));
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SubmittedReferralRepresentation(
      String id, String status, long version, SubmittedRevisionRepresentation submittedRevision) {
    static SubmittedReferralRepresentation from(
        SubmittedReferralRevision revision, boolean identifierRestricted) {
      return new SubmittedReferralRepresentation(
          revision.referralId().toString(),
          "SUBMITTED",
          revision.referralVersion(),
          SubmittedRevisionRepresentation.from(revision, identifierRestricted));
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SubmittedRevisionRepresentation(
      long revisionNumber,
      OriginRepresentation origin,
      PatientReferenceRepresentation patientReference,
      CareRequirementRepresentation careRequirement,
      SourceContextRepresentation sourceContext,
      Instant submittedAt,
      String reasonCode) {
    static SubmittedRevisionRepresentation from(
        SubmittedReferralRevision revision, boolean identifierRestricted) {
      return new SubmittedRevisionRepresentation(
          revision.revisionNumber(),
          new OriginRepresentation(
              revision.originOrganization().value(),
              revision.originFacility().map(ExternalReference::value).orElse(null)),
          identifierRestricted
              ? null
              : PatientReferenceRepresentation.from(revision.patientReference()),
          CareRequirementRepresentation.from(revision.careRequirement()),
          SourceContextRepresentation.from(revision),
          revision.submittedAt(),
          revision.reasonCode().orElse(null));
    }
  }

  public record ReferralResolutionRepresentation(
      String id,
      String status,
      long version,
      String outcome,
      String reasonCode,
      ActorRepresentation resolvedBy,
      Instant resolvedAt) {
    static ReferralResolutionRepresentation from(
        ReferralDraft referral, ReferralResolution resolution) {
      return new ReferralResolutionRepresentation(
          referral.id().toString(),
          referral.state().name(),
          referral.version(),
          resolution.outcome().name(),
          resolution.reasonCode(),
          ActorRepresentation.from(resolution.resolvedBy()),
          resolution.resolvedAt());
    }
  }

  private record RetentionHoldRequest(ReferralRetentionHoldPurpose purpose, Instant reviewBy) {}

  public record ReferralRetentionHoldAccepted(String holdId, String referralId, Instant reviewBy) {}

  public record ReferralIdentifierRestrictionAccepted(
      String referralId, boolean patientReferenceAccessRestricted, boolean newlyApplied) {}
}
