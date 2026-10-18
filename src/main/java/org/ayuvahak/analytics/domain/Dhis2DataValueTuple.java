package org.ayuvahak.analytics.domain;

import java.util.Objects;
import java.util.Optional;

/** Complete configured DHIS2 aggregate value identity; external IDs are not AyuVahak identity. */
public record Dhis2DataValueTuple(
    String dataElementUid,
    String period,
    String orgUnitUid,
    Optional<String> categoryOptionComboUid,
    Optional<String> attributeOptionComboUid) {

  public Dhis2DataValueTuple {
    Dhis2MappingVersion.requireMetadataIdentifier(dataElementUid, "data element UID");
    Dhis2MappingVersion.requireMetadataIdentifier(orgUnitUid, "organization unit UID");
    if (period == null || !period.matches("[0-9]{4}(?:[0-9]{2}|Q[1-4]|W[0-9]{2})?")) {
      throw new IllegalArgumentException("DHIS2 period code is invalid");
    }
    categoryOptionComboUid =
        Objects.requireNonNull(categoryOptionComboUid, "category option combo");
    attributeOptionComboUid =
        Objects.requireNonNull(attributeOptionComboUid, "attribute option combo");
    categoryOptionComboUid.ifPresent(
        value -> Dhis2MappingVersion.requireMetadataIdentifier(value, "category option combo UID"));
    attributeOptionComboUid.ifPresent(
        value ->
            Dhis2MappingVersion.requireMetadataIdentifier(value, "attribute option combo UID"));
  }

  public String stableKey() {
    return dataElementUid
        + "|"
        + period
        + "|"
        + orgUnitUid
        + "|"
        + categoryOptionComboUid.orElse("")
        + "|"
        + attributeOptionComboUid.orElse("");
  }

  @Override
  public String toString() {
    return "Dhis2DataValueTuple[metadata=redacted, period=" + period + "]";
  }
}
