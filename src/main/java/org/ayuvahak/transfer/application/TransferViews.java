package org.ayuvahak.transfer.application;

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
import org.ayuvahak.transfer.application.TransferViewStore.IncomingRequest;
import org.ayuvahak.transfer.application.TransferViewStore.JourneyRows;
import org.ayuvahak.transfer.application.TransferViewStore.RequestAttemptView;
import org.ayuvahak.transfer.application.TransferViewStore.TransferSummary;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Authorized, minimized read operations over Transfer-owned coordination facts. */
@Service
@Lazy
public final class TransferViews {

  public static final int DEFAULT_PAGE_SIZE = 50;
  public static final int MAX_PAGE_SIZE = 100;

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private final TransferViewStore store;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final Clock clock;

  public TransferViews(
      TransferViewStore store,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "Transfer view store");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public JourneyPage journey(ExecutionContext context, InternalId journeyId, int page, int size) {
    validatePage(page, size);
    requireScope(context, false);
    authorize(context, "TRANSFER_JOURNEY_READ", journeyId);
    JourneyRows rows =
        store
            .journey(journeyId, context.scope(), offset(page, size), size + 1)
            .orElseThrow(
                () -> {
                  recordDenial(context, "TRANSFER_JOURNEY_READ", "TransferJourney", journeyId);
                  return notFound();
                });
    Page<RequestAttemptView> attempts = pageWindow(rows.attempts(), page, size);
    recordRead(context, "TRANSFER_JOURNEY_READ", "TransferJourney", journeyId);
    return new JourneyPage(
        rows.journeyId(),
        rows.referralId(),
        rows.originOrganizationId(),
        rows.originFacilityId(),
        rows.createdAt(),
        rows.version(),
        attempts.items(),
        attempts.page(),
        attempts.size(),
        attempts.hasMore());
  }

  public Page<IncomingRequest> pendingIncoming(ExecutionContext context, int page, int size) {
    validatePage(page, size);
    requireScope(context, true);
    authorize(context, "TRANSFER_INCOMING_REQUESTS_READ", scopeTarget(context.scope()));
    InternalId facilityId = InternalId.parse(context.scope().facility().orElseThrow().value());
    List<IncomingRequest> rows = store.pendingIncoming(facilityId, offset(page, size), size + 1);
    Page<IncomingRequest> result = pageWindow(rows, page, size);
    recordRead(context, "TRANSFER_INCOMING_REQUESTS_READ", "TransferRequestCollection", facilityId);
    return result;
  }

  public Page<TransferSummary> transfers(
      ExecutionContext context, Optional<CoordinatedTransfer.Status> status, int page, int size) {
    validatePage(page, size);
    requireScope(context, false);
    authorize(context, "TRANSFER_LIST_READ", scopeTarget(context.scope()));
    Page<TransferSummary> result =
        pageWindow(
            store.transfers(context.scope(), status, offset(page, size), size + 1), page, size);
    recordRead(context, "TRANSFER_LIST_READ", "TransferCollection", scopeTarget(context.scope()));
    return result;
  }

  public TransferSummary transfer(ExecutionContext context, InternalId transferId) {
    requireScope(context, false);
    authorize(context, "TRANSFER_READ", transferId);
    TransferSummary transfer =
        store
            .transfer(transferId, context.scope())
            .orElseThrow(
                () -> {
                  recordDenial(context, "TRANSFER_READ", "CoordinatedTransfer", transferId);
                  return notFound();
                });
    recordRead(context, "TRANSFER_READ", "CoordinatedTransfer", transferId);
    return transfer;
  }

