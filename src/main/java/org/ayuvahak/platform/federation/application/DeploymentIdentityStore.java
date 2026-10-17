package org.ayuvahak.platform.federation.application;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.platform.federation.domain.DeploymentIdentity;

/** Persistence boundary for the one local deployment identity and sanitized startup conflicts. */
public interface DeploymentIdentityStore {

  DeploymentIdentity registerOrVerify(
      DeploymentId deploymentId, String installationBindingDigest, Instant now);

  Optional<DeploymentIdentity> findCurrent();
}
