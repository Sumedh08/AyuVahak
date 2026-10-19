package org.ayuvahak.platform.retention;

import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Restore identifier supplied by an operator before owner reapplication is required. */
@ConfigurationProperties("ayuvahak.retention.reapplication")
public class RetentionReapplicationProperties {

  private String requiredRestoreId = "";

  public String getRequiredRestoreId() {
    return requiredRestoreId;
  }

  public void setRequiredRestoreId(String requiredRestoreId) {
    this.requiredRestoreId = requiredRestoreId == null ? "" : requiredRestoreId.strip();
  }

  public Optional<UUID> requiredRestoreId() {
    if (requiredRestoreId.isBlank()) return Optional.empty();
    return Optional.of(UUID.fromString(requiredRestoreId));
  }
}
