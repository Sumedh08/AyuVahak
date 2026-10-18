package org.ayuvahak.analytics.application;

import java.util.Optional;
import org.ayuvahak.analytics.domain.AggregateSnapshot;
import org.ayuvahak.analytics.domain.IndicatorDefinitionVersion;
import org.ayuvahak.platform.core.InternalId;

/** Analytics-owned persistence boundary for immutable definitions and aggregate results. */
public interface AnalyticsStore {

  Optional<IndicatorDefinitionVersion> findDefinition(String key, int version);

  IndicatorDefinitionVersion publishDefinition(IndicatorDefinitionVersion definition);

  AggregateSnapshot saveOrReplay(AggregateSnapshot snapshot);

  Optional<AggregateSnapshot> findSnapshot(InternalId computationRequestId);
}
