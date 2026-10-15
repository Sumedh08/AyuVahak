package org.ayuvahak.directory.application;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.IssuerId;

/** Directory-owned persistence for an opaque source change cursor. */
public interface DirectoryMcsdSynchronizationCheckpointPort {

  Optional<String> findMcsdSourceCursor(IssuerId sourceNamespace);

  void saveMcsdSourceCursor(IssuerId sourceNamespace, String cursor, Instant recordedAt);
}
