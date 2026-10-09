package org.ayuvahak.directory.adapter.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.ayuvahak.directory.application.DirectoryApplicationService;
import org.ayuvahak.directory.application.DirectoryExternalIdentifierHistoryEntry;
import org.ayuvahak.directory.application.DirectoryExternalIdentifierPage;
import org.ayuvahak.directory.application.DirectoryFacilitySearchEntry;
import org.ayuvahak.directory.application.DirectoryScopeLookupUnavailableException;
import org.ayuvahak.directory.application.FacilityDirectoryView;
import org.ayuvahak.directory.application.LocatedFacilitySearchResult;
import org.ayuvahak.directory.application.UnlocatedFacilitySearchResult;
import org.ayuvahak.directory.domain.CapabilityVerificationStatus;
import org.ayuvahak.directory.domain.CareCapability;
import org.ayuvahak.directory.domain.DirectoryConceptType;
import org.ayuvahak.directory.domain.DirectoryExternalIdentifier;
import org.ayuvahak.directory.domain.DirectoryLocation;
import org.ayuvahak.directory.domain.DirectoryProvenance;
import org.ayuvahak.directory.domain.DirectorySource;
import org.ayuvahak.directory.domain.Facility;
import org.ayuvahak.directory.domain.FacilityCapabilityAssertion;
import org.ayuvahak.directory.domain.HealthcareService;
import org.ayuvahak.directory.domain.Organization;
import org.ayuvahak.directory.domain.Wgs84Coordinate;
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
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Minimal protected HTTP surface for Organization and Facility coordination projections. */
@Validated
@RestController
@RequestMapping("/api/v1/directory")
public class DirectoryOrganizationFacilityController {

  public static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  public static final String ORGANIZATION_SCOPE_HEADER = "X-AyuVahak-Organization-Id";
  public static final String FACILITY_SCOPE_HEADER = "X-AyuVahak-Facility-Id";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final Set<String> FACILITY_SEARCH_QUERY_PARAMETERS =
      Set.of(
          "centerLongitude",
          "centerLatitude",
          "radiusMeters",
          "organizationId",
          "facilityId",
          "requiredCapability",
          "pageSize",
          "offset");

