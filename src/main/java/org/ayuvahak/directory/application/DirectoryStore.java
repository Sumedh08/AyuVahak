package org.ayuvahak.directory.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.directory.domain.DirectoryLocation;
import org.ayuvahak.directory.domain.Facility;
import org.ayuvahak.directory.domain.Organization;
import org.ayuvahak.directory.domain.Wgs84Coordinate;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;

/** Directory-owned persistence boundary for Organization and Facility projections. */
public interface DirectoryStore extends DirectoryScopeReferenceLookup {

  void insertOrganization(Organization organization);

  Optional<Organization> findOrganization(InternalId id);

  Optional<Organization> lockOrganization(InternalId id);

  void appendOrganizationRevision(Organization current, String reasonCode);

  boolean updateOrganization(Organization corrected, long expectedVersion, Instant updatedAt);

  void insertFacility(Facility facility);

  Optional<Facility> findFacility(InternalId id);

  Optional<Facility> lockFacility(InternalId id);

  void appendFacilityRevision(Facility current, String reasonCode);

  boolean updateFacility(Facility corrected, long expectedVersion, Instant updatedAt);

  void insertLocation(DirectoryLocation location);

  Optional<DirectoryLocation> findLocation(InternalId id);

  Optional<DirectoryLocation> lockLocation(InternalId id);

  List<DirectoryLocation> listLocations(InternalId facilityId);

  void appendLocationRevision(DirectoryLocation current, String reasonCode);

  boolean updateLocation(DirectoryLocation corrected, long expectedVersion, Instant updatedAt);

  List<LocatedFacilitySearchResult> findNearbyFacilities(
      Optional<InternalId> organizationId,
      Optional<InternalId> facilityId,
      Wgs84Coordinate center,
      double radiusMeters,
      int limit,
      int offset);

  List<UnlocatedFacilitySearchResult> findUnlocatedFacilities(
      Optional<InternalId> organizationId, Optional<InternalId> facilityId, int limit, int offset);

  ReceiptClaim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt);

  void configureBoundedReceiptWait();

  void completeReceipt(
      CommandReceiptKey key, int responseStatus, InternalId resultReference, Instant recordedAt);

  enum ReceiptState {
    OWNER,
    REPLAY,
    KEY_REUSED,
    IN_PROGRESS
  }

  record ReceiptClaim(
      ReceiptState state, Optional<Integer> responseStatus, Optional<InternalId> result) {}
}
