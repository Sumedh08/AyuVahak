package org.ayuvahak.platform.executioncontext;

/** A business context validates the active records and relationships for scopes it owns. */
public interface ExecutionScopeOwnerValidator {

  boolean supports(RequestedExecutionScope requestedScope);

  boolean isValid(RequestedExecutionScope requestedScope);
}
