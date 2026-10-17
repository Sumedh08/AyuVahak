package org.ayuvahak.matching.application;

import java.util.Optional;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/** Supplies only the identity verified for this running local installation. */
@FunctionalInterface
public interface FederationLocalDeploymentIdentity {

  Optional<DeploymentId> current();
}
