package org.ayuvahak.analytics.application;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.analytics.domain.Dhis2MappingVersion;
import org.ayuvahak.platform.core.ExternalReference;

/** Analytics-owned storage boundary for immutable DHIS2 mapping versions. */
public interface Dhis2MappingStore {

  PublishResult publish(Dhis2MappingVersion mapping, Instant recordedAt);

  void attachAudit(String mappingKey, int version, java.util.UUID auditRecordId);

  void deactivate(String mappingKey, Instant changedAt);

  void attachLifecycleAudit(String mappingKey, java.util.UUID auditRecordId);

  Optional<Dhis2MappingVersion> activeMapping(
      String indicatorKey, int indicatorVersion, ExternalReference organization, Instant at);

  default Optional<Dhis2MappingVersion> activeMapping(
      String indicatorKey,
      int indicatorVersion,
      ExternalReference organization,
      Optional<ExternalReference> facility,
      Instant at) {
    if (facility.isPresent()) {
      return Optional.empty();
    }
    return activeMapping(indicatorKey, indicatorVersion, organization, at);
  }

  Optional<Dhis2MappingVersion> findVersion(String mappingKey, int version);

  Optional<Integer> findActiveVersion(String mappingKey);

  record PublishResult(Dhis2MappingVersion mapping, boolean created) {}
}