  private final DirectoryApplicationService directory;
  private final ExecutionContextFactory contextFactory;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public DirectoryOrganizationFacilityController(
      DirectoryApplicationService directory,
      ExecutionContextFactory contextFactory,
      ObjectMapper objectMapper,
      @Qualifier("auditClock") Clock clock) {
    this.directory = directory;
    this.contextFactory = contextFactory;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @PostMapping("/concepts/{conceptType}/{conceptId}/external-identifiers")
  public ResponseEntity<ResourceCreated> addExternalIdentifier(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String conceptType,
      @PathVariable String conceptId,
      @Valid @RequestBody ExternalIdentifierRequest request) {
    DirectoryConceptType type = parseConceptType(conceptType);
    InternalId id = parseId(conceptId);
    ExecutionContext context =
        context(
            actor, scope, organizationScope, facilityScope, "DIRECTORY_EXTERNAL_IDENTIFIER_ADD");
    InternalId identifierId =
        directory.addExternalIdentifier(
            context,
            type,
            id,
            request.toExternalReference(),
            request.provenance().toProvenance(context, clock.instant()),
            idempotencyKey,
            fingerprint(externalIdentifierCommand(type, id, request)));
    return ResponseEntity.created(
            URI.create("/api/v1/directory/external-identifiers/" + identifierId))
        .body(new ResourceCreated(identifierId.toString()));
  }

  @GetMapping("/concepts/{conceptType}/{conceptId}/external-identifiers")
  public ExternalIdentifierPageRepresentation listExternalIdentifiers(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @PathVariable String conceptType,
      @PathVariable String conceptId,
      @RequestParam(defaultValue = "false") boolean includeHistory,
      @RequestParam(defaultValue = "0") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    DirectoryConceptType type = parseConceptType(conceptType);
    InternalId id = parseId(conceptId);
    ExecutionContext context =
        context(
            actor, scope, organizationScope, facilityScope, "DIRECTORY_EXTERNAL_IDENTIFIER_READ");
    return ExternalIdentifierPageRepresentation.from(
        directory.listExternalIdentifiers(context, type, id, includeHistory, limit, offset));
  }

  @PostMapping("/external-identifiers/{identifierId}/removal")
  public ResponseEntity<Void> removeExternalIdentifier(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String identifierId,
      @Valid @RequestBody ExternalIdentifierTransitionRequest request) {
    InternalId id = parseId(identifierId);
    ExecutionContext context =
        context(
            actor, scope, organizationScope, facilityScope, "DIRECTORY_EXTERNAL_IDENTIFIER_REMOVE");
    directory.removeExternalIdentifier(
        context,
        id,
        request.expectedVersion(),
        request.provenance().toProvenance(context, clock.instant()),
        idempotencyKey,
        fingerprint(externalIdentifierTransitionCommand(request)));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/external-identifiers/{identifierId}/supersession")
  public ResponseEntity<ResourceCreated> supersedeExternalIdentifier(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String identifierId,
      @Valid @RequestBody ExternalIdentifierSupersessionRequest request) {
    InternalId id = parseId(identifierId);
    ExecutionContext context =
        context(
            actor,
            scope,
            organizationScope,
            facilityScope,
            "DIRECTORY_EXTERNAL_IDENTIFIER_SUPERSEDE");
    InternalId replacementId =
        directory.supersedeExternalIdentifier(
            context,
            id,
            request.expectedVersion(),
            request.toExternalReference(),
            request.provenance().toProvenance(context, clock.instant()),
            idempotencyKey,
            fingerprint(externalIdentifierSupersessionCommand(id, request)));
    return ResponseEntity.created(
            URI.create("/api/v1/directory/external-identifiers/" + replacementId))
        .body(new ResourceCreated(replacementId.toString()));
  }

  @PostMapping("/organizations")
  public ResponseEntity<ResourceCreated> createOrganization(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody CreateOrganizationRequest request) {
    ExecutionContext context =
        context(actor, scope, organizationScope, facilityScope, "DIRECTORY_ORGANIZATION_CREATE");
    InternalId id =
        directory.createOrganization(
            context,
            request.name(),
            optionalInternalId(request.parentOrganizationId()),
            request.source().toSource("Organization"),
            request.provenance().toProvenance(context, clock.instant()),
            idempotencyKey,
            fingerprint(organizationCommand(request)));
    return ResponseEntity.created(URI.create("/api/v1/directory/organizations/" + id))
        .body(new ResourceCreated(id.toString()));
  }

  @GetMapping("/organizations/{organizationId}")
  public OrganizationRepresentation getOrganization(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @PathVariable String organizationId) {
    ExecutionContext context =
        context(actor, scope, organizationScope, facilityScope, "DIRECTORY_ORGANIZATION_READ");
    return OrganizationRepresentation.from(
        directory.getOrganization(context, parseId(organizationId)));
  }

  @PatchMapping("/organizations/{organizationId}")
  public ResponseEntity<Void> correctOrganization(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String organizationId,
      @Valid @RequestBody CorrectionRequest request) {
    ExecutionContext context =
        context(actor, scope, organizationScope, facilityScope, "DIRECTORY_ORGANIZATION_CORRECT");
    directory.correctOrganization(
        context,
        parseId(organizationId),
        request.expectedVersion(),
        request.name(),
        request.provenance().toProvenance(context, clock.instant()),
        idempotencyKey,
        fingerprint(correctionCommand(request)));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/facilities")
  public ResponseEntity<ResourceCreated> createFacility(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody CreateFacilityRequest request) {
    ExecutionContext context =
        context(actor, scope, organizationScope, facilityScope, "DIRECTORY_FACILITY_CREATE");
    InternalId id =
        directory.createFacility(
            context,
            parseId(request.managingOrganizationId()),
            request.name(),
            request.source().toSource("Facility"),
            request.provenance().toProvenance(context, clock.instant()),
            idempotencyKey,
            fingerprint(facilityCommand(request)));
    return ResponseEntity.created(URI.create("/api/v1/directory/facilities/" + id))
        .body(new ResourceCreated(id.toString()));
  }

  @GetMapping("/facilities/{facilityId}")
  public FacilityRepresentation getFacility(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @PathVariable String facilityId) {
    ExecutionContext context =
        context(actor, scope, organizationScope, facilityScope, "DIRECTORY_FACILITY_READ");
    return FacilityRepresentation.from(directory.getFacility(context, parseId(facilityId)));
  }

  @PostMapping("/locations")
  public ResponseEntity<ResourceCreated> createLocation(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody CreateLocationRequest request) {
    ExecutionContext context =
        context(actor, scope, organizationScope, facilityScope, "DIRECTORY_LOCATION_CREATE");
    InternalId id =
        directory.createLocation(
            context,
            parseId(request.facilityId()),
            request.name(),
            coordinate(request.longitude(), request.latitude()),
            request.primary(),
            request.source().toSource("Location"),
            request.provenance().toProvenance(context, clock.instant()),
            idempotencyKey,
            fingerprint(locationCommand(request)));
    return ResponseEntity.created(URI.create("/api/v1/directory/locations/" + id))
        .body(new ResourceCreated(id.toString()));
  }

  @PatchMapping("/locations/{locationId}")
  public ResponseEntity<Void> correctLocation(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String locationId,
      @Valid @RequestBody LocationCorrectionRequest request) {
    ExecutionContext context =
        context(actor, scope, organizationScope, facilityScope, "DIRECTORY_LOCATION_CORRECT");
    directory.correctLocation(
        context,
        parseId(locationId),
        request.expectedVersion(),
        request.name(),
        coordinate(request.longitude(), request.latitude()),
        request.primary(),
        request.provenance().toProvenance(context, clock.instant()),
        idempotencyKey,
        fingerprint(locationCorrectionCommand(request)));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/healthcare-services")
  public ResponseEntity<ResourceCreated> createHealthcareService(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody CreateHealthcareServiceRequest request) {
    ExecutionContext context =
        context(
            actor, scope, organizationScope, facilityScope, "DIRECTORY_HEALTHCARE_SERVICE_CREATE");
    InternalId id =
        directory.createHealthcareService(
            context,
            parseId(request.organizationId()),
            parseId(request.facilityId()),
            optionalInternalId(request.locationId()),
            request.name(),
            request.source().toSource("HealthcareService"),
            request.provenance().toProvenance(context, clock.instant()),
            idempotencyKey,
            fingerprint(healthcareServiceCommand(request)));
    return ResponseEntity.created(URI.create("/api/v1/directory/healthcare-services/" + id))
        .body(new ResourceCreated(id.toString()));
  }

  @GetMapping("/healthcare-services/{serviceId}")
  public HealthcareServiceRepresentation getHealthcareService(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @PathVariable String serviceId) {
    ExecutionContext context =
        context(
            actor, scope, organizationScope, facilityScope, "DIRECTORY_HEALTHCARE_SERVICE_READ");
    return HealthcareServiceRepresentation.from(
        directory.getHealthcareService(context, parseId(serviceId)));
  }

  @PatchMapping("/healthcare-services/{serviceId}")
  public ResponseEntity<Void> correctHealthcareService(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String serviceId,
      @Valid @RequestBody HealthcareServiceCorrectionRequest request) {
    ExecutionContext context =
        context(
            actor, scope, organizationScope, facilityScope, "DIRECTORY_HEALTHCARE_SERVICE_CORRECT");
    directory.correctHealthcareService(
        context,
        parseId(serviceId),
        request.expectedVersion(),
        request.name(),
        optionalInternalId(request.locationId()),
        request.provenance().toProvenance(context, clock.instant()),
        idempotencyKey,
        fingerprint(healthcareServiceCorrectionCommand(request)));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/healthcare-services/{serviceId}/deactivation")
  public ResponseEntity<Void> deactivateHealthcareService(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String serviceId,
      @Valid @RequestBody DeactivationRequest request) {
    ExecutionContext context =
        context(
            actor,
            scope,
            organizationScope,
            facilityScope,
            "DIRECTORY_HEALTHCARE_SERVICE_DEACTIVATE");
    directory.deactivateHealthcareService(
        context,
        parseId(serviceId),
        request.expectedVersion(),
        request.provenance().toProvenance(context, clock.instant()),
        idempotencyKey,
        fingerprint(deactivationCommand(request)));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/capabilities")
  public ResponseEntity<ResourceCreated> createCareCapability(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody CreateCareCapabilityRequest request) {
    ExecutionContext context =
        context(actor, scope, organizationScope, facilityScope, "DIRECTORY_CARE_CAPABILITY_CREATE");
    InternalId id =
        directory.createCareCapability(
            context,
            request.code(),
            request.displayName(),
            optionalInternalId(request.parentCapabilityId()),
            request.source().toSource("CareCapability"),
            request.provenance().toProvenance(context, clock.instant()),
            idempotencyKey,
            fingerprint(capabilityCommand(request)));
    return ResponseEntity.created(URI.create("/api/v1/directory/capabilities/" + id))
        .body(new ResourceCreated(id.toString()));
  }

  @GetMapping("/capabilities")
  public List<CareCapabilityRepresentation> listCareCapabilities(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope) {
    ExecutionContext context =
        context(
            actor, scope, organizationScope, facilityScope, "DIRECTORY_CARE_CAPABILITY_LIST_READ");
    return directory.listCareCapabilities(context).stream()
        .map(CareCapabilityRepresentation::from)
        .toList();
  }

  @PatchMapping("/capabilities/{capabilityId}")
  public ResponseEntity<Void> correctCareCapability(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String capabilityId,
      @Valid @RequestBody CareCapabilityCorrectionRequest request) {
    ExecutionContext context =
        context(
            actor, scope, organizationScope, facilityScope, "DIRECTORY_CARE_CAPABILITY_CORRECT");
    directory.correctCareCapabilityDisplayName(
        context,
        parseId(capabilityId),
        request.expectedVersion(),
        request.displayName(),
        request.provenance().toProvenance(context, clock.instant()),
        idempotencyKey,
        fingerprint(capabilityCorrectionCommand(request)));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/capabilities/{capabilityId}/deactivation")
  public ResponseEntity<Void> deactivateCareCapability(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String capabilityId,
      @Valid @RequestBody DeactivationRequest request) {
    ExecutionContext context =
        context(
            actor, scope, organizationScope, facilityScope, "DIRECTORY_CARE_CAPABILITY_DEACTIVATE");
    directory.deactivateCareCapability(
        context,
        parseId(capabilityId),
        request.expectedVersion(),
        request.provenance().toProvenance(context, clock.instant()),
        idempotencyKey,
        fingerprint(deactivationCommand(request)));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/facilities/{facilityId}/capability-assertions")
  public ResponseEntity<ResourceCreated> createFacilityCapabilityAssertion(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String facilityId,
      @Valid @RequestBody CreateFacilityCapabilityAssertionRequest request) {
    ExecutionContext context =
        context(
            actor,
            scope,
            organizationScope,
            facilityScope,
            "DIRECTORY_CAPABILITY_ASSERTION_CREATE");
    InternalId id =
        directory.createFacilityCapabilityAssertion(
            context,
            parseId(facilityId),
            request.capabilityCode(),
            optionalInternalId(request.healthcareServiceId()),
            request.verificationStatus(),
            request.source().toSource("FacilityCapabilityAssertion"),
            request.provenance().toProvenance(context, clock.instant()),
            request.effectiveFrom(),
            Optional.ofNullable(request.effectiveUntil()),
            idempotencyKey,
            fingerprint(assertionCommand(request)));
    return ResponseEntity.created(URI.create("/api/v1/directory/capability-assertions/" + id))
        .body(new ResourceCreated(id.toString()));
  }

  @GetMapping("/capability-assertions/{assertionId}")
  public FacilityCapabilityAssertionRepresentation getFacilityCapabilityAssertion(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @PathVariable String assertionId) {
    ExecutionContext context =
        context(
            actor, scope, organizationScope, facilityScope, "DIRECTORY_CAPABILITY_ASSERTION_READ");
    return FacilityCapabilityAssertionRepresentation.from(
        directory.getFacilityCapabilityAssertion(context, parseId(assertionId)));
  }

  @PostMapping("/capability-assertions/{assertionId}/corrections")
  public ResponseEntity<Void> correctFacilityCapabilityAssertion(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String assertionId,
      @Valid @RequestBody CapabilityAssertionCorrectionRequest request) {
    ExecutionContext context =
        context(
            actor,
            scope,
            organizationScope,
            facilityScope,
            "DIRECTORY_CAPABILITY_ASSERTION_CORRECT");
    directory.correctFacilityCapabilityAssertion(
        context,
        parseId(assertionId),
        request.expectedVersion(),
        request.verificationStatus(),
        request.source().toSource("FacilityCapabilityAssertion"),
        request.provenance().toProvenance(context, clock.instant()),
        request.effectiveFrom(),
        Optional.ofNullable(request.effectiveUntil()),
        idempotencyKey,
        fingerprint(assertionCorrectionCommand(request)));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/facilities/{facilityId}/capability-evidence")
  public FacilityCapabilityEvaluationRepresentation evaluateFacilityCapabilities(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @PathVariable String facilityId,
      @RequestParam("requiredCapability") List<String> requiredCapabilities) {
    ExecutionContext context =
        context(
            actor,
            scope,
            organizationScope,
            facilityScope,
            "DIRECTORY_FACILITY_CAPABILITY_EVIDENCE_READ");
    return FacilityCapabilityEvaluationRepresentation.from(
        directory.evaluateFacilityCapabilities(context, parseId(facilityId), requiredCapabilities));
  }

  @GetMapping("/facilities")
  public FacilitySearchRepresentation findFacilities(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestParam(required = false) Double centerLongitude,
      @RequestParam(required = false) Double centerLatitude,
      @RequestParam(required = false) Double radiusMeters,
      @RequestParam(required = false) String organizationId,
      @RequestParam(required = false) String facilityId,
      @RequestParam(name = "requiredCapability", required = false)
          List<String> requiredCapabilityCodes,
      @RequestParam(required = false, defaultValue = "0") int pageSize,
      @RequestParam(required = false, defaultValue = "0") int offset,
      HttpServletRequest servletRequest) {
    ExecutionContext context =
        context(actor, scope, organizationScope, facilityScope, "DIRECTORY_FACILITY_SEARCH_READ");
    rejectUnknownFacilitySearchParameters(servletRequest);
    Optional<InternalId> organizationFilter = optionalInternalId(organizationId);
    Optional<InternalId> facilityFilter = optionalInternalId(facilityId);
    boolean hasLongitude = centerLongitude != null;
    boolean hasLatitude = centerLatitude != null;
    boolean hasRadius = radiusMeters != null;
    if (!hasLongitude && !hasLatitude && !hasRadius) {
      var result =
          directory.findFacilities(
              context,
              organizationFilter,
              facilityFilter,
              requiredCapabilityCodes,
              pageSize,
              offset);
      return new FacilitySearchRepresentation(
          "DIRECTORY_FILTERED",
          result.facilities().stream().map(FacilitySearchItemRepresentation::from).toList(),
          List.of(),
          result.pageSize(),
          result.offset(),
          result.hasMore(),
          false);
    }
    if (!hasLongitude || !hasLatitude || !hasRadius) {
      throw searchValidation("Geographic search requires center longitude, latitude, and radius.");
    }
    var result =
        directory.findNearbyFacilities(
            context,
            coordinate(centerLongitude, centerLatitude).orElseThrow(),
            radiusMeters,
            organizationFilter,
            facilityFilter,
            requiredCapabilityCodes,
            pageSize,
            offset);
    return new FacilitySearchRepresentation(
        "NEARBY",
        result.nearby().stream().map(FacilitySearchItemRepresentation::from).toList(),
        result.unlocated().stream().map(FacilitySearchItemRepresentation::from).toList(),
        result.pageSize(),
        result.offset(),
        result.hasMoreNearby(),
        result.hasMoreUnlocated());
  }

  @PatchMapping("/facilities/{facilityId}")
  public ResponseEntity<Void> correctFacility(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String facilityId,
      @Valid @RequestBody CorrectionRequest request) {
    ExecutionContext context =
        context(actor, scope, organizationScope, facilityScope, "DIRECTORY_FACILITY_CORRECT");
    directory.correctFacility(
        context,
        parseId(facilityId),
        request.expectedVersion(),
        request.name(),
        request.provenance().toProvenance(context, clock.instant()),
        idempotencyKey,
        fingerprint(correctionCommand(request)));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/facilities/{facilityId}/deactivation")
  public ResponseEntity<Void> deactivateFacility(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationScope,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String facilityId,
      @Valid @RequestBody DeactivationRequest request) {
    ExecutionContext context =
        context(actor, scope, organizationScope, facilityScope, "DIRECTORY_FACILITY_DEACTIVATE");
    directory.deactivateFacility(
        context,
        parseId(facilityId),
        request.expectedVersion(),
        request.provenance().toProvenance(context, clock.instant()),
        idempotencyKey,
        fingerprint(deactivationCommand(request)));
    return ResponseEntity.noContent().build();
  }

  private ExecutionContext context(
      ActorReference actor,
      String scope,
      String organizationId,
      String facilityId,
      String actionCode) {
    CorrelationId correlationId = correlationId();
    try {
      RequestedExecutionScope requestedScope =
          switch (scope == null ? "" : scope) {
            case "PLATFORM" -> {
              requireAbsent(organizationId, facilityId);
              yield RequestedExecutionScope.platform();
            }
            case "ORGANIZATION" -> {
              if (organizationId == null || facilityId != null) {
                throw new IllegalArgumentException("organization scope shape is invalid");
              }
              yield RequestedExecutionScope.organization(reference("Organization", organizationId));
            }
            case "FACILITY" -> {
              if (organizationId == null || facilityId == null) {
                throw new IllegalArgumentException("facility scope shape is invalid");
              }
              yield RequestedExecutionScope.facility(
                  reference("Organization", organizationId), reference("Facility", facilityId));
            }
            default -> throw new IllegalArgumentException("explicit execution scope is required");
          };
      if (actor.kind() == ActorKind.HUMAN) {
        return contextFactory.forInteractiveRequest(actor, requestedScope, correlationId);
      }
      return contextFactory.forIntegrationRequest(actor, requestedScope, correlationId);
    } catch (DirectoryScopeLookupUnavailableException unavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
          "Directory-owned scope references could not be validated safely.");
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      directory.recordInvalidExecutionScope(actor, correlationId, actionCode);
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "The requested Directory execution scope is not active or valid.");
    }
  }

  private static void requireAbsent(String organizationId, String facilityId) {
    if (organizationId != null || facilityId != null) {
      throw new IllegalArgumentException("platform scope cannot include resource references");
    }
  }

  private static ExternalReference reference(String type, String serializedId) {
    InternalId id = parseId(serializedId);
    return new ExternalReference(new IssuerId(DIRECTORY_ISSUER), type, id.toString());
  }

  private static InternalId parseId(String serializedId) {
    try {
      return InternalId.parse(serializedId);
    } catch (RuntimeException malformed) {
      throw new PlatformProblemException(
          HttpStatus.NOT_FOUND,
          PlatformErrorCode.DIRECTORY_NOT_FOUND,
          "The Directory record was not found.");
    }
  }

  private static DirectoryConceptType parseConceptType(String serializedType) {
    try {
      return DirectoryConceptType.valueOf(serializedType);
    } catch (IllegalArgumentException | NullPointerException malformed) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.DIRECTORY_VALIDATION_FAILED,
          "The requested Directory concept type does not support external identifiers.");
    }
  }

  private static Optional<InternalId> optionalInternalId(String serializedId) {
    return serializedId == null ? Optional.empty() : Optional.of(parseId(serializedId));
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private RequestFingerprint fingerprint(ObjectNode normalized) {
    return RequestFingerprint.from(normalized);
  }

  private ObjectNode externalIdentifierCommand(
      DirectoryConceptType conceptType, InternalId conceptId, ExternalIdentifierRequest request) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("conceptType", conceptType.name());
    normalized.put("conceptId", conceptId.toString());
    normalized.put("issuer", request.issuer());
    normalized.put("resourceType", request.resourceType());
    normalized.put("value", request.value());
    normalized.set("provenance", provenanceNode(request.provenance()));
    return normalized;
  }

  private ObjectNode externalIdentifierTransitionCommand(
      ExternalIdentifierTransitionRequest request) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("expectedVersion", request.expectedVersion());
    normalized.set("provenance", provenanceNode(request.provenance()));
    return normalized;
  }

  private ObjectNode externalIdentifierSupersessionCommand(
      InternalId identifierId, ExternalIdentifierSupersessionRequest request) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("identifierId", identifierId.toString());
    normalized.put("expectedVersion", request.expectedVersion());
    normalized.put("issuer", request.issuer());
    normalized.put("resourceType", request.resourceType());
    normalized.put("value", request.value());
    normalized.set("provenance", provenanceNode(request.provenance()));
    return normalized;
  }

  private ObjectNode organizationCommand(CreateOrganizationRequest request) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("name", request.name());
    if (request.parentOrganizationId() != null) {
      normalized.put("parentOrganizationId", request.parentOrganizationId());
    }
    normalized.set("source", sourceNode(request.source()));
    normalized.set("provenance", provenanceNode(request.provenance()));
    return normalized;
  }

  private ObjectNode facilityCommand(CreateFacilityRequest request) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("name", request.name());
    normalized.put("managingOrganizationId", request.managingOrganizationId());
    normalized.set("source", sourceNode(request.source()));
    normalized.set("provenance", provenanceNode(request.provenance()));
    return normalized;
  }

  private ObjectNode correctionCommand(CorrectionRequest request) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("expectedVersion", request.expectedVersion());
    normalized.put("name", request.name());
    normalized.set("provenance", provenanceNode(request.provenance()));
    return normalized;
  }

  private ObjectNode deactivationCommand(DeactivationRequest request) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("expectedVersion", request.expectedVersion());
    normalized.set("provenance", provenanceNode(request.provenance()));
    return normalized;
  }

