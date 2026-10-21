package org.ayuvahak.analytics.application;

import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;

/** Analytics-owned, scope-bounded read model for aggregate snapshots. */
public interface AnalyticsSnapshotReadStore {

  List<AnalyticsSnapshotSummary> findRecent(
      ExternalReference organization, Optional<ExternalReference> facility, int offset, int limit);
}
