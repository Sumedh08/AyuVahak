package org.ayuvahak.platform.eventing.configuration;

import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded local delivery defaults. They are operational bounds, not workload-derived SLOs. */
@Validated
@ConfigurationProperties("ayuvahak.eventing.outbox")
public class OutboxProperties {

  private Duration leaseDuration = Duration.ofSeconds(60);
  private Duration leaseRenewalInterval = Duration.ofSeconds(20);
  private Duration retryBaseDelay = Duration.ofSeconds(1);
  private Duration retryMaximumDelay = Duration.ofMinutes(5);
  private Duration pollInterval = Duration.ofSeconds(1);
  private int maximumAttempts = 10;
  private int batchSize = 10;
  private boolean dispatcherEnabled = true;

  public Duration getLeaseDuration() {
    return leaseDuration;
  }

  public void setLeaseDuration(Duration leaseDuration) {
    this.leaseDuration = leaseDuration;
  }

  public Duration getLeaseRenewalInterval() {
    return leaseRenewalInterval;
  }

  public void setLeaseRenewalInterval(Duration leaseRenewalInterval) {
    this.leaseRenewalInterval = leaseRenewalInterval;
  }

  public Duration getRetryBaseDelay() {
    return retryBaseDelay;
  }

  public void setRetryBaseDelay(Duration retryBaseDelay) {
    this.retryBaseDelay = retryBaseDelay;
  }

  public Duration getRetryMaximumDelay() {
    return retryMaximumDelay;
  }

  public void setRetryMaximumDelay(Duration retryMaximumDelay) {
    this.retryMaximumDelay = retryMaximumDelay;
  }

  public Duration getPollInterval() {
    return pollInterval;
  }

  public void setPollInterval(Duration pollInterval) {
    this.pollInterval = pollInterval;
  }

  public int getMaximumAttempts() {
    return maximumAttempts;
  }

  public void setMaximumAttempts(int maximumAttempts) {
    this.maximumAttempts = maximumAttempts;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public boolean isDispatcherEnabled() {
    return dispatcherEnabled;
  }

  public void setDispatcherEnabled(boolean dispatcherEnabled) {
    this.dispatcherEnabled = dispatcherEnabled;
  }

  @AssertTrue(message = "outbox timing and batch settings must be positive and within bounds")
  public boolean isValid() {
    return positive(leaseDuration)
        && positive(leaseRenewalInterval)
        && leaseRenewalInterval.compareTo(leaseDuration) < 0
        && positive(retryBaseDelay)
        && positive(retryMaximumDelay)
        && retryMaximumDelay.compareTo(retryBaseDelay) >= 0
        && positive(pollInterval)
        && maximumAttempts >= 1
        && maximumAttempts <= 100
        && batchSize >= 1
        && batchSize <= 1000;
  }

  private boolean positive(Duration duration) {
    return duration != null && !duration.isNegative() && !duration.isZero();
  }
}
