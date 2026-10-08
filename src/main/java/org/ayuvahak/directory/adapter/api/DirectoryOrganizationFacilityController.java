package org.ayuvahak.directory.adapter.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.directory.application.DirectoryApplicationService;
import org.ayuvahak.directory.application.DirectoryScopeLookupUnavailableException;
import org.ayuvahak.directory.domain.DirectoryProvenance;
import org.ayuvahak.directory.domain.DirectorySource;
import org.ayuvahak.directory.domain.Facility;
import org.ayuvahak.directory.domain.Organization;
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
      ProvenanceRepresentation provenance) {
    static FacilityRepresentation from(Facility facility) {
      return new FacilityRepresentation(
          facility.id().toString(),
          facility.managingOrganizationId().toString(),
          facility.name(),
          facility.status().name(),
          facility.version(),
          SourceRepresentation.from(facility.source()),
          ProvenanceRepresentation.from(facility.provenance()));
    }
  }
}
