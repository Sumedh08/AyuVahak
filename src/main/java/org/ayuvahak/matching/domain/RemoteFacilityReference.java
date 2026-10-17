package org.ayuvahak.matching.domain;

import java.util.Objects;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/** Immutable identity for a Facility owned by a remote Directory deployment. */
public record RemoteFacilityReference(
    DeploymentId deploymentId, String context, InternalId resourceId) {

  public RemoteFacilityReference {
    Objects.requireNonNull(deploymentId, "deployment ID");
    if (!"directory".equals(context)) {
      throw new IllegalArgumentException("remote Facility reference context must be directory");
    }
    Objects.requireNonNull(resourceId, "remote resource ID");
  }

  /** Returns the namespaced reference used by existing owner-read contracts. */
  public ExternalReference toExternalReference() {
    return new ExternalReference(
        new IssuerId("urn:ayuvahak:deployment:" + deploymentId.value() + ":" + context),
        "Facility",
        resourceId.toString());
  }
}
