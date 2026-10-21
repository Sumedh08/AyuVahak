package org.ayuvahak.platform.authorization.application;

import java.util.List;
import org.ayuvahak.platform.core.ActorReference;

/** Reads active authorization grants belonging to exactly one validated actor identity. */
@FunctionalInterface
public interface SubjectCapabilityLookup {

  List<GrantedSubjectCapability> findActiveFor(ActorReference actor);
}
