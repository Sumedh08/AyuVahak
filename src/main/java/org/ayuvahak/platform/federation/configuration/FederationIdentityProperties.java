package org.ayuvahak.platform.federation.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional local bootstrap inputs; the binding is secret and is never included in diagnostics.
 *
 * <p>The installation binding must be provisioned independently of the database and machine image.
 * Restoring or cloning a database alone therefore does not enable federation; a cloned deployment
 * must receive its own deployment identity and binding before peer trust can be activated.
 */
@ConfigurationProperties("ayuvahak.federation.identity")
public final class FederationIdentityProperties {

  private String deploymentId = "";
  private String installationBinding = "";

  public String getDeploymentId() {
    return deploymentId;
  }

  public void setDeploymentId(String deploymentId) {
    this.deploymentId = deploymentId == null ? "" : deploymentId;
  }

  public String getInstallationBinding() {
    return installationBinding;
  }

  public void setInstallationBinding(String installationBinding) {
    this.installationBinding = installationBinding == null ? "" : installationBinding;
  }

  @Override
  public String toString() {
    return "FederationIdentityProperties[deploymentId="
        + deploymentId
        + ", installationBinding=redacted]";
  }
}
