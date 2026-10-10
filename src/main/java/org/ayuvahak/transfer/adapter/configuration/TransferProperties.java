package org.ayuvahak.transfer.adapter.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment policy for Transfer request deadlines; an absent value blocks request creation. */
@ConfigurationProperties(prefix = "ayuvahak.transfer")
public class TransferProperties {

  private Duration requestDeadline;

  public Duration getRequestDeadline() {
    return requestDeadline;
  }

  public void setRequestDeadline(Duration requestDeadline) {
    this.requestDeadline = requestDeadline;
  }
}
