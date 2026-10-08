package org.ayuvahak.platform.executioncontext;

import org.ayuvahak.platform.core.ActorReference;

/**
 * Server-side check for a proposed scope. Implementations must validate the exact organization and
 * facility references against trusted deployment data and the actor's active grants. A false result
 * denies context creation; an unavailable validator must fail by throwing rather than allow.
 */
@FunctionalInterface
public interface ExecutionScopeValidator {

  boolean isValidFor(ActorReference verifiedActor, RequestedExecutionScope requestedScope);
}
