package org.ayuvahak.transport.application;

import java.time.Clock;
import java.util.List;
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
import org.ayuvahak.platform.audit.domain.AuditTargetContext;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.transport.domain.TransportAuthorities;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Authorized, Transfer-participant-scoped read operations over Transport-owned facts. */
@Service
public final class TransportViews {

  public static final int DEFAULT_PAGE_SIZE = 50;
  public static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_HISTORY_ROWS = 100;
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";

  private final TransportRequestReadStore store;
  private final TransferParticipantReadPort participants;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final Clock clock;

  public TransportViews(
      TransportRequestReadStore store,
      TransferParticipantReadPort participants,
      AuthorizationService authorization,
      AuditWriter audit,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "Transport read store");
    this.participants = Objects.requireNonNull(participants, "Transfer participant view");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public Page<TransportRequestReadStore.RequestRow> requests(
      ExecutionContext context, InternalId transferId, int page, int size) {
    validatePage(page, size);
    requireFacilityScope(context);
    authorize(context, transferId, "TRANSPORT_REQUEST_LIST_READ");
    participants.requireVisible(context, transferId);
    try {
      List<TransportRequestReadStore.RequestRow> rows =
          store.requestsForTransfer(transferId, offset(page, size), size + 1);
      Page<TransportRequestReadStore.RequestRow> result = pageWindow(rows, page, size);
      recordRead(context, transferId, "TRANSPORT_REQUEST_LIST_READ", "TransportRequestCollection");
      return result;
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  public Detail detail(ExecutionContext context, InternalId transferId, InternalId requestId) {
    requireFacilityScope(context);
    authorize(context, transferId, "TRANSPORT_REQUEST_READ");
    participants.requireVisible(context, transferId);
    try {
      TransportRequestReadStore.RequestRow request =
          store
              .request(requestId)
              .filter(row -> row.transferId().equals(transferId))
              .orElseThrow(TransportViews::notFound);
      Detail detail =
          new Detail(
              request,
              store.statuses(requestId, MAX_HISTORY_ROWS),
              store.assignments(requestId, MAX_HISTORY_ROWS),
              store.etas(requestId, MAX_HISTORY_ROWS),
              store.cancellations(requestId, MAX_HISTORY_ROWS),
              store.destinationChanges(requestId, MAX_HISTORY_ROWS),
              store.providerSubmissions(requestId, MAX_HISTORY_ROWS));
      recordRead(context, requestId, "TRANSPORT_REQUEST_READ", "TransportRequest");
      return detail;
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private void authorize(ExecutionContext context, InternalId transferId, String action) {
    AuthorizationDecision decision =
        authorization.evaluate(context, TransportAuthorities.status().code());
    if (decision.isAllowed()) return;
    boolean unavailable =
        decision.denialReason().orElse(null)
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
    recordAudit(
        context,
        transferId,
        action,
        "TransportRequest",
        AuditOutcome.DENIED,
        AuditAuthorizationOutcome.DENIED,
        unavailable ? "AUTHORIZATION_STORE_UNAVAILABLE" : "AUTHORIZATION_DENIED");
    throw new PlatformProblemException(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        unavailable
            ? "Transport authorization is temporarily unavailable."
            : "The actor is not authorized to view this Transport information.");
  }

  private void recordRead(
      ExecutionContext context, InternalId target, String action, String targetType) {
    recordAudit(
        context,
        target,
        action,
        targetType,
        AuditOutcome.SUCCEEDED,
        AuditAuthorizationOutcome.ALLOWED,
        null);
  }

  private void recordAudit(
      ExecutionContext context,
      InternalId target,
      String action,
      String targetType,
      AuditOutcome outcome,
      AuditAuthorizationOutcome authorizationOutcome,
      String reason) {
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              outcome == AuditOutcome.DENIED
                  ? AuditActionKind.SECURITY_OUTCOME
                  : AuditActionKind.SENSITIVE_READ,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              action,
              targetType,
              Optional.of(target),
              clock.instant(),
              outcome,
              Optional.ofNullable(reason),
              authenticationEvidence(context.authenticationMechanism()),
              authorizationOutcome,
              context.correlationId(),
              Optional.of(
                  new AuditTargetContext(
                      context.scope().kind(),
                      context.scope().organization(),
                      context.scope().facility()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Transport information is unavailable because its required access audit could not be stored.");
    }
  }

  private static void requireFacilityScope(ExecutionContext context) {
    Objects.requireNonNull(context, "execution context");
    ExecutionScope scope = context.scope();
    boolean valid =
        scope.kind() == ExecutionScope.Kind.FACILITY
            && scope.organization().filter(TransportViews::isDirectoryOrganization).isPresent()
            && scope.facility().filter(TransportViews::isDirectoryFacility).isPresent();
    if (!valid) {
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Transport views require a validated Directory Facility scope.");
    }
  }

  private static boolean isDirectoryOrganization(ExternalReference reference) {
    return DIRECTORY_ISSUER.equals(reference.issuer().value())
        && "Organization".equals(reference.resourceType());
  }

  private static boolean isDirectoryFacility(ExternalReference reference) {
    return DIRECTORY_ISSUER.equals(reference.issuer().value())
        && "Facility".equals(reference.resourceType());
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

  private static long offset(int page, int size) {
    return (long) page * size;
  }

  private static void validatePage(int page, int size) {
    if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
      throw new PlatformProblemException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSPORT_REQUEST_INVALID,
          "Transport views require a non-negative page and a page size between 1 and 100.");
    }
  }

  private static <T> Page<T> pageWindow(List<T> rows, int page, int size) {
    boolean hasMore = rows.size() > size;
    List<T> items = hasMore ? rows.subList(0, size) : rows;
    return new Page<>(items, page, size, hasMore);
  }

  private static PlatformProblemException notFound() {
    return new PlatformProblemException(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.NOT_FOUND,
        "Transport request was not found for the visible Transfer.");
  }

  private static PlatformProblemException unavailable() {
    return new PlatformProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSPORT_UNAVAILABLE,
        "Transport information could not be read safely.");
  }

  public record Page<T>(List<T> items, int page, int size, boolean hasMore) {
    public Page {
      items = List.copyOf(items);
    }
  }

  public record Detail(
      TransportRequestReadStore.RequestRow request,
      List<TransportRequestReadStore.StatusRow> statuses,
      List<TransportRequestReadStore.AssignmentRow> assignments,
      List<TransportRequestReadStore.EtaRow> etas,
      List<TransportRequestReadStore.CancellationRow> cancellations,
      List<TransportRequestReadStore.DestinationChangeRow> destinationChanges,
      List<TransportRequestReadStore.ProviderSubmissionRow> providerSubmissions) {
    public Detail {
      statuses = List.copyOf(statuses);
      assignments = List.copyOf(assignments);
      etas = List.copyOf(etas);
      cancellations = List.copyOf(cancellations);
      destinationChanges = List.copyOf(destinationChanges);
      providerSubmissions = List.copyOf(providerSubmissions);
    }
  }
}
