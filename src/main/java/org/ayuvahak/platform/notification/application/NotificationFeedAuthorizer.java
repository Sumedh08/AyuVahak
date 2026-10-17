package org.ayuvahak.platform.notification.application;

import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;

/** Revalidates the actor, exact explicit scope, and current notification grant. */
@FunctionalInterface
public interface NotificationFeedAuthorizer {

  ExecutionScope authorize(
      ActorReference actor, RequestedExecutionScope requestedScope, CorrelationId correlationId);
}
