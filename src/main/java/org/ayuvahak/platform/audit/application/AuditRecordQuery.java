package org.ayuvahak.platform.audit.application;

import java.util.List;
import java.util.Optional;

/** Audit-owned read boundary for restricted, minimized chronology. */
public interface AuditRecordQuery {

  List<AuditRecordView> findPage(Optional<AuditRecordPosition> before, int limit);
}
