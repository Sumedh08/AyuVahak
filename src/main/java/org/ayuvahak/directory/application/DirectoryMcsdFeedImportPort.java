package org.ayuvahak.directory.application;

import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Directory-owned atomic application boundary for one authenticated source feed message. */
public interface DirectoryMcsdFeedImportPort {

  DirectoryMcsdFeedImportResult importFeed(
      ExecutionContext context,
      IssuerId sourceNamespace,
      String externalMessageId,
      String requestFingerprint,
      java.util.List<DirectoryMcsdFeedEntry> entries);
}
