package org.ayuvahak.platform.executioncontext;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CausationId;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.SourceKind;

/**
 * Immutable context for one operation or unit of work. It carries no request object, trace ID,
 * credential, or ambient authority. Correlation identifies the logical work chain; tracing metadata
 * remains with observability, and causation is a distinct optional reference.
 */
public final class ExecutionContext {

  private final ActorReference actor;
  private final ExecutionScope scope;
  private final CorrelationId correlationId;
  private final Optional<CausationId> causationId;
  private final SourceKind sourceKind;
  private final AuthenticationMechanism authenticationMechanism;

  private ExecutionContext(
      ActorReference actor,
      ExecutionScope scope,
      CorrelationId correlationId,
      Optional<CausationId> causationId,
      SourceKind sourceKind,
      AuthenticationMechanism authenticationMechanism) {
    this.actor = Objects.requireNonNull(actor, "actor");
    this.scope = Objects.requireNonNull(scope, "scope");
    this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
    this.causationId = Objects.requireNonNull(causationId, "causationId");
    this.sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
    this.authenticationMechanism =
        Objects.requireNonNull(authenticationMechanism, "authenticationMechanism");
  }

  static ExecutionContext forInteractiveRequest(
      ActorReference verifiedHumanActor,
      RequestedExecutionScope requestedScope,
      CorrelationId correlationId,
      ExecutionScopeValidator scopeValidator) {
    requireActorKind(
        verifiedHumanActor, ActorKind.HUMAN, "interactive requests require a human actor");
    return create(
        verifiedHumanActor,
        requestedScope,
        correlationId,
        Optional.empty(),
        SourceKind.INTERACTIVE_REQUEST,
        AuthenticationMechanism.HUMAN_ACCESS_TOKEN,
        scopeValidator);
  }

  static ExecutionContext forIntegrationRequest(
      ActorReference verifiedServiceActor,
      RequestedExecutionScope requestedScope,
      CorrelationId correlationId,
      ExecutionScopeValidator scopeValidator) {
    requireActorKind(
        verifiedServiceActor, ActorKind.SERVICE, "integration requests require a service actor");
    return create(
        verifiedServiceActor,
        requestedScope,
        correlationId,
        Optional.empty(),
        SourceKind.INTEGRATION,
        AuthenticationMechanism.SERVICE_CLIENT_CREDENTIALS,
        scopeValidator);
  }

  static ExecutionContext forBackgroundWork(
      ActorReference configuredServiceActor,
      RequestedExecutionScope requestedScope,
      CorrelationId correlationId,
      Optional<CausationId> causationId,
      ExecutionScopeValidator scopeValidator) {
    requireActorKind(
        configuredServiceActor, ActorKind.SERVICE, "background work requires a service actor");
    if (!(requestedScope instanceof RequestedExecutionScope.Organization)
        && !(requestedScope instanceof RequestedExecutionScope.Facility)) {
      throw new ExecutionScopeDeniedException();
    }
    return create(
        configuredServiceActor,
        requestedScope,
        correlationId,
        causationId,
        SourceKind.BACKGROUND_WORK,
        AuthenticationMechanism.CONFIGURED_SERVICE_IDENTITY,
        scopeValidator);
  }

  static ExecutionContext forLocalOperatorBootstrap(
      ActorReference operator, CorrelationId correlationId) {
    requireActorKind(
        operator, ActorKind.HUMAN, "local operator bootstrap requires an operator reference");
    return new ExecutionContext(
        operator,
        ExecutionScope.platform(),
        correlationId,
        Optional.empty(),
        SourceKind.LOCAL_OPERATOR_COMMAND,
        AuthenticationMechanism.LOCAL_OPERATOR_CONTROL);
  }

  private static ExecutionContext create(
      ActorReference actor,
      RequestedExecutionScope requestedScope,
      CorrelationId correlationId,
      Optional<CausationId> causationId,
      SourceKind sourceKind,
      AuthenticationMechanism authenticationMechanism,
      ExecutionScopeValidator scopeValidator) {
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(causationId, "causationId");
    ExecutionScope scope = ExecutionScope.validateFor(actor, requestedScope, scopeValidator);
    return new ExecutionContext(
        actor, scope, correlationId, causationId, sourceKind, authenticationMechanism);
  }

  private static void requireActorKind(
      ActorReference actor, ActorKind expectedKind, String message) {
    if (actor == null || actor.kind() != expectedKind) {
      throw new IllegalArgumentException(message);
    }
  }

  public ActorReference actor() {
    return actor;
  }

  public ExecutionScope scope() {
    return scope;
  }

  public CorrelationId correlationId() {
    return correlationId;
  }

  public Optional<CausationId> causationId() {
    return causationId;
  }

  public SourceKind sourceKind() {
    return sourceKind;
  }

  public AuthenticationMechanism authenticationMechanism() {
    return authenticationMechanism;
  }

  @Override
  public String toString() {
    return "ExecutionContext[actorKind="
        + actor.kind()
        + ", scopeKind="
        + scope.kind()
        + ", sourceKind="
        + sourceKind
        + ", correlationId="
        + correlationId
        + ", references=redacted]";
  }
}
