package org.ayuvahak.platform.notification.application;

import java.util.Objects;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.ExecutionScopeDeniedException;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Checks current local grants after rebuilding the actor's exact requested scope. */
@Component
public final class GrantBackedNotificationFeedAuthorizer implements NotificationFeedAuthorizer {

  public static final String AUTHORITY = "NotificationFeedRead";

  private final ExecutionContextFactory contextFactory;
  private final AuthorizationService authorization;

  public GrantBackedNotificationFeedAuthorizer(
      ExecutionContextFactory contextFactory, AuthorizationService authorization) {
    this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
  }

  @Override
  public ExecutionScope authorize(
      ActorReference actor, RequestedExecutionScope requestedScope, CorrelationId correlationId) {
    if (actor == null) {
      throw new PlatformProblemException(
          HttpStatus.UNAUTHORIZED,
          PlatformErrorCode.AUTHENTICATION_REQUIRED,
          "A verified actor is required.");
    }
    if (!(requestedScope instanceof RequestedExecutionScope.Organization)
        && !(requestedScope instanceof RequestedExecutionScope.Facility)) {
      throw invalidScope();
    }

    ExecutionContext context;
    try {
      context =
          actor.kind() == ActorKind.HUMAN
              ? contextFactory.forInteractiveRequest(actor, requestedScope, correlationId)
              : contextFactory.forIntegrationRequest(actor, requestedScope, correlationId);
    } catch (ExecutionScopeDeniedException | IllegalArgumentException invalid) {
      throw invalidScope();
    }

    AuthorizationDecision decision = authorization.evaluate(context, AUTHORITY);
    if (decision.isAllowed()) {
      return context.scope();
    }
    if (decision.denialReason().orElse(null)
        == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
          "Notification access could not be verified.");
    }
    throw new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The actor is not authorized for this notification scope.");
  }

  private static PlatformProblemException invalidScope() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "A validated organization or facility scope is required.");
  }
}
