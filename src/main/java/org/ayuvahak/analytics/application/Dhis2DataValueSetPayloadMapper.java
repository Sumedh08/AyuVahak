package org.ayuvahak.analytics.application;

import org.ayuvahak.analytics.domain.AggregateSnapshot;
import org.ayuvahak.analytics.domain.Dhis2MappingVersion;

/** Adapter boundary that turns an eligible local snapshot into one bounded target payload. */
public interface Dhis2DataValueSetPayloadMapper {

  byte[] map(AggregateSnapshot snapshot, Dhis2MappingVersion mapping);
}
