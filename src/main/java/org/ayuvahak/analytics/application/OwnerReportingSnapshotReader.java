package org.ayuvahak.analytics.application;

import org.ayuvahak.analytics.domain.SourceMeasure;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Analytics application port for one bounded owner-produced aggregate measure. */
@FunctionalInterface
public interface OwnerReportingSnapshotReader {

  OwnerMeasureSnapshot snapshot(
      ExecutionContext context, SourceMeasure measure, OwnerReportingRequest request);
}
