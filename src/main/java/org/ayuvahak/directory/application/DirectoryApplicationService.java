package org.ayuvahak.directory.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.ayuvahak.directory.domain.DirectoryAuthorities;
import org.ayuvahak.directory.domain.DirectoryLifecycleStatus;
import org.ayuvahak.directory.domain.DirectoryProvenance;
import org.ayuvahak.directory.domain.DirectorySource;
import org.ayuvahak.directory.domain.Facility;
import org.ayuvahak.directory.domain.Organization;
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
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Use cases for Directory Organization and Facility facts. */
@Service
@Lazy
public class DirectoryApplicationService {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String COMMAND_BUSY_DETAIL =
      "A matching Directory command is still being processed; retry with the same idempotency key.";

  private final DirectoryStore store;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public DirectoryApplicationService(
      @Lazy DirectoryStore store,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter");
    this.transaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public InternalId createOrganization(
      ExecutionContext context,
      String name,
      Optional<InternalId> parentOrganizationId,
      DirectorySource source,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        "DIRECTORY_ORGANIZATION_CREATE",
        "Organization",
        Optional.empty(),
        Optional.empty());
    if (context.scope().kind() != ExecutionScope.Kind.PLATFORM) {
      denyScope(
          context,
          "DIRECTORY_ORGANIZATION_CREATE",
          "Organization",
          Optional.empty(),
          Optional.empty());
      throw scopeMismatch();
    }
    requireSourceType(source, "Organization");
    CommandReceiptKey receiptKey =
        commandKey(context, "organization.create", "organizations", rawIdempotencyKey);
    return executeIdempotent(
        receiptKey,
        fingerprint,
        201,
        () -> {
          parentOrganizationId.ifPresent(
              parentId -> {
                Organization parent =
                    store
                        .lockOrganization(parentId)
                        .orElseThrow(DirectoryApplicationService::notFound);
                if (parent.status() != DirectoryLifecycleStatus.ACTIVE) {
                  throw inactive();
                }
              });
          InternalId id = InternalId.generate();
          Organization organization =
              new Organization(
                  id,
                  normalizedName(name),
                  parentOrganizationId,
                  source,
                  provenance,
                  DirectoryLifecycleStatus.ACTIVE,
                  1);
          store.insertOrganization(organization);
          auditMutation(
              context,
              "DIRECTORY_ORGANIZATION_CREATE",
              "Organization",
              id,
              provenance.reasonCode(),
              Optional.of(organizationReference(id)),
              Optional.empty());
          return id;
        });
  }

  public Organization getOrganization(ExecutionContext context, InternalId id) {
    requireAuthority(
        context,
        DirectoryAuthorities.read().code(),
        "DIRECTORY_ORGANIZATION_READ",
        "Organization",
        Optional.of(id),
        Optional.empty());
    Organization organization =
        store.findOrganization(id).orElseThrow(DirectoryApplicationService::notFound);
    if (!scopeCanReadOrganization(context.scope(), organization.id())) {
      denyScope(
          context,
          "DIRECTORY_ORGANIZATION_READ",
          "Organization",
          Optional.of(id),
          Optional.of(organizationReference(id)));
      throw scopeMismatch();
    }
    auditRead(
        context,
        "DIRECTORY_ORGANIZATION_READ",
        "Organization",
        id,
        Optional.of(organizationReference(id)),
        Optional.empty());
    return organization;
  }

