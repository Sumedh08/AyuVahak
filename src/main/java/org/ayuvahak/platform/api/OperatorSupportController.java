package org.ayuvahak.platform.api;

import java.util.Objects;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.operatorsupport.application.OperatorSupportProfileStatus;
import org.ayuvahak.platform.operatorsupport.application.OperatorSupportQuestionKey;
import org.ayuvahak.platform.operatorsupport.application.OperatorSupportResponse;
import org.ayuvahak.platform.operatorsupport.application.OperatorSupportService;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Authenticated, finite-topic API for advisory documentation support. */
@RestController
@RequestMapping("/api/v1/operator-support")
public class OperatorSupportController {

  private final OperatorSupportService service;

  public OperatorSupportController(OperatorSupportService service) {
    this.service = Objects.requireNonNull(service, "operator support service");
  }

  @PostMapping(
      path = "/answers",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<OperatorSupportResponse> answer(
      @AuthenticationPrincipal ActorReference actor, @RequestBody JsonNode request) {
    OperatorSupportQuestionKey questionKey = questionKeyFrom(request);
    String correlationValue = MDC.get(CorrelationIdFilter.MDC_KEY);
    CorrelationId correlationId =
        correlationValue == null ? CorrelationId.generate() : new CorrelationId(correlationValue);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.answer(actor, correlationId, questionKey));
  }

  @GetMapping(path = "/profile", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<OperatorSupportProfileStatus> profileStatus(
      @AuthenticationPrincipal ActorReference actor) {
    String correlationValue = MDC.get(CorrelationIdFilter.MDC_KEY);
    CorrelationId correlationId =
        correlationValue == null ? CorrelationId.generate() : new CorrelationId(correlationValue);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.profileStatus(actor, correlationId));
  }

  static OperatorSupportQuestionKey questionKeyFrom(JsonNode request) {
    if (request == null
        || !request.isObject()
        || request.size() != 1
        || !request.has("questionKey")
        || !request.get("questionKey").isString()) {
      throw invalidRequest();
    }
    try {
      return OperatorSupportQuestionKey.valueOf(request.get("questionKey").stringValue());
    } catch (IllegalArgumentException invalidKey) {
      throw invalidRequest();
    }
  }

  private static PlatformProblemException invalidRequest() {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST,
        PlatformErrorCode.VALIDATION_FAILED,
        "A supported operator support topic key is required.");
  }
}
