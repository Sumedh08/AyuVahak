package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.audit.application.AuditPersistenceUnavailableException;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Authorized Transfer-owned aggregate snapshots for the Analytics service identity. */
@Service
public final class TransferReportingSnapshots implements TransferReportingSnapshotPort {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";

  private final TransferReportingSnapshotStore store;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final Clock clock;

  public TransferReportingSnapshots(
      TransferReportingSnapshotStore store,
      AuthorizationService authorization,
      AuditWriter audit,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "Transfer reporting snapshot store");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  @Override
  public Snapshot snapshot(ExecutionContext context, SnapshotRequest request) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(request, "snapshot request");
    requireServiceActor(context, request.requestId());
    requireDirectoryScope(context, request.requestId());
    authorize(context, request.requestId());
    if (request.measure() == Measure.UNFULFILLED
        && request.attribution() == Attribution.DESTINATION) {
      recordDenial(context, request.requestId(), "UNSUPPORTED_ATTRIBUTION");
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSFER_REQUEST_INVALID,
          "Transfer unfulfilled reporting supports origin attribution only.");
    }
    recordAllowedRead(context, request.requestId());
    try {
      return store.createOrReplay(context.scope(), request, clock.instant());
    } catch (TransferReportingSnapshotConflictException conflict) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
          "The reporting request identity was already used for a different scoped measure.");
    } catch (DataAccessException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.TRANSFER_UNAVAILABLE,
          "The Transfer reporting source is temporarily unavailable.");
    }
  }

  private void requireServiceActor(ExecutionContext context, InternalId requestId) {
    if (context.actor().kind() == ActorKind.SERVICE) {
      return;
    }
    recordDenial(context, requestId, "SERVICE_IDENTITY_REQUIRED");
    throw problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "Transfer reporting is available only to an authorized reporting service.");
  }

  private void requireDirectoryScope(ExecutionContext context, InternalId requestId) {
    ExecutionScope scope = context.scope();
    boolean validOrganization =
        scope
            .organization()
            .filter(
                reference ->
                    DIRECTORY_ISSUER.equals(reference.issuer().value())
                        && "Organization".equals(reference.resourceType()))
            .isPresent();
    boolean validFacility =
        scope
            .facility()
            .filter(
                reference ->
                    DIRECTORY_ISSUER.equals(reference.issuer().value())
                        && "Facility".equals(reference.resourceType()))
            .isPresent();
    boolean valid =
        (scope.kind() == ExecutionScope.Kind.ORGANIZATION && validOrganization)
            || (scope.kind() == ExecutionScope.Kind.FACILITY && validOrganization && validFacility);
    if (valid) {
      return;
    }
    recordDenial(context, requestId, "INVALID_REPORTING_SCOPE");
    throw problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "Transfer reporting requires a validated Directory organization or Facility scope.");
  }

  private void authorize(ExecutionContext context, InternalId requestId) {
    AuthorizationDecision decision =
        authorization.evaluate(context, TransferAuthorities.reportingSnapshots().code());
    if (decision.isAllowed()) {
      return;
    }
    boolean unavailable =
        decision.denialReason().orElse(null)
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
    recordDenial(
        context,
        requestId,
        unavailable ? "AUTHORIZATION_STORE_UNAVAILABLE" : "AUTHORIZATION_DENIED");
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        unavailable
            ? "Transfer reporting authorization is temporarily unavailable."
            : "The service is not authorized for this Transfer reporting scope.");
  }

  private void recordAllowedRead(ExecutionContext context, InternalId requestId) {
    try {
      audit.recordNonMutating(
          evidence(
              context,
              AuditActionKind.SENSITIVE_READ,
              AuditOutcome.SUCCEEDED,
              AuditAuthorizationOutcome.ALLOWED,
              "TRANSFER_REPORTING_SNAPSHOT_READ",
              Optional.empty(),
              requestId));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Transfer reporting is unavailable because the required access audit could not be stored.");
    }
  }

  private void recordDenial(ExecutionContext context, InternalId requestId, String reason) {
    try {
      audit.recordNonMutating(
          evidence(
              context,
              AuditActionKind.SECURITY_OUTCOME,
              AuditOutcome.DENIED,
              AuditAuthorizationOutcome.DENIED,
              "TRANSFER_REPORTING_SNAPSHOT_DENIED",
              Optional.of(reason),
              requestId));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The reporting request remains denied; its required denial audit is unavailable.");
    }
  }

  private AuditEvidence evidence(
      ExecutionContext context,
      AuditActionKind kind,
      AuditOutcome outcome,
      AuditAuthorizationOutcome authorizationOutcome,
      String action,
      Optional<String> reason,
      InternalId requestId) {
    return new AuditEvidence(
        kind,
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        action,
        "TransferReportingSnapshotRequest",
        Optional.of(requestId),
        clock.instant(),
        outcome,
        reason,
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

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
