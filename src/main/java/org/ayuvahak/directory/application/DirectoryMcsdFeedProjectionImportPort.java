package org.ayuvahak.directory.application;

import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Narrow Directory-owned command used only for authenticated mCSD feed imports. */
public interface DirectoryMcsdFeedProjectionImportPort {

  void authorizeSourceProjectionFeed(ExecutionContext context, IssuerId sourceNamespace);

  DirectorySourceProjectionImportResult importSourceProjectionFromFeed(
      ExecutionContext context, IssuerId sourceNamespace, DirectorySourceProjection projection);

  DirectorySourceProjectionImportResult importSourceDeletionFromFeed(
      ExecutionContext context, IssuerId sourceNamespace, DirectoryMcsdSourceDeletion deletion);
}
