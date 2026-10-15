package org.ayuvahak.platform.fhir;

import java.time.Duration;

public record FhirPayloadLimits(
    int maxPayloadBytes,
    int maxNestingDepth,
    int maxResources,
    int maxCollectionItems,
    Duration maxProcessingTime) {

  private static final int ABSOLUTE_MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
  private static final int ABSOLUTE_MAX_NESTING_DEPTH = 128;
  private static final int ABSOLUTE_MAX_RESOURCES = 1000;
  private static final int ABSOLUTE_MAX_COLLECTION_ITEMS = 10_000;
  private static final Duration ABSOLUTE_MAX_PROCESSING_TIME = Duration.ofSeconds(10);

  public FhirPayloadLimits(
      int maxPayloadBytes, int maxNestingDepth, int maxResources, Duration maxProcessingTime) {
    this(maxPayloadBytes, maxNestingDepth, maxResources, maxResources, maxProcessingTime);
  }

  public FhirPayloadLimits {
    if (maxPayloadBytes < 1 || maxPayloadBytes > ABSOLUTE_MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException("FHIR payload byte limit is outside the allowed range");
    }
    if (maxNestingDepth < 1 || maxNestingDepth > ABSOLUTE_MAX_NESTING_DEPTH) {
      throw new IllegalArgumentException("FHIR nesting limit is outside the allowed range");
    }
    if (maxResources < 1 || maxResources > ABSOLUTE_MAX_RESOURCES) {
      throw new IllegalArgumentException("FHIR resource limit is outside the allowed range");
    }
    if (maxCollectionItems < 1 || maxCollectionItems > ABSOLUTE_MAX_COLLECTION_ITEMS) {
      throw new IllegalArgumentException("FHIR collection item limit is outside the allowed range");
    }
    if (maxProcessingTime == null
        || maxProcessingTime.isZero()
        || maxProcessingTime.isNegative()
        || maxProcessingTime.compareTo(ABSOLUTE_MAX_PROCESSING_TIME) > 0) {
      throw new IllegalArgumentException("FHIR processing time limit is outside the allowed range");
    }
  }

  public static FhirPayloadLimits conservativeDefaults() {
    return new FhirPayloadLimits(4 * 1024 * 1024, 64, 250, 1000, Duration.ofSeconds(2));
  }
}
