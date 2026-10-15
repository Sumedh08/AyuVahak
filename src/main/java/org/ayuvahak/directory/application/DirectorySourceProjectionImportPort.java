package org.ayuvahak.directory.application;

import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Directory-owned application command for reconciling one mapped external source fact. */
public interface DirectorySourceProjectionImportPort {

  DirectorySourceProjectionImportResult importSourceProjection(
      ExecutionContext context, DirectorySourceProjection projection);
}
