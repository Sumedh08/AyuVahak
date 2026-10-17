package org.ayuvahak.platform.notification.configuration;

import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Explicit finite resource limits for optional server-sent notification hints. */
@Validated
@ConfigurationProperties("ayuvahak.notifications.stream")
public class NotificationStreamProperties {

  private boolean enabled = true;
  private Duration maximumLifetime = Duration.ofMinutes(10);
  private Duration reauthorizationInterval = Duration.ofSeconds(30);
  private Duration pollInterval = Duration.ofMillis(500);
  private Duration heartbeatInterval = Duration.ofSeconds(15);
  private int maximumConnections = 64;
  private int maximumConnectionsPerActor = 3;
  private int maximumQueueCount = 100;
  private int maximumQueueBytes = 65_536;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Duration getMaximumLifetime() {
    return maximumLifetime;
  }

  public void setMaximumLifetime(Duration maximumLifetime) {
    this.maximumLifetime = maximumLifetime;
  }

  public Duration getReauthorizationInterval() {
    return reauthorizationInterval;
  }

  public void setReauthorizationInterval(Duration reauthorizationInterval) {
    this.reauthorizationInterval = reauthorizationInterval;
  }

  public Duration getPollInterval() {
    return pollInterval;
  }

  public void setPollInterval(Duration pollInterval) {
    this.pollInterval = pollInterval;
  }

  public Duration getHeartbeatInterval() {
    return heartbeatInterval;
  }

  public void setHeartbeatInterval(Duration heartbeatInterval) {
    this.heartbeatInterval = heartbeatInterval;
  }

  public int getMaximumConnections() {
    return maximumConnections;
  }

  public void setMaximumConnections(int maximumConnections) {
    this.maximumConnections = maximumConnections;
  }

  public int getMaximumConnectionsPerActor() {
    return maximumConnectionsPerActor;
  }

  public void setMaximumConnectionsPerActor(int maximumConnectionsPerActor) {
    this.maximumConnectionsPerActor = maximumConnectionsPerActor;
  }

  public int getMaximumQueueCount() {
    return maximumQueueCount;
  }

  public void setMaximumQueueCount(int maximumQueueCount) {
    this.maximumQueueCount = maximumQueueCount;
  }

  public int getMaximumQueueBytes() {
    return maximumQueueBytes;
  }

  public void setMaximumQueueBytes(int maximumQueueBytes) {
    this.maximumQueueBytes = maximumQueueBytes;
  }

  @AssertTrue(message = "notification stream limits must be positive and finite")
  public boolean isValid() {
    return positive(maximumLifetime)
        && maximumLifetime.compareTo(Duration.ofHours(1)) <= 0
        && positive(reauthorizationInterval)
        && reauthorizationInterval.compareTo(maximumLifetime) <= 0
        && positive(pollInterval)
        && pollInterval.compareTo(Duration.ofSeconds(30)) <= 0
        && positive(heartbeatInterval)
        && heartbeatInterval.compareTo(maximumLifetime) <= 0
        && maximumConnections >= 1
        && maximumConnections <= 1_000
        && maximumConnectionsPerActor >= 1
        && maximumConnectionsPerActor <= maximumConnections
        && maximumQueueCount >= 1
        && maximumQueueCount <= 1_000
        && maximumQueueBytes >= 512
        && maximumQueueBytes <= 1_048_576;
  }

  private static boolean positive(Duration duration) {
    return duration != null && !duration.isNegative() && !duration.isZero();
  }
}
