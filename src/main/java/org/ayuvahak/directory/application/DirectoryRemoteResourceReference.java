package org.ayuvahak.directory.application;

import java.util.Objects;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/** A Directory resource identity namespaced by its authoritative remote deployment. */
public record DirectoryRemoteResourceReference(
    DeploymentId deploymentId, String context, String resourceType, String resourceId) {

  public DirectoryRemoteResourceReference {
    Objects.requireNonNull(deploymentId, "deploymentId");
    if (!"Directory".equals(context)) {
      throw new IllegalArgumentException("remote Directory references must use Directory context");
    }
    if (resourceType == null
        || !resourceType.matches("Organization|Facility|Location|HealthcareService")) {
      throw new IllegalArgumentException("unsupported remote Directory resource type");
    }
    if (resourceId == null || !resourceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("remote resource identifier must be a bounded token");
    }
  }

  @Override
  public String toString() {
    return "DirectoryRemoteResourceReference[deploymentId="
        + deploymentId
        + ", context=Directory, resourceType="
        + resourceType
        + ", resourceId=redacted]";
  }
}