  private void authorize(ExecutionContext context, String action, InternalId target) {
    AuthorizationDecision decision =
        authorization.getObject().evaluate(context, TransferAuthorities.view().code());
    if (decision.isAllowed()) {
      return;
    }
    boolean unavailable =
        decision.denialReason().orElse(null)
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
    recordDenial(
        context,
        action,
        actionTargetType(action),
        target,
        unavailable ? "AUTHORIZATION_UNAVAILABLE" : "AUTHORIZATION_DENIED");
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        unavailable
            ? "Transfer authorization is temporarily unavailable."
            : "The actor is not authorized to view this Transfer information.");
  }

  private void requireScope(ExecutionContext context, boolean facilityOnly) {
    Objects.requireNonNull(context, "execution context");
    ExecutionScope scope = context.scope();
    boolean valid =
        (scope.kind() == ExecutionScope.Kind.ORGANIZATION
                && scope.organization().filter(TransferViews::isDirectoryOrganization).isPresent())
            || (scope.kind() == ExecutionScope.Kind.FACILITY
                && scope.organization().filter(TransferViews::isDirectoryOrganization).isPresent()
                && scope.facility().filter(TransferViews::isDirectoryFacility).isPresent());
    if (!valid || (facilityOnly && scope.kind() != ExecutionScope.Kind.FACILITY)) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          facilityOnly
              ? "Incoming requests require a validated Directory Facility scope."
              : "Transfer views require a validated Directory organization or Facility scope.");
    }
  }

  private void recordRead(
      ExecutionContext context, String action, String targetType, InternalId targetId) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditOutcome.SUCCEEDED,
                  AuditAuthorizationOutcome.ALLOWED,
                  action,
                  targetType,
                  Optional.of(targetId),
                  Optional.empty()));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Transfer information is unavailable because its required access audit could not be stored.");
    }
  }

  private void recordDenial(
      ExecutionContext context, String action, String targetType, InternalId targetId) {
    recordDenial(context, action, targetType, targetId, "TRANSFER_SCOPE_MISMATCH");
  }

  private void recordDenial(
      ExecutionContext context,
      String action,
      String targetType,
      InternalId targetId,
      String reason) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditOutcome.DENIED,
                  AuditAuthorizationOutcome.DENIED,
                  action,
                  targetType,
                  Optional.of(targetId),
                  Optional.of(reason)));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The request remains denied; its required access audit is unavailable.");
    }
  }

  private AuditEvidence evidence(
      ExecutionContext context,
      AuditOutcome outcome,
      AuditAuthorizationOutcome authorizationOutcome,
      String action,
      String targetType,
      Optional<InternalId> targetId,
      Optional<String> reason) {
    return new AuditEvidence(
        AuditActionKind.SENSITIVE_READ,
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        action,
        targetType,
        targetId,
        clock.instant(),
        outcome,
        reason,
        authenticationEvidence(context.authenticationMechanism()),
        authorizationOutcome,
        context.correlationId(),
        Optional.of(targetContext(context.scope())));
  }

  private static String actionTargetType(String action) {
    return switch (action) {
      case "TRANSFER_JOURNEY_READ" -> "TransferJourney";
      case "TRANSFER_INCOMING_REQUESTS_READ" -> "TransferRequestCollection";
      case "TRANSFER_LIST_READ" -> "TransferCollection";
      default -> "CoordinatedTransfer";
    };
  }

  private static InternalId scopeTarget(ExecutionScope scope) {
    ExternalReference reference =
        scope.facility().orElseGet(() -> scope.organization().orElseThrow());
    return InternalId.parse(reference.value());
  }

  private static boolean isDirectoryOrganization(ExternalReference reference) {
    return DIRECTORY_ISSUER.equals(reference.issuer().value())
        && "Organization".equals(reference.resourceType());
  }

  private static boolean isDirectoryFacility(ExternalReference reference) {
    return DIRECTORY_ISSUER.equals(reference.issuer().value())
        && "Facility".equals(reference.resourceType());
  }

  private static AuditTargetContext targetContext(ExecutionScope scope) {
    return new AuditTargetContext(scope.kind(), scope.organization(), scope.facility());
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
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSFER_REQUEST_INVALID,
          "Transfer views require a non-negative page and a page size between 1 and 100.");
    }
  }

  /** Adds page metadata to a database-windowed result; the SQL query already applied the offset. */
  private static <T> Page<T> pageWindow(List<T> windowRows, int page, int size) {
    boolean hasMore = windowRows.size() > size;
    List<T> items = hasMore ? windowRows.subList(0, size) : windowRows;
    return new Page<>(items, page, size, hasMore);
  }

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND, PlatformErrorCode.TRANSFER_NOT_FOUND, "Transfer was not found.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  public record Page<T>(List<T> items, int page, int size, boolean hasMore) {
    public Page {
      items = List.copyOf(items);
    }
  }

  public record JourneyPage(
      InternalId journeyId,
      InternalId referralId,
      InternalId originOrganizationId,
      Optional<InternalId> originFacilityId,
      java.time.Instant createdAt,
      long version,
      List<RequestAttemptView> items,
      int page,
      int size,
      boolean hasMore) {
    public JourneyPage {
      items = List.copyOf(items);
    }
  }
}
