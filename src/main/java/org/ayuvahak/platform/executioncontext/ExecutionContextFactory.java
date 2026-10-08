package org.ayuvahak.platform.executioncontext;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CausationId;
import org.ayuvahak.platform.core.CorrelationId;

/** Creates explicit contexts at trusted request and worker boundaries after scope validation. */
public final class ExecutionContextFactory {

  private final ExecutionScopeValidator scopeValidator;

  public ExecutionContextFactory(ExecutionScopeValidator scopeValidator) {
    this.scopeValidator = Objects.requireNonNull(scopeValidator, "scopeValidator");
  }

  /** The actor must already have been verified by the authentication boundary. */
  public ExecutionContext forInteractiveRequest(
      ActorReference verifiedActor,
      RequestedExecutionScope requestedScope,
      CorrelationId correlationId) {
    return ExecutionContext.forInteractiveRequest(
        verifiedActor, requestedScope, correlationId, scopeValidator);
  }

  /** The service actor must already have been verified from separately scoped credentials. */
  public ExecutionContext forIntegrationRequest(
      ActorReference verifiedServiceActor,
      RequestedExecutionScope requestedScope,
      CorrelationId correlationId) {
    return ExecutionContext.forIntegrationRequest(
        verifiedServiceActor, requestedScope, correlationId, scopeValidator);
  }

  /**
   * Creates a new worker context from explicit service identity and scope. It never consults a
   * request, thread-local, or previously active context.
   */
  public ExecutionContext forBackgroundWork(
      ActorReference configuredServiceActor,
      RequestedExecutionScope requestedScope,
      CorrelationId correlationId,
      Optional<CausationId> causationId) {
    return ExecutionContext.forBackgroundWork(
        configuredServiceActor, requestedScope, correlationId, causationId, scopeValidator);
  }

  /** Creates the explicit out-of-band context used only by the one-shot local grant bootstrap. */
  public ExecutionContext forLocalOperatorBootstrap(
      ActorReference operator, CorrelationId correlationId) {
    return ExecutionContext.forLocalOperatorBootstrap(operator, correlationId);
  }
}
