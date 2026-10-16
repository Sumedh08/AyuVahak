package org.ayuvahak.transport.adapter.api;

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
import org.ayuvahak.transport.application.TransportProviderSubmissionCommands;
import org.ayuvahak.transport.domain.TransportProviderSubmission;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Named operator actions submit stable Transport operations to the configured provider only. */
@RestController
@RequestMapping("/api/v1/transport-requests/{transportRequestId}")
public final class TransportProviderSubmissionController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private final TransportProviderSubmissionCommands commands;
  private final ExecutionContextFactory contexts;

  public TransportProviderSubmissionController(
      TransportProviderSubmissionCommands commands, ExecutionContextFactory contexts) {
    this.commands = commands;
    this.contexts = contexts;
  }

  @PostMapping("/provider-dispatch")
  public ResponseEntity<SubmissionRepresentation> dispatch(
      @PathVariable String transportRequestId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId) {
    InternalId requestId = parseId(transportRequestId);
    return submit(
        actor,
        scope,
        organizationId,
        facilityId,
        requestId,
        TransportProviderSubmission.OperationKind.DISPATCH,
        requestId);
  }

  @PostMapping("/cancellation-operations/{operationId}/provider-submit")
  public ResponseEntity<SubmissionRepresentation> cancellation(
      @PathVariable String transportRequestId,
      @PathVariable String operationId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId) {
    return submit(
        actor,
        scope,
        organizationId,
        facilityId,
        parseId(transportRequestId),
        TransportProviderSubmission.OperationKind.CANCELLATION,
        parseId(operationId));
  }

  @PostMapping("/destination-change-operations/{operationId}/provider-submit")
  public ResponseEntity<SubmissionRepresentation> destinationChange(
      @PathVariable String transportRequestId,
      @PathVariable String operationId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId) {
    return submit(
        actor,
        scope,
        organizationId,
        facilityId,
        parseId(transportRequestId),
        TransportProviderSubmission.OperationKind.DESTINATION_CHANGE,
        parseId(operationId));
  }

  private ResponseEntity<SubmissionRepresentation> submit(
      ActorReference actor,
      String scope,
      String organizationId,
      String facilityId,
      InternalId requestId,
      TransportProviderSubmission.OperationKind kind,
      InternalId operationReference) {
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    var result = commands.submit(context, requestId, kind, operationReference);
    HttpHeaders headers = new HttpHeaders();
    headers.set(CorrelationIdFilter.HEADER_NAME, correlationId().value());
    if (result.isEmpty()) {
      return new ResponseEntity<>(
          new SubmissionRepresentation(
              operationReference.toString(),
              kind.name(),
              "MANUAL_COORDINATION_REQUIRED",
              null,
              null,
              null,
              false),
          headers,
          HttpStatus.ACCEPTED);
    }
    var submission = result.orElseThrow();
    if (submission.replayed()) headers.set("Idempotency-Replayed", "true");
    var value = submission.submission();
    return new ResponseEntity<>(
        new SubmissionRepresentation(
            value.operationReference().toString(),
            value.operationKind().name(),
            value.state().name(),
            value.configurationVersion().orElse(null),
            value.responseStatus().orElse(null),
            value.failureCode().orElse(null),
            submission.replayed()),
        headers,
        HttpStatus.ACCEPTED);
  }

  private ExecutionContext context(
      ActorReference actor, String scope, String organizationId, String facilityId) {
    if (actor == null
        || actor.kind() != ActorKind.HUMAN
        || !"FACILITY".equals(scope)
        || organizationId == null
        || facilityId == null) {
      throw invalidScope();
    }
    try {
      return contexts.forInteractiveRequest(
          actor,
          RequestedExecutionScope.facility(
              reference("Organization", organizationId), reference("Facility", facilityId)),
          correlationId());
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      throw invalidScope();
    }
  }

  private static ExternalReference reference(String type, String value) {
    return new ExternalReference(
        new IssuerId(DIRECTORY_ISSUER), type, InternalId.parse(value).toString());
  }

  private static InternalId parseId(String value) {
    try {
      return InternalId.parse(value);
    } catch (RuntimeException malformed) {
      throw new PlatformProblemException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSPORT_EVIDENCE_INVALID,
          "A valid Transport operation reference is required.");
    }
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException invalidScope() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "Provider submission requires an authorized origin Facility operator.");
  }

  public record SubmissionRepresentation(
      String operationReference,
      String operationKind,
      String state,
      Integer configurationVersion,
      Integer responseStatus,
      String failureCode,
      boolean replayed) {}
}
