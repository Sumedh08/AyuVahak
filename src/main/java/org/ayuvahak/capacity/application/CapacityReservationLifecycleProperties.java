package org.ayuvahak.capacity.application;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounded polling policy and configured service identity for Capacity reservation lifecycle work.
 */
@ConfigurationProperties(prefix = "ayuvahak.capacity.reservation-lifecycle")
public class CapacityReservationLifecycleProperties {

  private String workerClientId = "ayuvahak-capacity-reservation-lifecycle-worker";
  private Duration pollInterval = Duration.ofSeconds(15);
  private int batchSize = 50;
  private boolean enabled = true;

  public String getWorkerClientId() {
    return workerClientId;
  }

  public void setWorkerClientId(String workerClientId) {
    this.workerClientId = workerClientId;
  }

  public Duration getPollInterval() {
    return pollInterval;
  }

  public void setPollInterval(Duration pollInterval) {
    this.pollInterval = pollInterval;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  @PostConstruct
  void validate() {
    if (workerClientId == null || !workerClientId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("Capacity lifecycle worker client id is invalid");
    }
    if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
      throw new IllegalArgumentException("Capacity lifecycle poll interval must be positive");
    }
    if (batchSize < 1 || batchSize > 500) {
      throw new IllegalArgumentException("Capacity lifecycle batch size must be between 1 and 500");
    }
  }
}
