package org.ayuvahak.platform.retention;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Read-only readiness gate; owner replayers alone apply their own retention manifests. */
@Component("retentionReapplicationHealthIndicator")
public final class RetentionReapplicationHealthIndicator implements HealthIndicator {

  private static final Set<String> REQUIRED_OWNERS = Set.of("platform-eventing", "referral");

  private final RetentionReapplicationProperties properties;
  private final List<OwnerRetentionReapplicationStatus> owners;

  public RetentionReapplicationHealthIndicator(
      RetentionReapplicationProperties properties, List<OwnerRetentionReapplicationStatus> owners) {
    this.properties = Objects.requireNonNull(properties, "reapplication properties");
    this.owners = List.copyOf(Objects.requireNonNull(owners, "owner reapplication statuses"));
  }

  @Override
  public Health health() {
    final UUID restoreId;
    try {
      if (properties.requiredRestoreId().isEmpty()) return Health.up().build();
      restoreId = properties.requiredRestoreId().orElseThrow();
    } catch (IllegalArgumentException invalidRestoreId) {
      return Health.down().build();
    }
    try {
      Map<String, OwnerRetentionReapplicationStatus> byCode =
          owners.stream()
              .collect(
                  Collectors.toMap(OwnerRetentionReapplicationStatus::ownerCode, owner -> owner));
      boolean complete =
          REQUIRED_OWNERS.stream()
              .allMatch(
                  code -> {
                    OwnerRetentionReapplicationStatus owner = byCode.get(code);
                    return owner != null && owner.hasCompleted(restoreId);
                  });
      return complete ? Health.up().build() : Health.down().build();
    } catch (RuntimeException unavailable) {
      return Health.down().build();
    }
  }
}
