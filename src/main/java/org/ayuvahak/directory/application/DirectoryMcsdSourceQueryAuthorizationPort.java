package org.ayuvahak.directory.application;

import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/**
 * Directory-owned authorization boundary checked before querying an external source. The source
 * namespace is a logical identity, not a trusted network destination. A Phase 9 adapter must bind
 * it to administrator-controlled partner configuration before performing network I/O; Phase 8 has
 * no network adapter.
 */
public interface DirectoryMcsdSourceQueryAuthorizationPort {

  void authorize(
      ExecutionContext context, IssuerId sourceNamespace, DirectoryMcsdSourceQueryPurpose purpose);
}
