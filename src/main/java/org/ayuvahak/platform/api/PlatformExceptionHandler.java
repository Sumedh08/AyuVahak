package org.ayuvahak.platform.api;

import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class PlatformExceptionHandler extends ResponseEntityExceptionHandler {

  private static final String VALIDATION_DETAIL = "One or more request fields are invalid.";
  private static final String MALFORMED_DETAIL = "The request could not be parsed.";
  private static final String UNSUPPORTED_MEDIA_TYPE_DETAIL =
      "The request media type is not supported.";

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<ValidationIssue> issues =
        exception.getBindingResult().getFieldErrors().stream()
            .map(PlatformExceptionHandler::toValidationIssue)
            .toList();
    return problemResponse(
        HttpStatus.BAD_REQUEST,
        "Request validation failed",
        VALIDATION_DETAIL,
        PlatformErrorCode.VALIDATION_FAILED,
        issues);
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    if (exception.isForReturnValue()) {
      return internalError();
    }
    return problemResponse(
        HttpStatus.BAD_REQUEST,
        "Request validation failed",
        VALIDATION_DETAIL,
        PlatformErrorCode.VALIDATION_FAILED,
        List.of());
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return problemResponse(
        HttpStatus.BAD_REQUEST,
        "Malformed request",
        MALFORMED_DETAIL,
        PlatformErrorCode.MALFORMED_REQUEST,
        null);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return problemResponse(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "Unsupported media type",
        UNSUPPORTED_MEDIA_TYPE_DETAIL,
        PlatformErrorCode.UNSUPPORTED_MEDIA_TYPE,
        null);
  }

  @Override
  protected ResponseEntity<Object> handleNoHandlerFoundException(
      NoHandlerFoundException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return problemResponse(
        HttpStatus.NOT_FOUND,
        "Resource not found",
        "The requested resource was not found.",
        PlatformErrorCode.NOT_FOUND,
        null);
  }

  @Override
  protected ResponseEntity<Object> handleNoResourceFoundException(
      NoResourceFoundException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return problemResponse(
        HttpStatus.NOT_FOUND,
        "Resource not found",
        "The requested resource was not found.",
        PlatformErrorCode.NOT_FOUND,
        null);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> handleUnexpectedException(Exception ignored) {
    return internalError();
  }

  @ExceptionHandler(PlatformProblemException.class)
  public ResponseEntity<Object> handlePlatformProblem(PlatformProblemException exception) {
    return problemResponse(
        exception.status(),
        titleFor(exception.code()),
        exception.getMessage(),
        exception.code(),
        null);
  }

  @Override
  protected ResponseEntity<Object> createResponseEntity(
      Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    PlatformErrorCode code = errorCodeFor(status);
    ResponseEntity<Object> problem =
        problemResponse(status, titleFor(code), detailFor(code), code, null);
    HttpHeaders safeHeaders = new HttpHeaders();
    safeHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    safeHeaders.set(CorrelationIdFilter.HEADER_NAME, correlationId());
    if (!headers.getAllow().isEmpty()) {
      safeHeaders.setAllow(headers.getAllow());
    }
    if (!headers.getAccept().isEmpty()) {
      safeHeaders.setAccept(headers.getAccept());
    }
    return new ResponseEntity<>(problem.getBody(), safeHeaders, status);
  }

  private ResponseEntity<Object> internalError() {
    return problemResponse(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Internal server error",
        "An unexpected error occurred.",
        PlatformErrorCode.INTERNAL_ERROR,
        null);
  }

  private ResponseEntity<Object> problemResponse(
      HttpStatusCode status,
      String title,
      String detail,
      PlatformErrorCode code,
      List<ValidationIssue> validationIssues) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    problem.setProperty("code", code.name());
    problem.setProperty("correlationId", correlationId());
    if (validationIssues != null && !validationIssues.isEmpty()) {
      problem.setProperty("validationErrors", validationIssues);
    }
    return ResponseEntity.status(status)
        .header(CorrelationIdFilter.HEADER_NAME, correlationId())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  private static ValidationIssue toValidationIssue(FieldError error) {
    String code = error.getCode() == null ? "INVALID" : error.getCode();
    return new ValidationIssue(error.getField(), code);
  }

  private static PlatformErrorCode errorCodeFor(HttpStatusCode status) {
    if (status.is5xxServerError()) {
      return PlatformErrorCode.INTERNAL_ERROR;
    }
    if (status.value() == HttpStatus.NOT_FOUND.value()) {
      return PlatformErrorCode.NOT_FOUND;
    }
    if (status.value() == HttpStatus.METHOD_NOT_ALLOWED.value()) {
      return PlatformErrorCode.METHOD_NOT_ALLOWED;
    }
    if (status.value() == HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()) {
      return PlatformErrorCode.UNSUPPORTED_MEDIA_TYPE;
    }
    if (status.value() == HttpStatus.BAD_REQUEST.value()) {
      return PlatformErrorCode.MALFORMED_REQUEST;
    }
    return PlatformErrorCode.REQUEST_FAILED;
  }

  private static String titleFor(PlatformErrorCode code) {
    return switch (code) {
      case NOT_FOUND -> "Resource not found";
      case METHOD_NOT_ALLOWED -> "Method not allowed";
      case UNSUPPORTED_MEDIA_TYPE -> "Unsupported media type";
      case MALFORMED_REQUEST -> "Malformed request";
      case INTERNAL_ERROR -> "Internal server error";
      case AUTHORIZATION_DENIED -> "Authorization denied";
      case AUTHORIZATION_UNAVAILABLE -> "Authorization temporarily unavailable";
      case AUDIT_UNAVAILABLE -> "Audit temporarily unavailable";
      case EVENT_PUBLICATION_UNAVAILABLE -> "Event publication temporarily unavailable";
      case IDEMPOTENCY_KEY_REUSED -> "Idempotency key conflict";
      case IDEMPOTENCY_IN_PROGRESS -> "Command is still in progress";
      case GRANT_ALREADY_EXISTS -> "Grant already exists";
      case GRANT_NOT_ACTIVE -> "Grant is not active";
      case GRANT_NOT_FOUND -> "Grant not found";
      case INVALID_EXECUTION_SCOPE -> "Execution scope is invalid";
      case BOOTSTRAP_ALREADY_COMPLETED -> "Initial access is already established";
      default -> "Request failed";
    };
  }

  private static String detailFor(PlatformErrorCode code) {
    return switch (code) {
      case NOT_FOUND -> "The requested resource was not found.";
      case METHOD_NOT_ALLOWED -> "The request method is not supported for this resource.";
      case UNSUPPORTED_MEDIA_TYPE -> UNSUPPORTED_MEDIA_TYPE_DETAIL;
      case MALFORMED_REQUEST -> MALFORMED_DETAIL;
      case INTERNAL_ERROR -> "An unexpected error occurred.";
      default -> "The request could not be completed.";
    };
  }

  private static String correlationId() {
    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    return correlationId == null ? "unavailable" : correlationId;
  }

  private record ValidationIssue(String field, String code) {}
}
