package org.ayuvahak.platform.exchange.application;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
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
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.federation.application.DeploymentIdentityRegistrationService;
import org.ayuvahak.platform.federation.domain.DeploymentIdentity;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns authorization, audit, idempotency, and lifecycle commands for platform trust profiles. */
@Service
@Lazy
public final class PartnerTrustAdministration {

  public static final String AUTHORITY = "ExchangeTrustAdministration";
  private static final String TARGET_TYPE = "PartnerTrustProfile";
  private static final String BUSY_DETAIL =
      "A matching command is still being processed; retry with the same idempotency key.";

  private final PartnerTrustProfileStore store;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final DeploymentIdentityRegistrationService deploymentIdentity;

  public PartnerTrustAdministration(
      @Lazy PartnerTrustProfileStore store,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock,
      DeploymentIdentityRegistrationService deploymentIdentity) {
    this.store = Objects.requireNonNull(store, "store");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter");
    this.transaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.clock = Objects.requireNonNull(clock, "clock");
    this.deploymentIdentity = Objects.requireNonNull(deploymentIdentity, "deploymentIdentity");
  }

  public PartnerProfileCommandResult create(
      ExecutionContext context,
      PartnerTrustConfiguration configuration,
      String reasonCode,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(context, "EXCHANGE_PARTNER_CREATE", Optional.empty());
    validateReason(reasonCode);
    validateFederationIdentity(configuration);
    return execute(
        context,
        "exchange.partner.create",
        configuration.partnerKey().value(),
        "EXCHANGE_PARTNER_CREATE",
        reasonCode,
        201,
        idempotencyKey,
        fingerprint,
        () -> store.createInitial(configuration, clock.instant()));
  }

