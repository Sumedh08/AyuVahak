package org.ayuvahak.directory.application;

import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/**
 * Verifies the local Directory authority and records required disclosure audit before a peer read.
 */
public interface DirectoryFederatedDiscoveryAuthorizationPort {

  void authorize(
      ExecutionContext context, DeploymentId peerDeployment, ExternalReference remoteScope);
}
