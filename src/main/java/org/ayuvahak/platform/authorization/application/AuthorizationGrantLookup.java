package org.ayuvahak.platform.authorization.application;

import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Reads active grants owned by Identity & Access without exposing grant storage to callers. */
@FunctionalInterface
public interface AuthorizationGrantLookup {

  boolean hasActiveGrant(ActorReference actor, FunctionalAuthority authority, ExecutionScope scope);
}
