package org.ayuvahak.matching.adapter.api;

import java.net.URI;
import java.util.Set;
import org.ayuvahak.matching.application.MatchingRecommendationCommandResult;
import org.ayuvahak.matching.application.MatchingRecommendationCommands;
import org.ayuvahak.matching.application.MatchingRecommendationQueries;
import org.ayuvahak.matching.application.RecommendationHistoryResult;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
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
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Authenticated API boundary for explicit, origin-scoped Recommendation generation. */
@RestController
@Lazy
@RequestMapping("/api/v1")
public final class MatchingRecommendationController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  private static final String ORGANIZATION_SCOPE_HEADER = "X-AyuVahak-Organization-Id";
  private static final String FACILITY_SCOPE_HEADER = "X-AyuVahak-Facility-Id";
  private static final Set<String> REQUEST_FIELDS = Set.of("referralId", "revisionNumber");

  private final MatchingRecommendationCommands commands;
  private final MatchingRecommendationQueries queries;
  private final ExecutionContextFactory contextFactory;
  private final ObjectMapper objectMapper;

  public MatchingRecommendationController(
      MatchingRecommendationCommands commands,
      MatchingRecommendationQueries queries,
      ExecutionContextFactory contextFactory,
      ObjectMapper objectMapper) {
    this.commands = commands;
    this.queries = queries;
    this.contextFactory = contextFactory;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/recommendations")
  public ResponseEntity<RecommendationSnapshotRepresentation> generate(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    Request request = readRequest(body);
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    MatchingRecommendationCommandResult result =
        commands.generate(
            context,
            request.referralId(),
            request.revisionNumber(),
            idempotencyKey,
            fingerprint(request));
    HttpHeaders headers = new HttpHeaders();
    if (result.commandReplayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    headers.setLocation(URI.create("/api/v1/recommendations/" + result.snapshot().id()));
    return new ResponseEntity<>(
        RecommendationSnapshotRepresentation.from(result.snapshot()),
        headers,
        org.springframework.http.HttpStatusCode.valueOf(result.responseStatus()));
  }

  @GetMapping("/recommendations/{recommendationId}")
  public RecommendationSnapshotRepresentation get(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @PathVariable String recommendationId) {
    InternalId id;
    try {
      id = InternalId.parse(recommendationId);
    } catch (IllegalArgumentException invalid) {
      throw new PlatformProblemException(
          HttpStatus.NOT_FOUND,
          PlatformErrorCode.MATCHING_SNAPSHOT_NOT_FOUND,
          "The Recommendation is not available in the authorized scope.");
    }
    return RecommendationSnapshotRepresentation.from(
        queries.getSnapshot(context(actor, scope, organizationId, facilityId), id));
  }

  @GetMapping("/referrals/{referralId}/recommendations")
  public RecommendationHistoryRepresentation history(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @PathVariable String referralId,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) String candidateFacilityId) {
    InternalId referral;
    try {
      referral = InternalId.parse(referralId);
    } catch (IllegalArgumentException invalid) {
      throw new PlatformProblemException(
          HttpStatus.NOT_FOUND,
          PlatformErrorCode.MATCHING_REFERRAL_NOT_FOUND,
          "Recommendation history is not available in the authorized scope.");
    }
    java.util.Optional<ExternalReference> candidate;
    try {
      candidate =
          candidateFacilityId == null
              ? java.util.Optional.empty()
              : java.util.Optional.of(directoryReference("Facility", candidateFacilityId));
    } catch (IllegalArgumentException invalid) {
      throw requestInvalid("The candidate Facility identifier is invalid.");
    }
    RecommendationHistoryResult result =
        queries.getHistory(
            context(actor, scope, organizationId, facilityId), referral, candidate, limit);
    return RecommendationHistoryRepresentation.from(result);
  }

  private Request readRequest(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw requestInvalid("The Recommendation request body must be a JSON object.");
    }
    body.properties()
        .forEach(
            field -> {
              if (!REQUEST_FIELDS.contains(field.getKey())) {
                throw requestInvalid("The Recommendation request contains an unsupported field.");
              }
            });
    JsonNode referralId = body.get("referralId");
    JsonNode revision = body.get("revisionNumber");
    if (referralId == null
        || !referralId.isString()
        || revision == null
        || !revision.isIntegralNumber()) {
      throw requestInvalid("The Referral identifier and integer revisionNumber are required.");
    }
    try {
      long number = revision.longValue();
      if (!revision.canConvertToLong() || number < 1) {
        throw new IllegalArgumentException("revision is outside the supported range");
      }
      return new Request(InternalId.parse(referralId.stringValue()), number);
    } catch (IllegalArgumentException malformed) {
      throw requestInvalid("The Recommendation request identifier or revision is invalid.");
    }
  }

  private RequestFingerprint fingerprint(Request request) {
    ObjectNode canonical = objectMapper.createObjectNode();
    canonical.put("referralId", request.referralId().toString());
    canonical.put("revisionNumber", request.revisionNumber());
    return RequestFingerprint.from(canonical);
  }

  private ExecutionContext context(
      ActorReference actor, String scope, String organizationId, String facilityId) {
    if (actor == null || scope == null || organizationId == null) {
      throw invalidScope();
    }
    try {
      ExternalReference organization = directoryReference("Organization", organizationId);
      RequestedExecutionScope requested =
          switch (scope) {
            case "ORGANIZATION" -> {
              if (facilityId != null) {
                throw new IllegalArgumentException("organization context cannot include Facility");
              }
              yield RequestedExecutionScope.organization(organization);
            }
            case "FACILITY" ->
                RequestedExecutionScope.facility(
                    organization, directoryReference("Facility", facilityId));
            default -> throw new IllegalArgumentException("unsupported scope");
          };
      return contextFactory.forInteractiveRequest(actor, requested, correlationId());
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      throw invalidScope();
    }
  }

  private static ExternalReference directoryReference(String type, String id) {
    InternalId.parse(id);
    return new ExternalReference(new IssuerId(DIRECTORY_ISSUER), type, id);
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException invalidScope() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "Recommendation requests require a validated Directory origin scope.");
  }

  private static PlatformProblemException requestInvalid(String detail) {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST, PlatformErrorCode.MATCHING_REQUEST_INVALID, detail);
  }

  private record Request(InternalId referralId, long revisionNumber) {}
}
