package org.ayuvahak.platform.notification.configuration;

import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Finite replay and retention limits for the rebuildable notification projection. */
@Validated
@ConfigurationProperties("ayuvahak.notifications.projection")
public class NotificationProjectionProperties {

  private Duration retention = Duration.ofDays(7);
  private Duration deduplicationRetention = Duration.ofDays(8);
  private int maximumScopes = 10_000;
  private int maximumEntriesPerScope = 10_000;
  private int maximumReplayCount = 100;
  private int maximumReplayBytes = 65_536;

  public Duration getRetention() {
    return retention;
  }

  public void setRetention(Duration retention) {
    this.retention = retention;
  }

  public Duration getDeduplicationRetention() {
    return deduplicationRetention;
  }

  public void setDeduplicationRetention(Duration deduplicationRetention) {
    this.deduplicationRetention = deduplicationRetention;
  }

  public int getMaximumScopes() {
    return maximumScopes;
  }

  public void setMaximumScopes(int maximumScopes) {
    this.maximumScopes = maximumScopes;
  }

  public int getMaximumEntriesPerScope() {
    return maximumEntriesPerScope;
  }

  public void setMaximumEntriesPerScope(int maximumEntriesPerScope) {
    this.maximumEntriesPerScope = maximumEntriesPerScope;
  }

  public int getMaximumReplayCount() {
    return maximumReplayCount;
  }

  public void setMaximumReplayCount(int maximumReplayCount) {
    this.maximumReplayCount = maximumReplayCount;
  }

  public int getMaximumReplayBytes() {
    return maximumReplayBytes;
  }

  public void setMaximumReplayBytes(int maximumReplayBytes) {
    this.maximumReplayBytes = maximumReplayBytes;
  }

  @AssertTrue(message = "notification retention and replay settings must be positive and bounded")
  public boolean isValid() {
    return positive(retention)
        && positive(deduplicationRetention)
        && deduplicationRetention.compareTo(retention) >= 0
        && retention.compareTo(Duration.ofDays(365)) <= 0
        && maximumScopes >= 1
        && maximumScopes <= 1_000_000
        && maximumEntriesPerScope >= 1
        && maximumEntriesPerScope <= 1_000_000
        && maximumReplayCount >= 1
        && maximumReplayCount <= 1_000
        && maximumReplayBytes >= 512
        && maximumReplayBytes <= 1_048_576;
  }

  private static boolean positive(Duration duration) {
    return duration != null && !duration.isNegative() && !duration.isZero();
  }
}
