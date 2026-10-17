package org.ayuvahak.directory.application;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.directory.domain.DirectoryAuthorities;
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
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Requires a current local DirectoryRead grant and durable audit before remote discovery. */
@Service
@Lazy
public final class DirectoryFederatedDiscoveryAuthorizationService
    implements DirectoryFederatedDiscoveryAuthorizationPort {

  private static final String ACTION_CODE = "DIRECTORY_FEDERATED_DISCOVERY";

  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final Clock clock;

  public DirectoryFederatedDiscoveryAuthorizationService(
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Qualifier("auditClock") Clock clock) {
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void authorize(
      ExecutionContext context, DeploymentId peerDeployment, ExternalReference remoteScope) {
    Objects.requireNonNull(context, "executionContext");
    Objects.requireNonNull(peerDeployment, "peerDeployment");
    Objects.requireNonNull(remoteScope, "remoteScope");
    if (context.scope().kind() == ExecutionScope.Kind.PLATFORM) {
      auditDenied(context, remoteScope, "SCOPE_REQUIRED");
      throw denied();
    }
    AuthorizationDecision decision =
        authorization.getObject().evaluate(context, DirectoryAuthorities.read().code());
    if (!decision.isAllowed()) {
      auditDenied(
          context,
          remoteScope,
          decision.denialReason().orElse(null)
                  == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE
              ? "GRANT_STORE_UNAVAILABLE"
              : "AUTHORIZATION_DENIED");
      if (decision.denialReason().orElse(null)
          == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE) {
        throw new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
            "Directory authorization is unavailable; the remote query was not sent.");
      }
      throw denied();
    }
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SENSITIVE_READ,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  ACTION_CODE,
                  "FederatedDirectoryScope",
                  Optional.empty(),
                  clock.instant(),
                  AuditOutcome.SUCCEEDED,
                  Optional.of("AUTHORIZED_PEER_READ"),
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.ALLOWED,
                  context.correlationId(),
                  targetContext(remoteScope)));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw auditUnavailable();
    }
  }

  private void auditDenied(ExecutionContext context, ExternalReference remoteScope, String reason) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  ACTION_CODE,
                  "FederatedDirectoryScope",
                  Optional.empty(),
                  clock.instant(),
                  AuditOutcome.DENIED,
                  Optional.of(reason),
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.DENIED,
                  context.correlationId(),
                  targetContext(remoteScope)));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw auditUnavailable();
    }
  }

  private static Optional<AuditTargetContext> targetContext(ExternalReference remoteScope) {
    if (!"Organization".equals(remoteScope.resourceType())) {
      return Optional.empty();
    }
    return Optional.of(
        new AuditTargetContext(
            ExecutionScope.Kind.ORGANIZATION, Optional.of(remoteScope), Optional.empty()));
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

  private static PlatformProblemException denied() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The actor is not authorized to query federated Directory facts.");
  }

  private static PlatformProblemException auditUnavailable() {
    return new PlatformProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.AUDIT_UNAVAILABLE,
        "The peer query was not sent because its required audit record is unavailable.");
  }
}
