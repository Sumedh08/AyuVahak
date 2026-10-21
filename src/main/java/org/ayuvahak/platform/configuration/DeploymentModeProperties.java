package org.ayuvahak.platform.configuration;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** User-selected presentation mode; it does not select or alter any business workflow. */
@Validated
@ConfigurationProperties("ayuvahak.deployment")
public class DeploymentModeProperties {

  @NotNull private DeploymentMode mode = DeploymentMode.EMPTY;

  public DeploymentMode getMode() {
    return mode;
  }

  public void setMode(DeploymentMode mode) {
    this.mode = mode;
  }
}
