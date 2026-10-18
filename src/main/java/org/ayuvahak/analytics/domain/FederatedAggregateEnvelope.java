package org.ayuvahak.analytics.domain;

import java.util.Objects;
import org.ayuvahak.platform.core.ExternalReference;

/** Fixed named wire contract; target is scoped by peer trust and never used as a URL. */
public record FederatedAggregateEnvelope(
    FederatedAggregateSnapshot snapshot, ExternalReference targetOrganization) {

  public FederatedAggregateEnvelope {
    Objects.requireNonNull(snapshot, "aggregate snapshot");
    Objects.requireNonNull(targetOrganization, "target organization");
    if (!"Organization".equals(targetOrganization.resourceType())) {
      throw new IllegalArgumentException("aggregate target must be an organization");
    }
  }

  @Override
  public String toString() {
    return "FederatedAggregateEnvelope[snapshot=redacted, targetOrganization=redacted]";
  }
}