  private ObjectNode locationCommand(CreateLocationRequest request) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("facilityId", request.facilityId());
    normalized.put("name", request.name());
    addCoordinate(normalized, request.longitude(), request.latitude());
    normalized.put("primary", request.primary());
    normalized.set("source", sourceNode(request.source()));
    normalized.set("provenance", provenanceNode(request.provenance()));
    return normalized;
  }

  private ObjectNode locationCorrectionCommand(LocationCorrectionRequest request) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("expectedVersion", request.expectedVersion());
    normalized.put("name", request.name());
    addCoordinate(normalized, request.longitude(), request.latitude());
    normalized.put("primary", request.primary());
    normalized.set("provenance", provenanceNode(request.provenance()));
    return normalized;
  }

  private ObjectNode healthcareServiceCommand(CreateHealthcareServiceRequest request) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("organizationId", request.organizationId());
    normalized.put("facilityId", request.facilityId());
    if (request.locationId() == null) {
      normalized.putNull("locationId");
    } else {
      normalized.put("locationId", request.locationId());
    }
    normalized.put("name", request.name());
    normalized.set("source", sourceNode(request.source()));
    normalized.set("provenance", provenanceNode(request.provenance()));
    return normalized;
  }

  private ObjectNode healthcareServiceCorrectionCommand(
      HealthcareServiceCorrectionRequest request) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("expectedVersion", request.expectedVersion());
    normalized.put("name", request.name());
    if (request.locationId() == null) {
      normalized.putNull("locationId");
    } else {
      normalized.put("locationId", request.locationId());
    }
    normalized.set("provenance", provenanceNode(request.provenance()));
    return normalized;
  }

  private ObjectNode capabilityCommand(CreateCareCapabilityRequest request) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("code", request.code());
    normalized.put("displayName", request.displayName());
    if (request.parentCapabilityId() == null) {
      normalized.putNull("parentCapabilityId");
    } else {
      normalized.put("parentCapabilityId", request.parentCapabilityId());
    }
    normalized.set("source", sourceNode(request.source()));
    normalized.set("provenance", provenanceNode(request.provenance()));
    return normalized;
  }

  private ObjectNode capabilityCorrectionCommand(CareCapabilityCorrectionRequest request) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("expectedVersion", request.expectedVersion());
    normalized.put("displayName", request.displayName());
    normalized.set("provenance", provenanceNode(request.provenance()));
    return normalized;
  }

  private ObjectNode assertionCommand(CreateFacilityCapabilityAssertionRequest request) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("capabilityCode", request.capabilityCode());
    if (request.healthcareServiceId() == null) {
      normalized.putNull("healthcareServiceId");
    } else {
      normalized.put("healthcareServiceId", request.healthcareServiceId());
    }
    normalized.put("verificationStatus", request.verificationStatus().name());
    normalized.set("source", sourceNode(request.source()));
    normalized.set("provenance", provenanceNode(request.provenance()));
    normalized.put("effectiveFrom", request.effectiveFrom().toString());
    if (request.effectiveUntil() == null) {
      normalized.putNull("effectiveUntil");
    } else {
      normalized.put("effectiveUntil", request.effectiveUntil().toString());
    }
    return normalized;
  }

  private ObjectNode assertionCorrectionCommand(CapabilityAssertionCorrectionRequest request) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("expectedVersion", request.expectedVersion());
    normalized.put("verificationStatus", request.verificationStatus().name());
    normalized.set("source", sourceNode(request.source()));
    normalized.set("provenance", provenanceNode(request.provenance()));
    normalized.put("effectiveFrom", request.effectiveFrom().toString());
    if (request.effectiveUntil() == null) {
      normalized.putNull("effectiveUntil");
    } else {
      normalized.put("effectiveUntil", request.effectiveUntil().toString());
    }
    return normalized;
  }

  private static void addCoordinate(ObjectNode normalized, Double longitude, Double latitude) {
    if (longitude == null) {
      normalized.putNull("longitude");
      normalized.putNull("latitude");
    } else {
      normalized.put("longitude", longitude);
      normalized.put("latitude", latitude);
    }
  }

  private static Optional<Wgs84Coordinate> coordinate(Double longitude, Double latitude) {
    if (longitude == null && latitude == null) {
      return Optional.empty();
    }
    if (longitude == null || latitude == null) {
      throw coordinateInvalid();
    }
    try {
      return Optional.of(new Wgs84Coordinate(longitude, latitude));
    } catch (IllegalArgumentException invalid) {
      throw coordinateInvalid();
    }
  }

  private static PlatformProblemException coordinateInvalid() {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST,
        PlatformErrorCode.DIRECTORY_COORDINATE_INVALID,
        "Coordinates must be finite WGS84 longitude and latitude in that order.");
  }

  private static PlatformProblemException searchValidation(String detail) {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST, PlatformErrorCode.DIRECTORY_VALIDATION_FAILED, detail);
  }

  private static void rejectUnknownFacilitySearchParameters(HttpServletRequest request) {
    boolean hasUnsupportedParameter =
        request.getParameterMap().keySet().stream()
            .anyMatch(name -> !FACILITY_SEARCH_QUERY_PARAMETERS.contains(name));
    if (hasUnsupportedParameter) {
      throw searchValidation("The facility search contains an unsupported query parameter.");
    }
  }

  private ObjectNode sourceNode(SourceRequest request) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("issuer", request.issuer());
    normalized.put("resourceType", request.resourceType());
    normalized.put("reference", request.reference());
    return normalized;
  }

  private ObjectNode provenanceNode(ProvenanceRequest request) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("method", request.method());
    normalized.put("observedAt", request.observedAt().toString());
    normalized.put("reasonCode", request.reasonCode());
    return normalized;
  }

  public record CreateOrganizationRequest(
      @NotBlank String name,
      String parentOrganizationId,
      @Valid @NotNull SourceRequest source,
      @Valid @NotNull ProvenanceRequest provenance) {}

  public record CreateFacilityRequest(
      @NotBlank String name,
      @NotBlank String managingOrganizationId,
      @Valid @NotNull SourceRequest source,
      @Valid @NotNull ProvenanceRequest provenance) {}

  public record CorrectionRequest(
      @Positive long expectedVersion,
      @NotBlank String name,
      @Valid @NotNull ProvenanceRequest provenance) {}

  public record DeactivationRequest(
      @Positive long expectedVersion, @Valid @NotNull ProvenanceRequest provenance) {}

  public record CreateLocationRequest(
      @NotBlank String facilityId,
      @NotBlank String name,
      Double longitude,
      Double latitude,
      @NotNull Boolean primary,
      @Valid @NotNull SourceRequest source,
      @Valid @NotNull ProvenanceRequest provenance) {}

  public record LocationCorrectionRequest(
      @Positive long expectedVersion,
      @NotBlank String name,
      Double longitude,
      Double latitude,
      @NotNull Boolean primary,
      @Valid @NotNull ProvenanceRequest provenance) {}

  public record CreateHealthcareServiceRequest(
      @NotBlank String organizationId,
      @NotBlank String facilityId,
      String locationId,
      @NotBlank String name,
      @Valid @NotNull SourceRequest source,
      @Valid @NotNull ProvenanceRequest provenance) {}

  public record HealthcareServiceCorrectionRequest(
      @Positive long expectedVersion,
      @NotBlank String name,
      String locationId,
      @Valid @NotNull ProvenanceRequest provenance) {}

  public record CreateCareCapabilityRequest(
      @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9._-]{0,63}") String code,
      @NotBlank String displayName,
      String parentCapabilityId,
      @Valid @NotNull SourceRequest source,
      @Valid @NotNull ProvenanceRequest provenance) {}

  public record CareCapabilityCorrectionRequest(
      @Positive long expectedVersion,
      @NotBlank String displayName,
      @Valid @NotNull ProvenanceRequest provenance) {}

  public record CreateFacilityCapabilityAssertionRequest(
      @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9._-]{0,63}") String capabilityCode,
      String healthcareServiceId,
      @NotNull CapabilityVerificationStatus verificationStatus,
      @Valid @NotNull SourceRequest source,
      @Valid @NotNull ProvenanceRequest provenance,
      @NotNull Instant effectiveFrom,
      Instant effectiveUntil) {}

  public record CapabilityAssertionCorrectionRequest(
      @Positive long expectedVersion,
      @NotNull CapabilityVerificationStatus verificationStatus,
      @Valid @NotNull SourceRequest source,
      @Valid @NotNull ProvenanceRequest provenance,
      @NotNull Instant effectiveFrom,
      Instant effectiveUntil) {}

  public record ExternalIdentifierRequest(
      @NotBlank String issuer,
      @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9._-]{0,63}") String resourceType,
      @NotBlank String value,
      @Valid @NotNull ProvenanceRequest provenance) {
    ExternalReference toExternalReference() {
      try {
        return new ExternalReference(new IssuerId(issuer), resourceType, value);
      } catch (IllegalArgumentException malformed) {
        throw new PlatformProblemException(
            HttpStatus.BAD_REQUEST,
            PlatformErrorCode.DIRECTORY_VALIDATION_FAILED,
            "The external identifier issuer, resource type, or value is invalid.");
      }
    }
  }

  public record ExternalIdentifierTransitionRequest(
      @Positive long expectedVersion, @Valid @NotNull ProvenanceRequest provenance) {}

  public record ExternalIdentifierSupersessionRequest(
      @Positive long expectedVersion,
      @NotBlank String issuer,
      @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9._-]{0,63}") String resourceType,
      @NotBlank String value,
      @Valid @NotNull ProvenanceRequest provenance) {
    ExternalReference toExternalReference() {
      return new ExternalIdentifierRequest(issuer, resourceType, value, provenance)
          .toExternalReference();
    }
  }

  public record SourceRequest(
      @NotBlank String issuer,
      @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9._-]{0,63}") String resourceType,
      @NotBlank String reference) {
    DirectorySource toSource(String expectedType) {
      if (!expectedType.equals(resourceType)) {
        throw new PlatformProblemException(
            HttpStatus.BAD_REQUEST,
            PlatformErrorCode.DIRECTORY_VALIDATION_FAILED,
            "The source resource type must match the Directory record type.");
      }
      try {
        return new DirectorySource(
            new ExternalReference(new IssuerId(issuer), resourceType, reference));
      } catch (IllegalArgumentException malformed) {
        throw new PlatformProblemException(
            HttpStatus.BAD_REQUEST,
            PlatformErrorCode.DIRECTORY_VALIDATION_FAILED,
            "The source reference is invalid.");
      }
    }
  }

  public record ProvenanceRequest(
      @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,79}") String method,
      @NotNull Instant observedAt,
      @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,79}") String reasonCode) {
    DirectoryProvenance toProvenance(ExecutionContext context, Instant recordedAt) {
      return new DirectoryProvenance(method, observedAt, context.actor(), recordedAt, reasonCode);
    }
  }

  public record ResourceCreated(String id) {}

  public record ExternalIdentifierPageRepresentation(
      List<ExternalIdentifierHistoryRepresentation> items, int limit, int offset, boolean hasMore) {
    static ExternalIdentifierPageRepresentation from(DirectoryExternalIdentifierPage page) {
      return new ExternalIdentifierPageRepresentation(
          page.items().stream().map(ExternalIdentifierHistoryRepresentation::from).toList(),
          page.limit(),
          page.offset(),
          page.hasMore());
    }
  }

  public record ExternalIdentifierHistoryRepresentation(
      String id,
      String conceptType,
      String conceptId,
      ExternalIdentifierValueRepresentation externalIdentifier,
      String status,
      Optional<String> supersedesIdentifierId,
      ProvenanceRepresentation provenance,
      long version,
      boolean current,
      Instant recordedAt,
      Optional<String> changeReasonCode) {
    static ExternalIdentifierHistoryRepresentation from(
        DirectoryExternalIdentifierHistoryEntry entry) {
      DirectoryExternalIdentifier identifier = entry.identifier();
      return new ExternalIdentifierHistoryRepresentation(
          identifier.id().toString(),
          identifier.conceptType().name(),
          identifier.conceptId().toString(),
          ExternalIdentifierValueRepresentation.from(identifier.externalIdentifier()),
          identifier.status().name(),
          identifier.supersedesIdentifierId().map(InternalId::toString),
          ProvenanceRepresentation.from(identifier.provenance()),
          identifier.version(),
          entry.current(),
          entry.recordedAt(),
          entry.changeReasonCode());
    }
  }

  public record ExternalIdentifierValueRepresentation(
      String issuer, String resourceType, String value) {
    static ExternalIdentifierValueRepresentation from(ExternalReference externalIdentifier) {
      return new ExternalIdentifierValueRepresentation(
          externalIdentifier.issuer().value(),
          externalIdentifier.resourceType(),
          externalIdentifier.value());
    }
  }

  public record SourceRepresentation(String issuer, String resourceType, String reference) {
    static SourceRepresentation from(DirectorySource source) {
      return new SourceRepresentation(
          source.reference().issuer().value(),
          source.reference().resourceType(),
          source.reference().value());
    }
  }

  public record ProvenanceRepresentation(
      String method, Instant observedAt, Instant recordedAt, String reasonCode) {
    static ProvenanceRepresentation from(DirectoryProvenance provenance) {
      return new ProvenanceRepresentation(
          provenance.method(),
          provenance.observedAt(),
          provenance.recordedAt(),
          provenance.reasonCode());
    }
  }

  public record OrganizationRepresentation(
      String id,
      String name,
      String status,
      long version,
      Optional<String> parentOrganizationId,
      SourceRepresentation source,
      ProvenanceRepresentation provenance) {
    static OrganizationRepresentation from(Organization organization) {
      return new OrganizationRepresentation(
          organization.id().toString(),
          organization.name(),
          organization.status().name(),
          organization.version(),
          organization.parentOrganizationId().map(InternalId::toString),
          SourceRepresentation.from(organization.source()),
          ProvenanceRepresentation.from(organization.provenance()));
    }
  }

  public record FacilityRepresentation(
      String id,
      String managingOrganizationId,
      String name,
      String status,
      long version,
      SourceRepresentation source,
      ProvenanceRepresentation provenance,
      Optional<String> primaryLocationId,
      List<LocationRepresentation> locations) {
    static FacilityRepresentation from(FacilityDirectoryView view) {
      Facility facility = view.facility();
      return new FacilityRepresentation(
          facility.id().toString(),
          facility.managingOrganizationId().toString(),
          facility.name(),
          facility.status().name(),
          facility.version(),
          SourceRepresentation.from(facility.source()),
          ProvenanceRepresentation.from(facility.provenance()),
          view.primaryLocation().map(location -> location.id().toString()),
          view.locations().stream().map(LocationRepresentation::from).toList());
    }
  }

  public record CoordinateRepresentation(double longitude, double latitude) {
    static CoordinateRepresentation from(Wgs84Coordinate coordinate) {
      return new CoordinateRepresentation(coordinate.longitude(), coordinate.latitude());
    }
  }

  public record LocationRepresentation(
      String id,
      String facilityId,
      String name,
      Optional<CoordinateRepresentation> coordinates,
      boolean isPrimary,
      long version,
      SourceRepresentation source,
      ProvenanceRepresentation provenance) {
    static LocationRepresentation from(DirectoryLocation location) {
      return new LocationRepresentation(
          location.id().toString(),
          location.facilityId().toString(),
          location.name(),
          location.coordinate().map(CoordinateRepresentation::from),
          location.primary(),
          location.version(),
          SourceRepresentation.from(location.source()),
          ProvenanceRepresentation.from(location.provenance()));
    }
  }

  public record HealthcareServiceRepresentation(
      String id,
      String organizationId,
      String facilityId,
      Optional<String> locationId,
      String name,
      String status,
      long version,
      SourceRepresentation source,
      ProvenanceRepresentation provenance) {
    static HealthcareServiceRepresentation from(HealthcareService service) {
      return new HealthcareServiceRepresentation(
          service.id().toString(),
          service.organizationId().toString(),
          service.facilityId().toString(),
          service.locationId().map(InternalId::toString),
          service.name(),
          service.status().name(),
          service.version(),
          SourceRepresentation.from(service.source()),
          ProvenanceRepresentation.from(service.provenance()));
    }
  }

  public record CareCapabilityRepresentation(
      String id,
      String code,
      String displayName,
      Optional<String> parentCapabilityId,
      String status,
      long version,
      SourceRepresentation source,
      ProvenanceRepresentation provenance) {
    static CareCapabilityRepresentation from(CareCapability capability) {
      return new CareCapabilityRepresentation(
          capability.id().toString(),
          capability.code(),
          capability.displayName(),
          capability.parentCapabilityId().map(InternalId::toString),
          capability.status().name(),
          capability.version(),
          SourceRepresentation.from(capability.source()),
          ProvenanceRepresentation.from(capability.provenance()));
    }
  }

  public record FacilityCapabilityAssertionRepresentation(
      String id,
      String facilityId,
      String capabilityId,
      Optional<String> healthcareServiceId,
      String verificationStatus,
      SourceRepresentation source,
      ProvenanceRepresentation provenance,
      Instant effectiveFrom,
      Optional<Instant> effectiveUntil,
      long version) {
    static FacilityCapabilityAssertionRepresentation from(FacilityCapabilityAssertion assertion) {
      return new FacilityCapabilityAssertionRepresentation(
          assertion.id().toString(),
          assertion.facilityId().toString(),
          assertion.capabilityId().toString(),
          assertion.healthcareServiceId().map(InternalId::toString),
          assertion.verificationStatus().name(),
          SourceRepresentation.from(assertion.source()),
          ProvenanceRepresentation.from(assertion.provenance()),
          assertion.effectiveFrom(),
          assertion.effectiveUntil(),
          assertion.version());
    }
  }

  public record CapabilityRequirementRepresentation(
      String code, String outcome, List<String> evidenceAssertionIds) {}

  public record FacilityCapabilityEvaluationRepresentation(
      String facilityId,
      String facilityStatus,
      Instant evaluatedAt,
      boolean requirementsSatisfied,
      List<CapabilityRequirementRepresentation> requirements) {
    static FacilityCapabilityEvaluationRepresentation from(
        DirectoryApplicationService.FacilityCapabilityEvaluation evaluation) {
      return new FacilityCapabilityEvaluationRepresentation(
          evaluation.facilityId().toString(),
          evaluation.facilityStatus().name(),
          evaluation.evaluatedAt(),
          evaluation.requirementsSatisfied(),
          evaluation.requirements().stream()
              .map(
                  assessment ->
                      new CapabilityRequirementRepresentation(
                          assessment.code(),
                          assessment.outcome().name(),
                          assessment.evidenceAssertionIds()))
              .toList());
    }
  }

  public record FacilitySearchRepresentation(
      String mode,
      List<FacilitySearchItemRepresentation> facilities,
      List<FacilitySearchItemRepresentation> unlocated,
      int pageSize,
      int offset,
      boolean hasMore,
      boolean hasMoreUnlocated) {}

  public record FacilitySearchItemRepresentation(
      String facilityId,
      String managingOrganizationId,
      String facilityName,
      String status,
      long version,
      SourceRepresentation source,
      ProvenanceRepresentation provenance,
      Optional<LocationRepresentation> location,
      Optional<Double> distanceMeters) {
    static FacilitySearchItemRepresentation from(DirectoryFacilitySearchEntry result) {
      Facility facility = result.facility();
      return new FacilitySearchItemRepresentation(
          facility.id().toString(),
          facility.managingOrganizationId().toString(),
          facility.name(),
          facility.status().name(),
          facility.version(),
          SourceRepresentation.from(facility.source()),
          ProvenanceRepresentation.from(facility.provenance()),
          result.primaryLocation().map(LocationRepresentation::from),
          Optional.empty());
    }

    static FacilitySearchItemRepresentation from(LocatedFacilitySearchResult result) {
      Facility facility = result.facility();
      return new FacilitySearchItemRepresentation(
          result.facility().id().toString(),
          result.facility().managingOrganizationId().toString(),
          result.facility().name(),
          facility.status().name(),
          facility.version(),
          SourceRepresentation.from(facility.source()),
          ProvenanceRepresentation.from(facility.provenance()),
          Optional.of(LocationRepresentation.from(result.location())),
          Optional.of(result.distanceMeters()));
    }

    static FacilitySearchItemRepresentation from(UnlocatedFacilitySearchResult result) {
      return from(new DirectoryFacilitySearchEntry(result.facility(), result.primaryLocation()));
    }
  }
}
