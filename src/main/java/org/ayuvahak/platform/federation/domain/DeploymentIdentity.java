package org.ayuvahak.platform.federation.domain;

import java.time.Instant;
import java.util.Objects;

/** Immutable identity recorded for this installation's federation namespace. */
public record DeploymentIdentity(
    DeploymentId deploymentId, int version, Instant createdAt, Instant updatedAt) {

  public DeploymentIdentity {
    Objects.requireNonNull(deploymentId, "deploymentId");
    if (version != 1) {
      throw new IllegalArgumentException("deployment identity version must be one");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
