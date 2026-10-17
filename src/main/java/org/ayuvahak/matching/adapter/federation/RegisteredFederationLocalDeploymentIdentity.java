package org.ayuvahak.matching.adapter.federation;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.matching.application.FederationLocalDeploymentIdentity;
import org.ayuvahak.platform.federation.application.DeploymentIdentityRegistrationService;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.springframework.stereotype.Component;

/** Adapts the Platform Kernel's verified runtime identity to Matching's narrow port. */
@Component
public final class RegisteredFederationLocalDeploymentIdentity
    implements FederationLocalDeploymentIdentity {

  private final DeploymentIdentityRegistrationService registration;

  public RegisteredFederationLocalDeploymentIdentity(
      DeploymentIdentityRegistrationService registration) {
    this.registration = Objects.requireNonNull(registration, "deployment identity registration");
  }

  @Override
  public Optional<DeploymentId> current() {
    return registration.current().map(identity -> identity.deploymentId());
  }
}
