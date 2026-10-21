package org.ayuvahak.platform.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional documentation assistance is disabled unless an operator explicitly enables it. */
@ConfigurationProperties("ayuvahak.operator-support")
public class OperatorSupportProperties {

  private boolean enabled;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
