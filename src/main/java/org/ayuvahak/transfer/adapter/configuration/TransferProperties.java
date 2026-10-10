package org.ayuvahak.transfer.adapter.configuration;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment policy for Transfer request deadlines; an absent value blocks request creation. */
@ConfigurationProperties(prefix = "ayuvahak.transfer")
public class TransferProperties {

  private Duration requestDeadline;
  private String expiryWorkerClientId = "ayuvahak-transfer-expiry-worker";
  private Duration expiryPollInterval = Duration.ofSeconds(15);
  private int expiryBatchSize = 50;
  private boolean expiryPollingEnabled = true;

  public Duration getRequestDeadline() {
    return requestDeadline;
  }

  public void setRequestDeadline(Duration requestDeadline) {
    this.requestDeadline = requestDeadline;
  }

  public String getExpiryWorkerClientId() {
    return expiryWorkerClientId;
  }

  public void setExpiryWorkerClientId(String expiryWorkerClientId) {
    this.expiryWorkerClientId = expiryWorkerClientId;
  }

  public Duration getExpiryPollInterval() {
    return expiryPollInterval;
  }

  public void setExpiryPollInterval(Duration expiryPollInterval) {
    this.expiryPollInterval = expiryPollInterval;
  }

  public int getExpiryBatchSize() {
    return expiryBatchSize;
  }

  public void setExpiryBatchSize(int expiryBatchSize) {
    this.expiryBatchSize = expiryBatchSize;
  }

  public boolean isExpiryPollingEnabled() {
    return expiryPollingEnabled;
  }

  public void setExpiryPollingEnabled(boolean expiryPollingEnabled) {
    this.expiryPollingEnabled = expiryPollingEnabled;
  }

  @PostConstruct
  void validateExpiryConfiguration() {
    if (expiryWorkerClientId == null
        || !expiryWorkerClientId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("Transfer expiry worker client id is invalid");
    }
    if (expiryPollInterval == null
        || expiryPollInterval.isZero()
        || expiryPollInterval.isNegative()) {
      throw new IllegalArgumentException("Transfer expiry poll interval must be positive");
    }
    if (expiryBatchSize < 1 || expiryBatchSize > 500) {
      throw new IllegalArgumentException("Transfer expiry batch size must be between 1 and 500");
    }
  }
}
