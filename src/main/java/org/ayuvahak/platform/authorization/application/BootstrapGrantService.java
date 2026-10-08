package org.ayuvahak.platform.authorization.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.ayuvahak.platform.authorization.domain.PhaseTwoAuthorities;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.OutboxWriter;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Executes the explicit one-shot local first-grant operation. */
@Service
@Lazy
public class BootstrapGrantService {

  private static final String REQUIRED_CONFIRMATION = "CREATE_FIRST_ACCESS_GRANT";

  private final AccessGrantStore store;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final OutboxWriter outboxWriter;
  private final ExecutionContextFactory contextFactory;
  private final AuthenticationProperties authenticationProperties;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final ObjectMapper objectMapper;

  public BootstrapGrantService(
      @Lazy AccessGrantStore store,
      ObjectProvider<AuditWriter> auditWriter,
      OutboxWriter outboxWriter,
      ExecutionContextFactory contextFactory,
      AuthenticationProperties authenticationProperties,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock,
      ObjectMapper objectMapper) {
    this.store = Objects.requireNonNull(store, "store");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.outboxWriter = Objects.requireNonNull(outboxWriter, "outbox writer");
    this.contextFactory = Objects.requireNonNull(contextFactory, "context factory");
    this.authenticationProperties =
        Objects.requireNonNull(authenticationProperties, "authentication properties");
    this.transaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transaction manager"));
    this.clock = Objects.requireNonNull(clock, "clock");
    this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper");
  }

  public InternalId provisionFirstGrant(BootstrapGrantProperties properties, String hostPrincipal) {
    Objects.requireNonNull(properties, "properties");
    require(properties.scope().equals("PLATFORM"), "bootstrap scope must be PLATFORM");
    require(
        properties.confirmation().equals(REQUIRED_CONFIRMATION),
        "bootstrap confirmation phrase did not match");
    require(
        properties.granteeIssuer().equals(authenticationProperties.issuer()),
        "grantee issuer must match the configured trusted issuer");

    ActorKind granteeKind;
    try {
      granteeKind = ActorKind.valueOf(properties.granteeKind());
    } catch (IllegalArgumentException exception) {
      throw invalid("grantee kind must be HUMAN or SERVICE");
    }
    GrantPrincipal grantee =
        new GrantPrincipal(
            granteeKind, new IssuerId(properties.granteeIssuer()), properties.granteeIdentifier());
    if (granteeKind == ActorKind.SERVICE
        && !authenticationProperties.serviceClientIds().contains(grantee.identifier())) {
      throw invalid("service grantee must be in the configured client allowlist");
    }
    ActorReference operator =
        ActorReference.human(
            new IssuerId(properties.operatorIssuer()), properties.operatorReference());
    ExecutionContext context =
        contextFactory.forLocalOperatorBootstrap(operator, CorrelationId.generate());

    return transaction.execute(
        status -> {
          store.lockForBootstrap();
          if (store.anyGrantOrBootstrapMarkerExists()) {
            throw new PlatformProblemException(
                HttpStatus.CONFLICT,
                PlatformErrorCode.BOOTSTRAP_ALREADY_COMPLETED,
                "Initial access has already been established; use normal grant administration.");
          }
          Instant now = clock.instant();
          InternalId grantId =
              store.insertPlatformGrant(
                  grantee.actorReference(),
                  PhaseTwoAuthorities.ACCESS_GRANT_ADMINISTRATION.code(),
                  now);
          long sequence = store.advanceEventSequence(grantId);
          auditWriter
              .getObject()
              .recordMutation(
                  new AuditEvidence(
                      AuditActionKind.PROTECTED_MUTATION,
                      Optional.of(operator),
                      Optional.of(context.scope()),
                      "BOOTSTRAP_INITIAL_ACCESS",
                      "AccessGrant",
                      Optional.of(grantId),
                      now,
                      AuditOutcome.SUCCEEDED,
                      Optional.of("ACCESS_SETUP"),
                      AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL,
                      AuditAuthorizationOutcome.NOT_EVALUATED,
                      context.correlationId()));
          publishBootstrapEvent(context, grantId, sequence, now);
          store.completeBootstrap(
              grantId,
              properties.operatorIssuer(),
              properties.operatorReference(),
              safePrincipal(hostPrincipal),
              store.currentDatabasePrincipal(),
              now);
          return grantId;
        });
  }

  private void publishBootstrapEvent(
      ExecutionContext context, InternalId grantId, long sequence, Instant now) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("grantId", grantId.toString());
    payload.put("authorityCode", PhaseTwoAuthorities.ACCESS_GRANT_ADMINISTRATION.code());
    outboxWriter.record(
        new EventEnvelope(
            InternalId.generate(),
            EventType.parse("identity-access.grant-created.v1"),
            "identity-access",
            new OccurredAt(now),
            new RecordedAt(now),
            context,
            Optional.of(new AggregatePosition(grantId.toString(), sequence)),
            payload));
  }

  private static String safePrincipal(String value) {
    if (value == null || value.isBlank()) {
      return "unavailable";
    }
    return value.length() <= 256 ? value : value.substring(0, 256);
  }

  private static void require(boolean accepted, String message) {
    if (!accepted) {
      throw invalid(message);
    }
  }

  private static PlatformProblemException invalid(String detail) {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST, PlatformErrorCode.VALIDATION_FAILED, detail);
  }
}
