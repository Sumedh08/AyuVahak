package org.ayuvahak.analytics.adapter.dhis2;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.ayuvahak.analytics.application.Dhis2DataValueSetPayloadMapper;
import org.ayuvahak.analytics.domain.AggregateSnapshot;
import org.ayuvahak.analytics.domain.Dhis2MappingVersion;
import org.ayuvahak.analytics.domain.ReportingPeriod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Builds one aggregate-only DataValueSet payload from a complete immutable local snapshot. */
@Component
public final class Dhis2DataValueSetMapper implements Dhis2DataValueSetPayloadMapper {

  private final ObjectMapper mapper;

  public Dhis2DataValueSetMapper(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "JSON mapper");
  }

  @Override
  public byte[] map(AggregateSnapshot snapshot, Dhis2MappingVersion mapping) {
    Objects.requireNonNull(snapshot, "aggregate snapshot");
    Objects.requireNonNull(mapping, "DHIS2 mapping");
    if (snapshot.value().isEmpty()) {
      throw new IllegalArgumentException("only complete aggregate snapshots can be exported");
    }
    long value = snapshot.value().orElseThrow();
    if (value == 0 && !mapping.zeroIsSignificant()) {
      throw new IllegalArgumentException("target profile does not store a significant zero");
    }
    ReportingPeriod period = snapshot.period();
    var tuple = mapping.tupleFor(period);
    ObjectNode dataValueSet = mapper.createObjectNode();
    mapping.dataSetUid().ifPresent(uid -> dataValueSet.put("dataSet", uid));
    dataValueSet.put("period", tuple.period());
    dataValueSet.put("orgUnit", tuple.orgUnitUid());
    tuple.attributeOptionComboUid().ifPresent(uid -> dataValueSet.put("attributeOptionCombo", uid));
    ArrayNode values = mapper.createArrayNode();
    ObjectNode dataValue = mapper.createObjectNode();
    dataValue.put("dataElement", tuple.dataElementUid());
    tuple.categoryOptionComboUid().ifPresent(uid -> dataValue.put("categoryOptionCombo", uid));
    dataValue.put("value", Long.toString(value));
    values.add(dataValue);
    dataValueSet.set("dataValues", values);
    return mapper.writeValueAsBytes(dataValueSet);
  }

  public String mapToString(AggregateSnapshot snapshot, Dhis2MappingVersion mapping) {
    return new String(map(snapshot, mapping), StandardCharsets.UTF_8);
  }
}
