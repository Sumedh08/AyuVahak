package org.ayuvahak.platform.audit.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.authorization.domain.PhaseTwoAuthorities;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Authorizes and audits restricted audit chronology before returning any evidence. */
@Service
public class AuditReview {

  private static final int DEFAULT_PAGE_SIZE = 50;
  private static final int MAX_PAGE_SIZE = 100;

  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final ObjectProvider<AuditRecordQuery> recordQuery;
  private final Clock clock;

  public AuditReview(
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      ObjectProvider<AuditRecordQuery> recordQuery,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.recordQuery = Objects.requireNonNull(recordQuery, "record query");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public AuditRecordPage list(
      ExecutionContext context, Optional<String> cursor, int requestedLimit) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(cursor, "cursor");
    AuthorizationDecision decision =
        authorization.getObject().evaluate(context, PhaseTwoAuthorities.AUDIT_REVIEW.code());
    if (!decision.isAllowed()) {
      recordSecurityOutcome(context, "AUDIT_RECORD_LIST", "AUTHORIZATION_DENIED");
      if (decision.denialReason().orElse(null)
          == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE) {
        throw problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
            "Authorization could not be evaluated safely.");
      }
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.AUTHORIZATION_DENIED,
          "The authenticated principal is not authorized for this operation.");
    }

    int limit = requestedLimit == 0 ? DEFAULT_PAGE_SIZE : requestedLimit;
    if (limit < 1 || limit > MAX_PAGE_SIZE) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "Page size must be between 1 and 100.");
    }
    Optional<AuditRecordPosition> before = cursor.map(AuditReview::decodeCursor);

    recordRead(context);
    List<AuditRecordView> rows = recordQuery.getObject().findPage(before, limit + 1);
    boolean hasMore = rows.size() > limit;
    List<AuditRecordView> items = hasMore ? rows.subList(0, limit) : rows;
    Optional<String> nextCursor =
        hasMore ? Optional.of(encodeCursor(items.getLast())) : Optional.empty();
    return new AuditRecordPage(items, nextCursor);
  }

  public void recordInvalidScope(ActorReference actor, CorrelationId correlationId) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(actor),
                  Optional.empty(),
                  "EXECUTION_SCOPE_REJECTED",
                  "ExecutionScope",
                  Optional.empty(),
                  clock.instant(),
                  AuditOutcome.DENIED,
                  Optional.of("INVALID_SCOPE"),
                  authenticationEvidence(
                      actor.kind() == ActorKind.HUMAN
                          ? AuthenticationMechanism.HUMAN_ACCESS_TOKEN
                          : AuthenticationMechanism.SERVICE_CLIENT_CREDENTIALS),
                  AuditAuthorizationOutcome.NOT_EVALUATED,
                  correlationId));
    } catch (AuditPersistenceUnavailableException failure) {
      throw auditUnavailable();
    }
  }

  private void recordRead(ExecutionContext context) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditActionKind.SENSITIVE_READ,
                  AuditOutcome.SUCCEEDED,
                  AuditAuthorizationOutcome.ALLOWED,
                  Optional.empty()));
    } catch (AuditPersistenceUnavailableException failure) {
      throw auditUnavailable();
    }
  }

  private void recordSecurityOutcome(
      ExecutionContext context, String actionCode, String reasonCode) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  actionCode,
                  "AuditRecord",
                  Optional.empty(),
                  clock.instant(),
                  AuditOutcome.DENIED,
                  Optional.of(reasonCode),
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.DENIED,
                  context.correlationId()));
    } catch (AuditPersistenceUnavailableException failure) {
      throw auditUnavailable();
    }
  }

  private AuditEvidence evidence(
      ExecutionContext context,
      AuditActionKind actionKind,
      AuditOutcome outcome,
      AuditAuthorizationOutcome authorizationOutcome,
      Optional<String> reasonCode) {
    return new AuditEvidence(
        actionKind,
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        "AUDIT_RECORD_LIST",
        "AuditRecord",
        Optional.empty(),
        clock.instant(),
        outcome,
        reasonCode,
        authenticationEvidence(context.authenticationMechanism()),
        authorizationOutcome,
        context.correlationId());
  }

  private static AuditAuthenticationEvidence authenticationEvidence(
      AuthenticationMechanism mechanism) {
    return switch (mechanism) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }

  private static AuditRecordPosition decodeCursor(String value) {
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
      int separator = decoded.indexOf('|');
      if (separator < 1 || separator == decoded.length() - 1) {
        throw new IllegalArgumentException("invalid cursor");
      }
      return new AuditRecordPosition(
          java.time.Instant.parse(decoded.substring(0, separator)),
          UUID.fromString(decoded.substring(separator + 1)));
    } catch (RuntimeException failure) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "The page cursor is invalid.");
    }
  }

  private static String encodeCursor(AuditRecordView record) {
    String position = record.recordedAt() + "|" + record.id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(position.getBytes(StandardCharsets.UTF_8));
  }

  private static PlatformProblemException auditUnavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.AUDIT_UNAVAILABLE,
        "The required access record could not be committed.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
