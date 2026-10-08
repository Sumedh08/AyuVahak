package org.ayuvahak.platform.executioncontext;

/** A safe denial raised when the server cannot validate the actor's proposed execution scope. */
public final class ExecutionScopeDeniedException extends RuntimeException {

  public ExecutionScopeDeniedException() {
    super("execution scope is not valid for this actor");
  }
}
