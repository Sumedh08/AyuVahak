package org.ayuvahak.platform.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class RetentionReapplicationHealthIndicatorTest {

  @Test
  void configuredRestoreIsNotReadyUntilEveryOwnerReportsCompletion() {
    UUID restoreId = UUID.randomUUID();
    RetentionReapplicationProperties properties = new RetentionReapplicationProperties();
    properties.setRequiredRestoreId(restoreId.toString());
    var platform = status("platform-eventing", restoreId, true);
    var referral = status("referral", restoreId, false);

    var indicator =
        new RetentionReapplicationHealthIndicator(properties, List.of(platform, referral));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    var complete =
        new RetentionReapplicationHealthIndicator(
            properties, List.of(platform, status("referral", restoreId, true)));
    assertThat(complete.health().getStatus()).isEqualTo(Status.UP);
    var missingOwner = new RetentionReapplicationHealthIndicator(properties, List.of(platform));
    assertThat(missingOwner.health().getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void invalidRestoreIdentifierFailsClosedAndNoConfiguredRestoreIsNeutral() {
    RetentionReapplicationProperties properties = new RetentionReapplicationProperties();
    var neutral = new RetentionReapplicationHealthIndicator(properties, List.of());
    assertThat(neutral.health().getStatus()).isEqualTo(Status.UP);

    properties.setRequiredRestoreId("not-a-uuid");
    var invalid = new RetentionReapplicationHealthIndicator(properties, List.of());
    assertThat(invalid.health().getStatus()).isEqualTo(Status.DOWN);
  }

  private static OwnerRetentionReapplicationStatus status(
      String owner, UUID expectedRestoreId, boolean complete) {
    return new OwnerRetentionReapplicationStatus() {
      @Override
      public String ownerCode() {
        return owner;
      }

      @Override
      public boolean hasCompleted(UUID restoreId) {
        return complete && expectedRestoreId.equals(restoreId);
      }
    };
  }
}
