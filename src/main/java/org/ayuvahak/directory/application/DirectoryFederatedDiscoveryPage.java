package org.ayuvahak.directory.application;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/** Bounded source response; omitted resources have no deletion semantics. */
public record DirectoryFederatedDiscoveryPage(
    DeploymentId sourceDeployment,
    ExternalReference requestedScope,
    String sourcePolicyVersion,
    Instant observedAt,
    Completeness completeness,
    List<DirectoryFederatedFact> facts) {

  public static final int MAX_FACTS = 100;

  public DirectoryFederatedDiscoveryPage {
    Objects.requireNonNull(sourceDeployment, "sourceDeployment");
    Objects.requireNonNull(requestedScope, "requestedScope");
    if (sourcePolicyVersion == null
        || !sourcePolicyVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("source policy version must be a bounded token");
    }
    Objects.requireNonNull(observedAt, "observedAt");
    Objects.requireNonNull(completeness, "completeness");
    facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
    if (facts.size() > MAX_FACTS) {
      throw new IllegalArgumentException("remote Directory page exceeds its resource bound");
    }
    Set<DirectoryRemoteResourceReference> seen = new HashSet<>();
    for (DirectoryFederatedFact fact : facts) {
      if (!fact.reference().deploymentId().equals(sourceDeployment)
          || !fact.sourcePolicyVersion().equals(sourcePolicyVersion)
          || !inScope(fact, requestedScope)
          || !seen.add(fact.reference())) {
        throw new IllegalArgumentException("remote Directory page is invalid or out of scope");
      }
    }
  }

  private static boolean inScope(DirectoryFederatedFact fact, ExternalReference scope) {
    if ("Facility".equals(scope.resourceType())) {
      return "Facility".equals(fact.reference().resourceType())
          && scope.value().equals(fact.reference().resourceId());
    }
    return ("Organization".equals(fact.reference().resourceType())
            && scope.value().equals(fact.reference().resourceId()))
        || fact.organization()
            .map(DirectoryRemoteResourceReference::resourceId)
            .filter(scope.value()::equals)
            .isPresent();
  }

  public enum Completeness {
    COMPLETE,
    PARTIAL
  }
}
