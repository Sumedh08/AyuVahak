package org.ayuvahak.platform.executioncontext;

import java.util.Objects;
import org.ayuvahak.platform.core.ExternalReference;

/** A proposed scope supplied to the server boundary; it is untrusted until validated. */
public sealed interface RequestedExecutionScope
    permits RequestedExecutionScope.Platform,
        RequestedExecutionScope.Organization,
        RequestedExecutionScope.Facility {

  static RequestedExecutionScope platform() {
    return new Platform();
  }

  static RequestedExecutionScope organization(ExternalReference organization) {
    return new Organization(organization);
  }

  static RequestedExecutionScope facility(
      ExternalReference organization, ExternalReference facility) {
    return new Facility(organization, facility);
  }

  record Platform() implements RequestedExecutionScope {}

  record Organization(ExternalReference organization) implements RequestedExecutionScope {

    public Organization {
      requireReferenceType(organization, "Organization");
    }
  }

  /** The facility's organization is explicit to prevent scope from being inferred or omitted. */
  record Facility(ExternalReference organization, ExternalReference facility)
      implements RequestedExecutionScope {

    public Facility {
      requireReferenceType(organization, "Organization");
      requireReferenceType(facility, "Facility");
    }
  }

  private static void requireReferenceType(ExternalReference reference, String expectedType) {
    Objects.requireNonNull(reference, expectedType.toLowerCase() + " reference");
    if (!expectedType.equals(reference.resourceType())) {
      throw new IllegalArgumentException(
          "execution scope requires an external reference of type " + expectedType);
    }
  }
}