  public PartnerProfileCommandResult createVersion(
      ExecutionContext context,
      PartnerKey partnerKey,
      int expectedCurrentVersion,
      PartnerTrustConfiguration configuration,
      String reasonCode,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(context, "EXCHANGE_PARTNER_VERSION_CREATE", Optional.empty());
    validateReason(reasonCode);
    if (!partnerKey.equals(configuration.partnerKey())) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "The partner key in the path must match the profile configuration.");
    }
    validateFederationIdentity(configuration);
    return execute(
        context,
        "exchange.partner.version-create",
        partnerKey.value() + ":" + expectedCurrentVersion,
        "EXCHANGE_PARTNER_VERSION_CREATE",
        reasonCode,
        201,
        idempotencyKey,
        fingerprint,
        () -> store.createNextVersion(configuration, expectedCurrentVersion, clock.instant()));
  }

  public PartnerProfileCommandResult activate(
      ExecutionContext context,
      PartnerKey partnerKey,
      int version,
      String reasonCode,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(context, "EXCHANGE_PARTNER_ACTIVATE", Optional.empty());
    validateReason(reasonCode);
    validateFederationActivation(partnerKey, version);
    return execute(
        context,
        "exchange.partner.activate",
        partnerKey.value() + ":" + version,
        "EXCHANGE_PARTNER_ACTIVATE",
        reasonCode,
        200,
        idempotencyKey,
        fingerprint,
        () -> store.activate(partnerKey, version, clock.instant()));
  }

  public PartnerProfileCommandResult suspend(
      ExecutionContext context,
      PartnerKey partnerKey,
      String reasonCode,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(context, "EXCHANGE_PARTNER_SUSPEND", Optional.empty());
    validateReason(reasonCode);
    return execute(
        context,
        "exchange.partner.suspend",
        partnerKey.value(),
        "EXCHANGE_PARTNER_SUSPEND",
        reasonCode,
        200,
        idempotencyKey,
        fingerprint,
        () -> store.suspend(partnerKey, clock.instant()));
  }

  public PartnerProfileCommandResult revoke(
      ExecutionContext context,
      PartnerKey partnerKey,
      String reasonCode,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(context, "EXCHANGE_PARTNER_REVOKE", Optional.empty());
    validateReason(reasonCode);
    return execute(
        context,
        "exchange.partner.revoke",
        partnerKey.value(),
        "EXCHANGE_PARTNER_REVOKE",
        reasonCode,
        200,
        idempotencyKey,
        fingerprint,
        () -> store.revoke(partnerKey, clock.instant()));
  }

  public List<PartnerProfileVersion> versions(ExecutionContext context, PartnerKey partnerKey) {
    requireAuthority(context, "EXCHANGE_PARTNER_READ", Optional.empty());
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditActionKind.SENSITIVE_READ,
                  "EXCHANGE_PARTNER_READ",
                  Optional.empty(),
                  AuditOutcome.SUCCEEDED,
                  Optional.empty(),
                  AuditAuthorizationOutcome.ALLOWED));
      return store.versions(partnerKey);
    } catch (AuditPersistenceUnavailableException failure) {
      throw auditUnavailable();
    } catch (PartnerProfileNotFoundException failure) {
      throw notFound();
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  private PartnerProfileCommandResult execute(
      ExecutionContext context,
      String operation,
      String target,
      String actionCode,
      String reasonCode,
      int responseStatus,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint,
      Supplier<PartnerProfileVersion> mutation) {
    CommandReceiptKey key = commandKey(context, operation, target, rawIdempotencyKey);
    try {
      return transaction.execute(
          transactionStatus -> {
            store.configureBoundedReceiptWait();
            PartnerTrustProfileStore.ReceiptClaim claim =
                store.claimReceipt(key, fingerprint, clock.instant());
            if (claim.state() == PartnerTrustProfileStore.ReceiptState.KEY_REUSED) {
              throw problem(
                  HttpStatus.CONFLICT,
                  PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
                  "The idempotency key was already used for a different command.");
            }
            if (claim.state() == PartnerTrustProfileStore.ReceiptState.IN_PROGRESS) {
              throw problem(
                  HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
            }
            if (claim.state() == PartnerTrustProfileStore.ReceiptState.REPLAY) {
              PartnerProfileVersion prior =
                  store
                      .findById(claim.resultVersion().orElseThrow())
                      .orElseThrow(PartnerProfileNotFoundException::new);
              return new PartnerProfileCommandResult(
                  claim.responseStatus().orElseThrow(), prior, true);
            }
            PartnerProfileVersion result = mutation.get();
            auditMutation(context, actionCode, result.id(), reasonCode);
            store.completeReceipt(key, responseStatus, result.id(), clock.instant());
            return new PartnerProfileCommandResult(responseStatus, result, false);
          });
    } catch (CannotAcquireLockException failure) {
      throw problem(HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
    } catch (DuplicateKeyException failure) {
      throw conflict();
    } catch (AuditPersistenceUnavailableException failure) {
      throw auditUnavailable();
    } catch (PartnerProfileNotFoundException failure) {
      throw notFound();
    } catch (PartnerProfileConflictException | PartnerProfileStateConflictException failure) {
      throw conflict();
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  private void requireAuthority(
      ExecutionContext context, String actionCode, Optional<InternalId> targetId) {
    if (context == null
        || context.scope().kind()
            != org.ayuvahak.platform.executioncontext.ExecutionScope.Kind.PLATFORM) {
      recordDenial(context, actionCode, targetId, "INVALID_EXECUTION_SCOPE");
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "An explicit platform execution scope is required.");
    }
    AuthorizationDecision decision = authorization.getObject().evaluate(context, AUTHORITY);
    if (decision.isAllowed()) {
      return;
    }
    recordDenial(context, actionCode, targetId, "AUTHORIZATION_DENIED");
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
        "The authenticated principal is not authorized for partner trust administration.");
  }

  private void validateFederationIdentity(PartnerTrustConfiguration configuration) {
    configuration
        .federationPeer()
        .ifPresent(
            peer -> {
              validateFederationPeerIdentity(peer.deploymentId());
            });
  }

  private void validateFederationActivation(PartnerKey partnerKey, int version) {
    try {
      store.versions(partnerKey).stream()
          .filter(candidate -> candidate.version() == version)
          .findFirst()
          .flatMap(candidate -> candidate.configuration().federationPeer())
          .ifPresent(peer -> validateFederationPeerIdentity(peer.deploymentId()));
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  private void validateFederationPeerIdentity(
      org.ayuvahak.platform.federation.domain.DeploymentId peerDeploymentId) {
    DeploymentIdentity local;
    try {
      local = deploymentIdentity.current().orElseThrow(PartnerTrustAdministration::unavailable);
    } catch (DataAccessException failure) {
      throw unavailable();
    }
    if (local.deploymentId().equals(peerDeploymentId)) {
      throw conflict();
    }
  }

  private void auditMutation(
      ExecutionContext context, String actionCode, InternalId versionId, String reasonCode) {
    auditWriter
        .getObject()
        .recordMutation(
            evidence(
                context,
                AuditActionKind.PROTECTED_MUTATION,
                actionCode,
                Optional.of(versionId),
                AuditOutcome.SUCCEEDED,
                Optional.of(reasonCode),
                AuditAuthorizationOutcome.ALLOWED));
  }

  private void recordDenial(
      ExecutionContext context,
      String actionCode,
      Optional<InternalId> targetId,
      String reasonCode) {
    if (context == null) {
      return;
    }
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditActionKind.SECURITY_OUTCOME,
                  actionCode,
                  targetId,
                  AuditOutcome.DENIED,
                  Optional.of(reasonCode),
                  AuditAuthorizationOutcome.DENIED));
    } catch (AuditPersistenceUnavailableException failure) {
      throw auditUnavailable();
    }
  }

  private AuditEvidence evidence(
      ExecutionContext context,
      AuditActionKind kind,
      String actionCode,
      Optional<InternalId> targetId,
      AuditOutcome outcome,
      Optional<String> reasonCode,
      AuditAuthorizationOutcome authorizationOutcome) {
    return new AuditEvidence(
        kind,
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        actionCode,
        TARGET_TYPE,
        targetId,
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

  private static CommandReceiptKey commandKey(
      ExecutionContext context, String operation, String target, String rawIdempotencyKey) {
    try {
      return CommandReceiptKey.from(context, operation, target, rawIdempotencyKey);
    } catch (IllegalArgumentException failure) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "A valid idempotency key is required.");
    }
  }

  private static void validateReason(String reasonCode) {
    if (!List.of(
            "PARTNER_SETUP",
            "PARTNER_CHANGE",
            "PARTNER_ACTIVATION",
            "PARTNER_SUSPENSION",
            "SECURITY_RESPONSE",
            "POLICY_REVIEW",
            "OTHER")
        .contains(reasonCode)) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "A valid partner administration reason code is required.");
    }
  }

  private static PlatformProblemException conflict() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.EXCHANGE_PARTNER_CONFLICT,
        "The partner profile changed or its requested lifecycle transition is not allowed.");
  }

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.EXCHANGE_PARTNER_NOT_FOUND,
        "The partner trust profile was not found.");
  }

  private static PlatformProblemException auditUnavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.AUDIT_UNAVAILABLE,
        "The partner command was not committed because required audit evidence is unavailable.");
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.EXCHANGE_CONFIGURATION_UNAVAILABLE,
        "Partner trust configuration is temporarily unavailable.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
