package org.ayuvahak.referral.adapter.api;

import java.util.Objects;
import java.util.Set;
import org.ayuvahak.directory.application.DirectoryScopeLookupUnavailableException;
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
import org.ayuvahak.referral.application.PatientContextResult;
import org.ayuvahak.referral.application.ReferralPatientContextQueries;
import org.ayuvahak.referral.application.ReferralPatientContextRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Fail-closed HTTP seam for Referral-owned patient-context disclosure. */
@RestController
@RequestMapping("/api/v1/referrals")
final class ReferralPatientContextController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  private static final String ORGANIZATION_SCOPE_HEADER = "X-AyuVahak-Organization-Id";
  private static final String FACILITY_SCOPE_HEADER = "X-AyuVahak-Facility-Id";
  private static final Set<String> REQUEST_PARAMETERS =
      Set.of("transferId", "requestAttemptId", "purposeCode");

  private final ReferralPatientContextQueries queries;
  private final ExecutionContextFactory contextFactory;

  ReferralPatientContextController(
      ReferralPatientContextQueries queries, ExecutionContextFactory contextFactory) {
    this.queries = Objects.requireNonNull(queries, "Referral patient-context queries");
    this.contextFactory = Objects.requireNonNull(contextFactory, "execution context factory");
  }

  @GetMapping("/{referralId}/patient-context")
  PatientContextResult retrieve(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @PathVariable String referralId,
      @RequestParam MultiValueMap<String, String> parameters) {
    if (actor == null) {
      throw new PlatformProblemException(
          HttpStatus.UNAUTHORIZED,
          PlatformErrorCode.AUTHENTICATION_REQUIRED,
          "A verified actor is required to request patient context.");
    }
    CorrelationId correlationId = correlationId();
    InternalId requestedReferralId = parseInternalId(referralId);
    ReferralPatientContextRequest request = readRequest(parameters);
    ExecutionContext context = context(actor, scope, organizationId, facilityId, correlationId);
    return queries.retrieve(context, requestedReferralId, request);
  }

  private ReferralPatientContextRequest readRequest(MultiValueMap<String, String> parameters) {
    if (parameters == null
        || !parameters.keySet().equals(REQUEST_PARAMETERS)
        || parameters.values().stream().anyMatch(values -> values == null || values.size() != 1)) {
      throw malformedRequest();
    }
    try {
      return new ReferralPatientContextRequest(
          parseInternalId(parameters.getFirst("transferId")),
          parseInternalId(parameters.getFirst("requestAttemptId")),
          parameters.getFirst("purposeCode"));
    } catch (IllegalArgumentException malformed) {
      throw malformedRequest();
    }
  }

  private ExecutionContext context(
      ActorReference actor,
      String scope,
      String organizationId,
      String facilityId,
      CorrelationId correlationId) {
    try {
      RequestedExecutionScope requestedScope;
      if ("FACILITY".equals(scope) && organizationId != null && facilityId != null) {
        requestedScope =
            RequestedExecutionScope.facility(
                directoryReference("Organization", organizationId),
                directoryReference("Facility", facilityId));
      } else {
        queries.recordInvalidScope(actor, correlationId);
        throw invalidScope();
      }
      return actor.kind() == ActorKind.HUMAN
          ? contextFactory.forInteractiveRequest(actor, requestedScope, correlationId)
          : contextFactory.forIntegrationRequest(actor, requestedScope, correlationId);
    } catch (DirectoryScopeLookupUnavailableException unavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.REFERRAL_UNAVAILABLE,
          "Directory could not validate the receiving facility scope.");
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      queries.recordInvalidScope(actor, correlationId);
      throw invalidScope();
    }
  }

  private static ExternalReference directoryReference(String resourceType, String value) {
    return new ExternalReference(
        new IssuerId(DIRECTORY_ISSUER), resourceType, InternalId.parse(value).toString());
  }

  private static InternalId parseInternalId(String serialized) {
    try {
      return InternalId.parse(serialized);
    } catch (IllegalArgumentException malformed) {
      throw malformedRequest();
    }
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    if (value == null || value.isBlank()) {
      return CorrelationId.generate();
    }
    try {
      return new CorrelationId(value);
    } catch (IllegalArgumentException invalid) {
      return CorrelationId.generate();
    }
  }

  private static PlatformProblemException malformedRequest() {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST,
        PlatformErrorCode.MALFORMED_REQUEST,
        "The patient-context request is malformed.");
  }

  private static PlatformProblemException invalidScope() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "Patient-context disclosure requires a validated receiving facility scope.");
  }
}
