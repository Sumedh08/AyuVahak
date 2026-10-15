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
import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.adapter.configuration.TransferProperties;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Transfer authorizes a human initiation and delegates creation to the Transport owner. */
@Service
public final class TransferTransportRequestCommands {

  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final TransportRequestCreationPort transport;
  private final ExecutionContextFactory contexts;
  private final AuthenticationProperties authentication;
  private final TransferProperties properties;
  private final Clock clock;

  public TransferTransportRequestCommands(
      AuthorizationService authorization,
      AuditWriter audit,
      TransportRequestCreationPort transport,
      ExecutionContextFactory contexts,
      AuthenticationProperties authentication,
      TransferProperties properties,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.transport = Objects.requireNonNull(transport, "Transport request port");
    this.contexts = Objects.requireNonNull(contexts, "execution context factory");
    this.authentication = Objects.requireNonNull(authentication, "authentication properties");
    this.properties = Objects.requireNonNull(properties, "Transfer properties");
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public TransportRequestCreationPort.Result request(
      ExecutionContext initiatingContext,
      InternalId transferId,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(initiatingContext, "initiating context");
    Objects.requireNonNull(transferId, "Transfer id");
    Objects.requireNonNull(fingerprint, "fingerprint");
    if (initiatingContext.actor().kind() != ActorKind.HUMAN
        || initiatingContext.scope().kind() != ExecutionScope.Kind.FACILITY) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Transport initiation requires an authorized human in origin Facility scope.");
    }
    AuthorizationDecision decision =
        authorization.evaluate(initiatingContext, TransferAuthorities.request().code());
    if (!decision.isAllowed()) {
      boolean unavailable =
          decision.denialReason().orElse(null)
              == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
      recordDenial(initiatingContext, transferId, unavailable);
      throw problem(
          unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
          unavailable
              ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
              : PlatformErrorCode.AUTHORIZATION_DENIED,
          unavailable
              ? "Transfer authorization is temporarily unavailable."
              : "The actor is not authorized to request Transport for this Transfer.");
    }

    var facility = initiatingContext.scope().facility().orElseThrow();
    var organization = initiatingContext.scope().organization().orElseThrow();
    ActorReference serviceActor =
        ActorReference.service(
            new IssuerId(authentication.issuer()), properties.getTransportWorkerClientId());
    ExecutionContext serviceContext =
        contexts.forBackgroundWork(
            serviceActor,
            RequestedExecutionScope.facility(organization, facility),
            initiatingContext.correlationId(),
            initiatingContext.causationId());
    return transport.create(
        serviceContext, transferId, initiatingContext.actor(), idempotencyKey, fingerprint);
  }

  private void recordDenial(ExecutionContext context, InternalId transferId, boolean unavailable) {
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              "TRANSFER_TRANSPORT_REQUEST_DENIED",
              "Transfer",
              Optional.of(transferId),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of(unavailable ? "AUTHORIZATION_STORE_UNAVAILABLE" : "AUTHORIZATION_DENIED"),
              AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN,
              AuditAuthorizationOutcome.DENIED,
              context.correlationId()));
    } catch (AuditPersistenceUnavailableException auditUnavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The request remains denied; its required audit record is unavailable.");
    }
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
