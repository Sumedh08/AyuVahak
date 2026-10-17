package org.ayuvahak.platform.federation.domain;

import java.util.Objects;

/** Stable opaque namespace for one AyuVahak deployment; it is not a health-organization ID. */
public record DeploymentId(String value) {

  public DeploymentId {
    Objects.requireNonNull(value, "value");
    if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
      throw new IllegalArgumentException("deployment ID must be a bounded opaque identifier");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
