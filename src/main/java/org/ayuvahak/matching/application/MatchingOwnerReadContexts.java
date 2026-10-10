package org.ayuvahak.matching.application;

import java.util.Objects;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;

/** Creates isolated owner-read contexts using Matching's configured service identity. */
public final class MatchingOwnerReadContexts {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";

  private final ActorReference serviceActor;
  private final ExecutionContextFactory contexts;

  public MatchingOwnerReadContexts(ActorReference serviceActor, ExecutionContextFactory contexts) {
    this.serviceActor = Objects.requireNonNull(serviceActor, "Matching service actor");
    if (serviceActor.kind() != ActorKind.SERVICE) {
      throw new IllegalArgumentException("Matching owner reads require a service actor");
    }
    this.contexts = Objects.requireNonNull(contexts, "execution context factory");
  }

  public ExecutionContext referralRead(ExecutionContext requester) {
    requireHumanOriginRequest(requester);
    return referralRead(
        requester.correlationId(),
        requester.scope().organization().orElseThrow(),
        requester.scope().facility());
  }

  public ExecutionContext referralRead(
      CorrelationId correlationId,
      ExternalReference organization,
      java.util.Optional<ExternalReference> facility) {
    Objects.requireNonNull(correlationId, "correlation identifier");
    requireDirectoryReference(organization, "Organization");
    facility.ifPresent(reference -> requireDirectoryReference(reference, "Facility"));
    return contexts.forBackgroundWork(
        serviceActor,
        facility
            .map(value -> RequestedExecutionScope.facility(organization, value))
            .orElseGet(() -> RequestedExecutionScope.organization(organization)),
        correlationId,
        java.util.Optional.empty());
  }

  public ExecutionContext directoryRead(
      ExecutionContext requester, ExternalReference organization) {
    requireHumanOriginRequest(requester);
    return directoryRead(requester.correlationId(), organization);
  }

  public ExecutionContext directoryRead(
      CorrelationId correlationId, ExternalReference organization) {
    requireDirectoryReference(organization, "Organization");
    return contexts.forBackgroundWork(
        serviceActor,
        RequestedExecutionScope.organization(organization),
        Objects.requireNonNull(correlationId, "correlation identifier"),
        java.util.Optional.empty());
  }

  public ExecutionContext directoryRead(
      ExecutionContext requester, ExternalReference organization, ExternalReference facility) {
    requireHumanOriginRequest(requester);
    return directoryRead(requester.correlationId(), organization, facility);
  }

  public ExecutionContext directoryRead(
      CorrelationId correlationId, ExternalReference organization, ExternalReference facility) {
    requireDirectoryReference(organization, "Organization");
    requireDirectoryReference(facility, "Facility");
    return contexts.forBackgroundWork(
        serviceActor,
        RequestedExecutionScope.facility(organization, facility),
        Objects.requireNonNull(correlationId, "correlation identifier"),
        java.util.Optional.empty());
  }

  public ExecutionContext capacityRead(
      ExecutionContext requester, ExternalReference organization, ExternalReference facility) {
    requireHumanOriginRequest(requester);
    return capacityRead(requester.correlationId(), organization, facility);
  }

  public ExecutionContext capacityRead(
      CorrelationId correlationId, ExternalReference organization, ExternalReference facility) {
    requireDirectoryReference(organization, "Organization");
    requireDirectoryReference(facility, "Facility");
    return contexts.forBackgroundWork(
        serviceActor,
        RequestedExecutionScope.facility(organization, facility),
        Objects.requireNonNull(correlationId, "correlation identifier"),
        java.util.Optional.empty());
  }

  private static void requireHumanOriginRequest(ExecutionContext requester) {
    Objects.requireNonNull(requester, "recommendation requester context");
    if (requester.actor().kind() != ActorKind.HUMAN
        || (requester.scope().kind() != ExecutionScope.Kind.ORGANIZATION
            && requester.scope().kind() != ExecutionScope.Kind.FACILITY)) {
      throw new IllegalArgumentException(
          "Matching owner reads require an origin-scoped human request context");
    }
  }

  private static void requireDirectoryReference(ExternalReference reference, String type) {
    Objects.requireNonNull(reference, type + " reference");
    if (!DIRECTORY_ISSUER.equals(reference.issuer().value())
        || !type.equals(reference.resourceType())) {
      throw new IllegalArgumentException("Matching owner-read scope reference is invalid");
    }
  }
}