  public void correctOrganization(
      ExecutionContext context,
      InternalId id,
      long expectedVersion,
      String name,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        "DIRECTORY_ORGANIZATION_CORRECT",
        "Organization",
        Optional.of(id),
        Optional.empty());
    if (!scopeCanCurateOrganization(context.scope(), id)) {
      denyScope(
          context,
          "DIRECTORY_ORGANIZATION_CORRECT",
          "Organization",
          Optional.of(id),
          Optional.of(organizationReference(id)));
      throw scopeMismatch();
    }
    CommandReceiptKey receiptKey =
        commandKey(context, "organization.correct", "organizations/" + id, rawIdempotencyKey);
    executeIdempotent(
        receiptKey,
        fingerprint,
        204,
        () -> {
          Organization current =
              store.lockOrganization(id).orElseThrow(DirectoryApplicationService::notFound);
          if (current.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw inactive();
          }
          requireExpectedVersion(current.version(), expectedVersion);
          Organization corrected = current.correct(normalizedName(name), provenance);
          store.appendOrganizationRevision(current, provenance.reasonCode());
          if (!store.updateOrganization(corrected, expectedVersion, clock.instant())) {
            throw versionConflict();
          }
          auditMutation(
              context,
              "DIRECTORY_ORGANIZATION_CORRECT",
              "Organization",
              id,
              provenance.reasonCode(),
              Optional.of(organizationReference(id)),
              Optional.empty());
          return id;
        });
  }

  public InternalId createFacility(
      ExecutionContext context,
      InternalId managingOrganizationId,
      String name,
      DirectorySource source,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        "DIRECTORY_FACILITY_CREATE",
        "Facility",
        Optional.empty(),
        Optional.of(organizationReference(managingOrganizationId)));
    if (!scopeCanCreateFacility(context.scope(), managingOrganizationId)) {
      denyScope(
          context,
          "DIRECTORY_FACILITY_CREATE",
          "Facility",
          Optional.empty(),
          Optional.of(organizationReference(managingOrganizationId)));
      throw scopeMismatch();
    }
    requireSourceType(source, "Facility");
    CommandReceiptKey receiptKey =
        commandKey(context, "facility.create", "facilities", rawIdempotencyKey);
    return executeIdempotent(
        receiptKey,
        fingerprint,
        201,
        () -> {
          Organization organization =
              store
                  .lockOrganization(managingOrganizationId)
                  .orElseThrow(DirectoryApplicationService::notFound);
          if (organization.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw inactive();
          }
          InternalId id = InternalId.generate();
          Facility facility =
              new Facility(
                  id,
                  managingOrganizationId,
                  normalizedName(name),
                  source,
                  provenance,
                  DirectoryLifecycleStatus.ACTIVE,
                  1);
          store.insertFacility(facility);
          auditMutation(
              context,
              "DIRECTORY_FACILITY_CREATE",
              "Facility",
              id,
              provenance.reasonCode(),
              Optional.of(organizationReference(managingOrganizationId)),
              Optional.of(facilityReference(id)));
          return id;
        });
  }

  public Facility getFacility(ExecutionContext context, InternalId id) {
    requireAuthority(
        context,
        DirectoryAuthorities.read().code(),
        "DIRECTORY_FACILITY_READ",
        "Facility",
        Optional.of(id),
        Optional.empty());
    Facility facility = store.findFacility(id).orElseThrow(DirectoryApplicationService::notFound);
    if (!scopeCanReadFacility(context.scope(), facility)) {
      denyScope(
          context,
          "DIRECTORY_FACILITY_READ",
          "Facility",
          Optional.of(id),
          Optional.of(organizationReference(facility.managingOrganizationId())),
          Optional.of(facilityReference(id)));
      throw scopeMismatch();
    }
    auditRead(
        context,
        "DIRECTORY_FACILITY_READ",
        "Facility",
        id,
        Optional.of(organizationReference(facility.managingOrganizationId())),
        Optional.of(facilityReference(id)));
    return facility;
  }

  public void correctFacility(
      ExecutionContext context,
      InternalId id,
      long expectedVersion,
      String name,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        "DIRECTORY_FACILITY_CORRECT",
        "Facility",
        Optional.of(id),
        Optional.empty());
    if (!scopeCanCurateFacility(context.scope(), id)) {
      denyScope(
          context, "DIRECTORY_FACILITY_CORRECT", "Facility", Optional.of(id), Optional.empty());
      throw scopeMismatch();
    }
    CommandReceiptKey receiptKey =
        commandKey(context, "facility.correct", "facilities/" + id, rawIdempotencyKey);
    executeIdempotent(
        receiptKey,
        fingerprint,
        204,
        () -> {
          Facility current =
              store.lockFacility(id).orElseThrow(DirectoryApplicationService::notFound);
          if (!scopeCanCurateFacility(context.scope(), id)) {
            denyScope(
                context,
                "DIRECTORY_FACILITY_CORRECT",
                "Facility",
                Optional.of(id),
                Optional.of(organizationReference(current.managingOrganizationId())),
                Optional.of(facilityReference(id)));
            throw scopeMismatch();
          }
          if (current.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw inactive();
          }
          requireExpectedVersion(current.version(), expectedVersion);
          Facility corrected = current.correct(normalizedName(name), provenance);
          store.appendFacilityRevision(current, provenance.reasonCode());
          if (!store.updateFacility(corrected, expectedVersion, clock.instant())) {
            throw versionConflict();
          }
          auditMutation(
              context,
              "DIRECTORY_FACILITY_CORRECT",
              "Facility",
              id,
              provenance.reasonCode(),
              Optional.of(organizationReference(current.managingOrganizationId())),
              Optional.of(facilityReference(id)));
          return id;
        });
  }

  public void deactivateFacility(
      ExecutionContext context,
      InternalId id,
      long expectedVersion,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        "DIRECTORY_FACILITY_DEACTIVATE",
        "Facility",
        Optional.of(id),
        Optional.empty());
    if (!scopeCanCurateFacility(context.scope(), id)) {
      denyScope(
          context, "DIRECTORY_FACILITY_DEACTIVATE", "Facility", Optional.of(id), Optional.empty());
      throw scopeMismatch();
    }
    CommandReceiptKey receiptKey =
        commandKey(context, "facility.deactivate", "facilities/" + id, rawIdempotencyKey);
    executeIdempotent(
        receiptKey,
        fingerprint,
        204,
        () -> {
          Facility current =
              store.lockFacility(id).orElseThrow(DirectoryApplicationService::notFound);
          if (!scopeCanCurateFacility(context.scope(), id)) {
            denyScope(
                context,
                "DIRECTORY_FACILITY_DEACTIVATE",
                "Facility",
                Optional.of(id),
                Optional.of(organizationReference(current.managingOrganizationId())),
                Optional.of(facilityReference(id)));
            throw scopeMismatch();
          }
          if (current.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw inactive();
          }
          requireExpectedVersion(current.version(), expectedVersion);
          Facility deactivated = current.deactivate(provenance);
          store.appendFacilityRevision(current, provenance.reasonCode());
          if (!store.updateFacility(deactivated, expectedVersion, clock.instant())) {
            throw versionConflict();
          }
          auditMutation(
              context,
              "DIRECTORY_FACILITY_DEACTIVATE",
              "Facility",
              id,
              provenance.reasonCode(),
              Optional.of(organizationReference(current.managingOrganizationId())),
              Optional.of(facilityReference(id)));
          return id;
        });
  }

  /** Durable denial evidence for a request whose proposed scope could not be validated. */
  public void recordInvalidExecutionScope(
      ActorReference actor, CorrelationId correlationId, String actionCode) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(actor),
                  Optional.empty(),
                  actionCode,
                  "DirectoryScope",
                  Optional.empty(),
                  clock.instant(),
                  AuditOutcome.DENIED,
                  Optional.of("INVALID_EXECUTION_SCOPE"),
                  authenticationEvidence(actor.kind()),
                  AuditAuthorizationOutcome.DENIED,
                  correlationId));
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The request remains denied; its required audit record is unavailable.");
    }
  }

  private InternalId executeIdempotent(
      CommandReceiptKey key,
      RequestFingerprint fingerprint,
      int responseStatus,
      Supplier<InternalId> command) {
    try {
      return transaction.execute(
          status -> {
            store.configureBoundedReceiptWait();
            DirectoryStore.ReceiptClaim claim =
                store.claimReceipt(key, fingerprint, clock.instant());
            return switch (claim.state()) {
              case KEY_REUSED ->
                  throw problem(
                      HttpStatus.CONFLICT,
                      PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
                      "The idempotency key was already used for a different Directory command.");
              case IN_PROGRESS ->
                  throw problem(
                      HttpStatus.CONFLICT,
                      PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS,
                      COMMAND_BUSY_DETAIL);
              case REPLAY -> claim.result().orElseThrow();
              case OWNER -> {
                InternalId result = command.get();
                store.completeReceipt(key, responseStatus, result, clock.instant());
                yield result;
              }
            };
          });
    } catch (CannotAcquireLockException failure) {
      throw problem(
          HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, COMMAND_BUSY_DETAIL);
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The Directory change could not be committed with its required audit evidence.");
    } catch (DataAccessException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.REQUEST_FAILED,
          "The Directory command could not be completed safely.");
    }
  }

  private void requireAuthority(
      ExecutionContext context,
      String authorityCode,
      String actionCode,
      String targetType,
      Optional<InternalId> targetId,
      Optional<ExternalReference> targetOrganization) {
    AuthorizationDecision decision = authorization.getObject().evaluate(context, authorityCode);
    if (decision.isAllowed()) {
      return;
    }
    recordDenial(
        context,
        actionCode,
        targetType,
        targetId,
        targetOrganization,
        Optional.empty(),
        decision.denialReason().map(Enum::name).orElse("AUTHORIZATION_DENIED"));
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
        "The authenticated principal is not authorized for this Directory operation.");
  }

  private void auditMutation(
      ExecutionContext context,
      String actionCode,
      String targetType,
      InternalId targetId,
      String reasonCode,
      Optional<ExternalReference> organization,
      Optional<ExternalReference> facility) {
    try {
      auditWriter
          .getObject()
          .recordMutation(
              evidence(
                  context,
                  AuditActionKind.PROTECTED_MUTATION,
                  actionCode,
                  targetType,
                  Optional.of(targetId),
                  AuditOutcome.SUCCEEDED,
                  Optional.of(reasonCode),
                  AuditAuthorizationOutcome.ALLOWED,
                  organization,
                  facility));
    } catch (AuditPersistenceUnavailableException failure) {
      throw failure;
    }
  }

  private void auditRead(
      ExecutionContext context,
      String actionCode,
      String targetType,
      InternalId targetId,
      Optional<ExternalReference> organization,
      Optional<ExternalReference> facility) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditActionKind.SENSITIVE_READ,
                  actionCode,
                  targetType,
                  Optional.of(targetId),
                  AuditOutcome.SUCCEEDED,
                  Optional.empty(),
                  AuditAuthorizationOutcome.ALLOWED,
                  organization,
                  facility));
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The requested Directory record is not disclosed because access evidence is unavailable.");
    }
  }

  private void denyScope(
      ExecutionContext context,
      String actionCode,
      String targetType,
      Optional<InternalId> targetId,
      Optional<ExternalReference> organization) {
    denyScope(context, actionCode, targetType, targetId, organization, Optional.empty());
  }

  private void denyScope(
      ExecutionContext context,
      String actionCode,
      String targetType,
      Optional<InternalId> targetId,
      Optional<ExternalReference> organization,
      Optional<ExternalReference> facility) {
    recordDenial(
        context,
        actionCode,
        targetType,
        targetId,
        organization,
        facility,
        "DIRECTORY_SCOPE_MISMATCH");
  }

  private void recordDenial(
      ExecutionContext context,
      String actionCode,
      String targetType,
      Optional<InternalId> targetId,
      Optional<ExternalReference> organization,
      Optional<ExternalReference> facility,
      String reasonCode) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditActionKind.SECURITY_OUTCOME,
                  actionCode,
                  targetType,
                  targetId,
                  AuditOutcome.DENIED,
                  Optional.of(reasonCode),
                  AuditAuthorizationOutcome.DENIED,
                  organization,
                  facility));
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The denial remains in effect; its required audit record is unavailable.");
    }
  }

  private AuditEvidence evidence(
      ExecutionContext context,
      AuditActionKind kind,
      String actionCode,
      String targetType,
      Optional<InternalId> targetId,
      AuditOutcome outcome,
      Optional<String> reasonCode,
      AuditAuthorizationOutcome authorizationOutcome,
      Optional<ExternalReference> organization,
      Optional<ExternalReference> facility) {
    Optional<AuditTargetContext> targetContext =
        organization.isPresent() || facility.isPresent()
            ? Optional.of(
                new AuditTargetContext(
                    facility.isPresent()
                        ? ExecutionScope.Kind.FACILITY
                        : ExecutionScope.Kind.ORGANIZATION,
                    organization,
                    facility))
            : Optional.empty();
    return new AuditEvidence(
        kind,
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        actionCode,
        targetType,
        targetId,
        clock.instant(),
        outcome,
        reasonCode,
        authenticationEvidence(context.authenticationMechanism()),
        authorizationOutcome,
        context.correlationId(),
        targetContext);
  }

  private CommandReceiptKey commandKey(
      ExecutionContext context, String operation, String target, String rawKey) {
    try {
      return CommandReceiptKey.from(context, operation, target, rawKey);
    } catch (IllegalArgumentException failure) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "A valid Idempotency-Key is required for this Directory command.");
    }
  }

  private boolean scopeCanReadOrganization(ExecutionScope scope, InternalId organizationId) {
    return switch (scope.kind()) {
      case PLATFORM -> true;
      case ORGANIZATION ->
          scope.organization().orElseThrow().equals(organizationReference(organizationId));
      case FACILITY -> false;
    };
  }

  private boolean scopeCanCurateOrganization(ExecutionScope scope, InternalId organizationId) {
    return scopeCanReadOrganization(scope, organizationId);
  }

  private boolean scopeCanCreateFacility(ExecutionScope scope, InternalId organizationId) {
    return switch (scope.kind()) {
      case PLATFORM -> true;
      case ORGANIZATION ->
          scope.organization().orElseThrow().equals(organizationReference(organizationId));
      case FACILITY -> false;
    };
  }

  private boolean scopeCanReadFacility(ExecutionScope scope, Facility facility) {
    return switch (scope.kind()) {
      case PLATFORM -> true;
      case ORGANIZATION ->
          scope
              .organization()
              .orElseThrow()
              .equals(organizationReference(facility.managingOrganizationId()));
      case FACILITY ->
          scope
                  .organization()
                  .orElseThrow()
                  .equals(organizationReference(facility.managingOrganizationId()))
              && scope.facility().orElseThrow().equals(facilityReference(facility.id()));
    };
  }

  private boolean scopeCanCurateFacility(ExecutionScope scope, InternalId facilityId) {
    return switch (scope.kind()) {
      case PLATFORM -> true;
      case ORGANIZATION ->
          store
              .findFacility(facilityId)
              .map(
                  facility ->
                      scope
                          .organization()
                          .orElseThrow()
                          .equals(organizationReference(facility.managingOrganizationId())))
              .orElse(false);
      case FACILITY -> scope.facility().orElseThrow().equals(facilityReference(facilityId));
    };
  }

  private static void requireSourceType(DirectorySource source, String expectedType) {
    if (source == null || !expectedType.equals(source.reference().resourceType())) {
      throw validation("The source resource type must match the Directory record type.");
    }
  }

  private static String normalizedName(String name) {
    if (name == null || name.isBlank() || !name.equals(name.strip()) || name.length() > 200) {
      throw validation("The Directory name must be trimmed and between 1 and 200 characters.");
    }
    return name;
  }

  private static void requireExpectedVersion(long actual, long expected) {
    if (expected < 1 || actual != expected) {
      throw versionConflict();
    }
  }

  private static ExternalReference organizationReference(InternalId id) {
    return new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Organization", id.toString());
  }

  private static ExternalReference facilityReference(InternalId id) {
    return new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", id.toString());
  }

  private static DirectoryProvenance provenance(
      String method,
      Instant observedAt,
      String reasonCode,
      ExecutionContext context,
      Instant recordedAt) {
    return new DirectoryProvenance(method, observedAt, context.actor(), recordedAt, reasonCode);
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

  private static AuditAuthenticationEvidence authenticationEvidence(ActorKind kind) {
    return kind == ActorKind.HUMAN
        ? AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN
        : AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
  }

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.DIRECTORY_NOT_FOUND,
        "The Directory record was not found.");
  }

  private static PlatformProblemException inactive() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.DIRECTORY_INACTIVE,
        "The inactive Directory record cannot be used for this operation.");
  }

  private static PlatformProblemException versionConflict() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.DIRECTORY_VERSION_CONFLICT,
        "The Directory record changed; retrieve the current version and retry.");
  }

  private static PlatformProblemException scopeMismatch() {
    return problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.DIRECTORY_SCOPE_MISMATCH,
        "The operation is outside the validated Directory scope.");
  }

  private static PlatformProblemException validation(String detail) {
    return problem(HttpStatus.BAD_REQUEST, PlatformErrorCode.DIRECTORY_VALIDATION_FAILED, detail);
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
