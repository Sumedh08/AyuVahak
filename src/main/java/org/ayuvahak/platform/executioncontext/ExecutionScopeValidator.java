package org.ayuvahak.platform.executioncontext;

import org.ayuvahak.platform.core.ActorReference;

/**
 * Server-side check for a proposed scope. Implementations validate that referenced records and
 * relationships are active and owned by the responsible context. Authorization grants are evaluated
 * separately by Identity & Access. A false result denies context creation; an unavailable validator
 * must fail by throwing rather than allow.
 */
@FunctionalInterface
public interface ExecutionScopeValidator {

  boolean isValidFor(ActorReference verifiedActor, RequestedExecutionScope requestedScope);
}
