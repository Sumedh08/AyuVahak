package org.ayuvahak.platform.retention;

import java.util.UUID;

/** Read-only owner contract used by the platform readiness gate. */
public interface OwnerRetentionReapplicationStatus {

  String ownerCode();

  boolean hasCompleted(UUID restoreId);
}
