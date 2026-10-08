package org.ayuvahak.directory.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.directory.application.DirectoryScopeLookupUnavailableException;
import org.ayuvahak.directory.application.DirectoryStore;
import org.ayuvahak.directory.application.LocatedFacilitySearchResult;
import org.ayuvahak.directory.application.UnlocatedFacilitySearchResult;
import org.ayuvahak.directory.domain.DirectoryLifecycleStatus;
import org.ayuvahak.directory.domain.DirectoryLocation;
import org.ayuvahak.directory.domain.DirectoryProvenance;
import org.ayuvahak.directory.domain.DirectorySource;
import org.ayuvahak.directory.domain.Facility;
import org.ayuvahak.directory.domain.Organization;
import org.ayuvahak.directory.domain.Wgs84Coordinate;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.platform.idempotency.configuration.CommandIdempotencyProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** JDBC adapter for Directory-owned Organization and Facility facts and command receipts. */
@Repository
@Lazy
public class JdbcDirectoryStore implements DirectoryStore {

  private static final double DISTANCE_PREFILTER_BUFFER_METERS = 0.01;

  private static final String ORGANIZATION_COLUMNS =
      "organization_id, name, parent_organization_id, source_issuer, source_resource_type, "
          + "source_reference, provenance_method, provenance_observed_at, provenance_recorded_at, "
          + "provenance_actor_kind, provenance_actor_issuer, provenance_actor_identifier_type, "
          + "provenance_actor_identifier, provenance_reason_code, lifecycle_status, version";
  private static final String FACILITY_COLUMNS =
      "facility_id, managing_organization_id, name, source_issuer, source_resource_type, "
          + "source_reference, provenance_method, provenance_observed_at, provenance_recorded_at, "
          + "provenance_actor_kind, provenance_actor_issuer, provenance_actor_identifier_type, "
          + "provenance_actor_identifier, provenance_reason_code, lifecycle_status, version";
  private static final String LOCATION_COLUMNS =
      "l.location_id, l.facility_id, l.name, ST_X(l.coordinates::geometry) AS longitude, "
          + "ST_Y(l.coordinates::geometry) AS latitude, l.is_primary, l.source_issuer, "
          + "l.source_resource_type, l.source_reference, l.provenance_method, "
          + "l.provenance_observed_at, l.provenance_recorded_at, l.provenance_actor_kind, "
          + "l.provenance_actor_issuer, l.provenance_actor_identifier_type, "
          + "l.provenance_actor_identifier, l.provenance_reason_code, l.version";
  private static final String FACILITY_SEARCH_COLUMNS =
      "f.facility_id AS f_id, f.managing_organization_id AS f_organization_id, "
          + "f.name AS f_name, f.source_issuer AS f_source_issuer, "
          + "f.source_resource_type AS f_source_resource_type, "
          + "f.source_reference AS f_source_reference, f.provenance_method AS f_provenance_method, "
          + "f.provenance_observed_at AS f_provenance_observed_at, "
          + "f.provenance_recorded_at AS f_provenance_recorded_at, "
          + "f.provenance_actor_kind AS f_provenance_actor_kind, "
          + "f.provenance_actor_issuer AS f_provenance_actor_issuer, "
          + "f.provenance_actor_identifier_type AS f_provenance_actor_identifier_type, "
          + "f.provenance_actor_identifier AS f_provenance_actor_identifier, "
          + "f.provenance_reason_code AS f_provenance_reason_code, "
          + "f.lifecycle_status AS f_lifecycle_status, f.version AS f_version";
  private static final String LOCATION_SEARCH_COLUMNS =
      "l.location_id AS l_id, l.facility_id AS l_facility_id, l.name AS l_name, "
          + "ST_X(l.coordinates::geometry) AS l_longitude, "
          + "ST_Y(l.coordinates::geometry) AS l_latitude, l.is_primary AS l_is_primary, "
          + "l.source_issuer AS l_source_issuer, l.source_resource_type AS l_source_resource_type, "
          + "l.source_reference AS l_source_reference, l.provenance_method AS l_provenance_method, "
          + "l.provenance_observed_at AS l_provenance_observed_at, "
          + "l.provenance_recorded_at AS l_provenance_recorded_at, "
          + "l.provenance_actor_kind AS l_provenance_actor_kind, "
          + "l.provenance_actor_issuer AS l_provenance_actor_issuer, "
          + "l.provenance_actor_identifier_type AS l_provenance_actor_identifier_type, "
          + "l.provenance_actor_identifier AS l_provenance_actor_identifier, "
          + "l.provenance_reason_code AS l_provenance_reason_code, l.version AS l_version";
  private static final RowMapper<Organization> ORGANIZATION_MAPPER =
      (result, rowNumber) ->
          new Organization(
              new InternalId(result.getObject("organization_id", UUID.class)),
              result.getString("name"),
              Optional.ofNullable(result.getObject("parent_organization_id", UUID.class))
                  .map(InternalId::new),
              source(result),
              provenance(result),
              DirectoryLifecycleStatus.valueOf(result.getString("lifecycle_status")),
              result.getLong("version"));
  private static final RowMapper<Facility> FACILITY_MAPPER =
      (result, rowNumber) ->
          new Facility(
              new InternalId(result.getObject("facility_id", UUID.class)),
              new InternalId(result.getObject("managing_organization_id", UUID.class)),
              result.getString("name"),
              source(result),
              provenance(result),
              DirectoryLifecycleStatus.valueOf(result.getString("lifecycle_status")),
              result.getLong("version"));
  private static final RowMapper<DirectoryLocation> LOCATION_MAPPER =
      (result, rowNumber) -> locationFrom(result);

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final CommandIdempotencyProperties idempotencyProperties;

  public JdbcDirectoryStore(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      CommandIdempotencyProperties idempotencyProperties) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.idempotencyProperties =
        Objects.requireNonNull(idempotencyProperties, "idempotencyProperties");
  }

  @Override
  public void insertOrganization(Organization organization) {
    Object[] sourceParameters =
        sourceAndProvenanceParameters(organization.source(), organization.provenance());
    Object[] parameters = new Object[3 + sourceParameters.length + 3];
    parameters[0] = organization.id().value();
    parameters[1] = organization.name();
    parameters[2] = organization.parentOrganizationId().map(InternalId::value).orElse(null);
    System.arraycopy(sourceParameters, 0, parameters, 3, sourceParameters.length);
    parameters[3 + sourceParameters.length] = organization.status().name();
    parameters[4 + sourceParameters.length] =
        Timestamp.from(organization.provenance().recordedAt());
    parameters[5 + sourceParameters.length] =
        Timestamp.from(organization.provenance().recordedAt());
    jdbc.update(
        "INSERT INTO directory.organization (organization_id, name, parent_organization_id, "
            + sourceColumns()
            + ", lifecycle_status, version, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
        parameters);
  }

  @Override
  public Optional<Organization> findOrganization(InternalId id) {
    return findOrganization(id, false);
  }

  @Override
  public Optional<Organization> lockOrganization(InternalId id) {
    return findOrganization(id, true);
  }

  @Override
  public void appendOrganizationRevision(Organization current, String reasonCode) {
    jdbc.update(
        "INSERT INTO directory.organization_revision (revision_id, organization_id, "
            + "superseded_version, snapshot, changed_at, reason_code) "
            + "VALUES (?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP, ?)",
        InternalId.generate().value(),
        current.id().value(),
        current.version(),
        snapshot(current),
        reasonCode);
  }

  @Override
  public boolean updateOrganization(
      Organization corrected, long expectedVersion, Instant updatedAt) {
    return jdbc.update(
            "UPDATE directory.organization SET name = ?, provenance_method = ?, "
                + "provenance_observed_at = ?, provenance_recorded_at = ?, provenance_actor_kind = ?, "
                + "provenance_actor_issuer = ?, provenance_actor_identifier_type = ?, "
                + "provenance_actor_identifier = ?, provenance_reason_code = ?, lifecycle_status = ?, "
                + "version = ?, updated_at = ? WHERE organization_id = ? AND version = ?",
            corrected.name(),
            corrected.provenance().method(),
            Timestamp.from(corrected.provenance().observedAt()),
            Timestamp.from(corrected.provenance().recordedAt()),
            corrected.provenance().recordedBy().kind().name(),
            corrected.provenance().recordedBy().identity().issuer().value(),
            corrected.provenance().recordedBy().identity().resourceType(),
            corrected.provenance().recordedBy().identity().value(),
            corrected.provenance().reasonCode(),
            corrected.status().name(),
            corrected.version(),
            Timestamp.from(updatedAt),
            corrected.id().value(),
            expectedVersion)
        == 1;
  }

  @Override
  public void insertFacility(Facility facility) {
    Object[] sourceParameters =
        sourceAndProvenanceParameters(facility.source(), facility.provenance());
    Object[] parameters = new Object[3 + sourceParameters.length + 2];
    parameters[0] = facility.id().value();
    parameters[1] = facility.managingOrganizationId().value();
    parameters[2] = facility.name();
    System.arraycopy(sourceParameters, 0, parameters, 3, sourceParameters.length);
    parameters[3 + sourceParameters.length] = Timestamp.from(facility.provenance().recordedAt());
    parameters[4 + sourceParameters.length] = Timestamp.from(facility.provenance().recordedAt());
    jdbc.update(
        "INSERT INTO directory.facility (facility_id, managing_organization_id, name, "
            + sourceColumns()
            + ", lifecycle_status, version, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?)",
        parameters);
  }

  @Override
  public Optional<Facility> findFacility(InternalId id) {
    return findFacility(id, false);
  }

  @Override
  public Optional<Facility> lockFacility(InternalId id) {
    return findFacility(id, true);
  }

  @Override
  public void appendFacilityRevision(Facility current, String reasonCode) {
    jdbc.update(
        "INSERT INTO directory.facility_revision (revision_id, facility_id, superseded_version, "
            + "snapshot, changed_at, reason_code) VALUES (?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP, ?)",
        InternalId.generate().value(),
        current.id().value(),
        current.version(),
        snapshot(current),
        reasonCode);
  }

  @Override
  public boolean updateFacility(Facility corrected, long expectedVersion, Instant updatedAt) {
    return jdbc.update(
            "UPDATE directory.facility SET name = ?, provenance_method = ?, "
                + "provenance_observed_at = ?, provenance_recorded_at = ?, provenance_actor_kind = ?, "
                + "provenance_actor_issuer = ?, provenance_actor_identifier_type = ?, "
                + "provenance_actor_identifier = ?, provenance_reason_code = ?, lifecycle_status = ?, "
                + "version = ?, updated_at = ? WHERE facility_id = ? AND version = ?",
            corrected.name(),
            corrected.provenance().method(),
            Timestamp.from(corrected.provenance().observedAt()),
            Timestamp.from(corrected.provenance().recordedAt()),
            corrected.provenance().recordedBy().kind().name(),
            corrected.provenance().recordedBy().identity().issuer().value(),
            corrected.provenance().recordedBy().identity().resourceType(),
            corrected.provenance().recordedBy().identity().value(),
            corrected.provenance().reasonCode(),
            corrected.status().name(),
            corrected.version(),
            Timestamp.from(updatedAt),
            corrected.id().value(),
            expectedVersion)
        == 1;
  }

  @Override
  public void insertLocation(DirectoryLocation location) {
    Object[] sourceParameters =
        sourceAndProvenanceParameters(location.source(), location.provenance());
    Object[] parameters = new Object[6 + sourceParameters.length + 2];
    parameters[0] = location.id().value();
    parameters[1] = location.facilityId().value();
    parameters[2] = location.name();
    parameters[3] = location.coordinate().map(Wgs84Coordinate::longitude).orElse(null);
    parameters[4] = location.coordinate().map(Wgs84Coordinate::latitude).orElse(null);
    parameters[5] = location.primary();
    System.arraycopy(sourceParameters, 0, parameters, 6, sourceParameters.length);
    parameters[6 + sourceParameters.length] = Timestamp.from(location.provenance().recordedAt());
    parameters[7 + sourceParameters.length] = Timestamp.from(location.provenance().recordedAt());
    jdbc.update(
        "INSERT INTO directory.location (location_id, facility_id, name, coordinates, is_primary, "
            + sourceColumns()
            + ", version, created_at, updated_at) VALUES (?, ?, ?, "
            + "ST_SetSRID(ST_MakePoint(?::double precision, ?::double precision), 4326)::geography, "
            + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
        parameters);
  }

  @Override
  public Optional<DirectoryLocation> findLocation(InternalId id) {
    return findLocation(id, false);
  }

  @Override
  public Optional<DirectoryLocation> lockLocation(InternalId id) {
    return findLocation(id, true);
  }

  @Override
  public List<DirectoryLocation> listLocations(InternalId facilityId) {
    return jdbc.query(
        "SELECT "
            + LOCATION_COLUMNS
            + " FROM directory.location l WHERE l.facility_id = ? ORDER BY l.location_id",
        LOCATION_MAPPER,
        facilityId.value());
  }

  @Override
  public void appendLocationRevision(DirectoryLocation current, String reasonCode) {
    jdbc.update(
        "INSERT INTO directory.location_revision (revision_id, location_id, superseded_version, "
            + "snapshot, changed_at, reason_code) VALUES (?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP, ?)",
        InternalId.generate().value(),
        current.id().value(),
        current.version(),
        snapshot(current),
        reasonCode);
  }

  @Override
  public boolean updateLocation(
      DirectoryLocation corrected, long expectedVersion, Instant updatedAt) {
    Object[] sourceParameters =
        sourceAndProvenanceParameters(corrected.source(), corrected.provenance());
    Object[] parameters = new Object[4 + sourceParameters.length + 4];
    parameters[0] = corrected.name();
    parameters[1] = corrected.coordinate().map(Wgs84Coordinate::longitude).orElse(null);
    parameters[2] = corrected.coordinate().map(Wgs84Coordinate::latitude).orElse(null);
    parameters[3] = corrected.primary();
    System.arraycopy(sourceParameters, 0, parameters, 4, sourceParameters.length);
    parameters[4 + sourceParameters.length] = corrected.version();
    parameters[5 + sourceParameters.length] = Timestamp.from(updatedAt);
    parameters[6 + sourceParameters.length] = corrected.id().value();
    parameters[7 + sourceParameters.length] = expectedVersion;
    return jdbc.update(
            "UPDATE directory.location SET name = ?, coordinates = "
                + "ST_SetSRID(ST_MakePoint(?::double precision, ?::double precision), 4326)::geography, "
                + "is_primary = ?, source_issuer = ?, source_resource_type = ?, source_reference = ?, "
                + "provenance_method = ?, provenance_observed_at = ?, provenance_recorded_at = ?, "
                + "provenance_actor_kind = ?, provenance_actor_issuer = ?, "
                + "provenance_actor_identifier_type = ?, provenance_actor_identifier = ?, "
                + "provenance_reason_code = ?, version = ?, updated_at = ? "
                + "WHERE location_id = ? AND version = ?",
            parameters)
        == 1;
  }

  @Override
  public List<LocatedFacilitySearchResult> findNearbyFacilities(
      Optional<InternalId> organizationId,
      Optional<InternalId> facilityId,
      Wgs84Coordinate center,
      double radiusMeters,
      int limit,
      int offset) {
    String point =
        "ST_SetSRID(ST_MakePoint(?::double precision, ?::double precision), 4326)::geography";
    return jdbc.query(
        "SELECT "
            + FACILITY_SEARCH_COLUMNS
            + ", "
            + LOCATION_SEARCH_COLUMNS
            + ", ST_Distance(l.coordinates, "
            + point
            + ") AS distance_meters FROM directory.facility f "
            + "JOIN directory.organization o ON o.organization_id = f.managing_organization_id "
            + "JOIN directory.location l ON l.facility_id = f.facility_id AND l.is_primary "
            + "WHERE f.lifecycle_status = 'ACTIVE' AND o.lifecycle_status = 'ACTIVE' "
            + "AND l.coordinates IS NOT NULL AND ST_DWithin(l.coordinates, "
            + point
            + ", ? + "
            + DISTANCE_PREFILTER_BUFFER_METERS
            + ") AND ST_Distance(l.coordinates, "
            + point
            + ") <= ? AND (?::uuid IS NULL OR f.managing_organization_id = ?) "
            + "AND (?::uuid IS NULL OR f.facility_id = ?) "
            + "ORDER BY distance_meters, f.facility_id LIMIT ? OFFSET ?",
        (result, row) ->
            new LocatedFacilitySearchResult(
                facilityFromSearch(result),
                locationFromSearch(result),
                result.getDouble("distance_meters")),
        center.longitude(),
        center.latitude(),
        center.longitude(),
        center.latitude(),
        radiusMeters,
        center.longitude(),
        center.latitude(),
        radiusMeters,
        organizationId.map(InternalId::value).orElse(null),
        organizationId.map(InternalId::value).orElse(null),
        facilityId.map(InternalId::value).orElse(null),
        facilityId.map(InternalId::value).orElse(null),
        limit,
        offset);
  }

  @Override
  public List<UnlocatedFacilitySearchResult> findUnlocatedFacilities(
      Optional<InternalId> organizationId, Optional<InternalId> facilityId, int limit, int offset) {
    return jdbc.query(
        "SELECT "
            + FACILITY_SEARCH_COLUMNS
            + ", "
            + LOCATION_SEARCH_COLUMNS
            + " FROM directory.facility f "
            + "JOIN directory.organization o ON o.organization_id = f.managing_organization_id "
            + "LEFT JOIN directory.location l ON l.facility_id = f.facility_id AND l.is_primary "
            + "WHERE f.lifecycle_status = 'ACTIVE' AND o.lifecycle_status = 'ACTIVE' "
            + "AND (l.location_id IS NULL OR l.coordinates IS NULL) "
            + "AND (?::uuid IS NULL OR f.managing_organization_id = ?) "
            + "AND (?::uuid IS NULL OR f.facility_id = ?) ORDER BY f.facility_id LIMIT ? OFFSET ?",
        (result, row) ->
            new UnlocatedFacilitySearchResult(
                facilityFromSearch(result),
                result.getObject("l_id", UUID.class) == null
                    ? Optional.empty()
                    : Optional.of(locationFromSearch(result))),
        organizationId.map(InternalId::value).orElse(null),
        organizationId.map(InternalId::value).orElse(null),
        facilityId.map(InternalId::value).orElse(null),
        facilityId.map(InternalId::value).orElse(null),
        limit,
        offset);
  }

  @Override
  public ReceiptClaim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt) {
    int inserted =
        jdbc.update(
            "INSERT INTO directory.command_receipt (scope_digest, idempotency_key_digest, "
                + "request_fingerprint, receipt_state, recorded_at) VALUES (?, ?, ?, 'IN_PROGRESS', ?) "
                + "ON CONFLICT DO NOTHING",
            key.scopeDigest(),
            key.idempotencyKey().value(),
            fingerprint.value(),
            Timestamp.from(recordedAt));
    if (inserted == 1) {
      return new ReceiptClaim(ReceiptState.OWNER, Optional.empty(), Optional.empty());
    }
    return jdbc.queryForObject(
        "SELECT request_fingerprint, receipt_state, response_status, result_reference "
            + "FROM directory.command_receipt WHERE scope_digest = ? "
            + "AND idempotency_key_digest = ?",
        (result, rowNumber) -> {
          if (!fingerprint.value().equals(result.getString("request_fingerprint"))) {
            return new ReceiptClaim(ReceiptState.KEY_REUSED, Optional.empty(), Optional.empty());
          }
          if ("IN_PROGRESS".equals(result.getString("receipt_state"))) {
            return new ReceiptClaim(ReceiptState.IN_PROGRESS, Optional.empty(), Optional.empty());
          }
          return new ReceiptClaim(
              ReceiptState.REPLAY,
              Optional.of(result.getInt("response_status")),
              Optional.of(new InternalId(result.getObject("result_reference", UUID.class))));
        },
        key.scopeDigest(),
        key.idempotencyKey().value());
  }

  @Override
  public void configureBoundedReceiptWait() {
    jdbc.execute(
        "SET LOCAL lock_timeout = '"
            + idempotencyProperties.getReceiptLockWait().toMillis()
            + "ms'");
  }

  @Override
  public void completeReceipt(
      CommandReceiptKey key, int responseStatus, InternalId resultReference, Instant recordedAt) {
    int changed =
        jdbc.update(
            "UPDATE directory.command_receipt SET receipt_state = 'COMPLETED', response_status = ?, "
                + "result_reference = ?, recorded_at = ? WHERE scope_digest = ? "
                + "AND idempotency_key_digest = ? AND receipt_state = 'IN_PROGRESS'",
            responseStatus,
            resultReference.value(),
            Timestamp.from(recordedAt),
            key.scopeDigest(),
            key.idempotencyKey().value());
    if (changed != 1) {
      throw new IllegalStateException("Directory command receipt could not be completed");
    }
  }

  @Override
  public boolean isActiveOrganization(ExternalReference organization) {
    try {
      InternalId id = directoryId(organization, "Organization");
      if (id == null) {
        return false;
      }
      Integer count =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM directory.organization WHERE organization_id = ? "
                  + "AND lifecycle_status = 'ACTIVE'",
              Integer.class,
              id.value());
      return count != null && count == 1;
    } catch (DataAccessException failure) {
      throw new DirectoryScopeLookupUnavailableException(failure);
    }
  }

  @Override
  public boolean isActiveFacilityInOrganization(
      ExternalReference organization, ExternalReference facility) {
    try {
      InternalId organizationId = directoryId(organization, "Organization");
      InternalId facilityId = directoryId(facility, "Facility");
      if (organizationId == null || facilityId == null) {
        return false;
      }
      Integer count =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM directory.facility f JOIN directory.organization o "
                  + "ON o.organization_id = f.managing_organization_id "
                  + "WHERE f.facility_id = ? AND f.managing_organization_id = ? "
                  + "AND f.lifecycle_status = 'ACTIVE' AND o.lifecycle_status = 'ACTIVE'",
              Integer.class,
              facilityId.value(),
              organizationId.value());
      return count != null && count == 1;
    } catch (DataAccessException failure) {
      throw new DirectoryScopeLookupUnavailableException(failure);
    }
  }

  private Optional<Organization> findOrganization(InternalId id, boolean forUpdate) {
    String lock = forUpdate ? " FOR UPDATE" : "";
    return jdbc
        .query(
            "SELECT "
                + ORGANIZATION_COLUMNS
                + " FROM directory.organization "
                + "WHERE organization_id = ?"
                + lock,
            ORGANIZATION_MAPPER,
            id.value())
        .stream()
        .findFirst();
  }

  private Optional<Facility> findFacility(InternalId id, boolean forUpdate) {
    String lock = forUpdate ? " FOR UPDATE" : "";
    return jdbc
        .query(
            "SELECT "
                + FACILITY_COLUMNS
                + " FROM directory.facility "
                + "WHERE facility_id = ?"
                + lock,
            FACILITY_MAPPER,
            id.value())
        .stream()
        .findFirst();
  }

  private Optional<DirectoryLocation> findLocation(InternalId id, boolean forUpdate) {
    String lock = forUpdate ? " FOR UPDATE" : "";
    return jdbc
        .query(
            "SELECT "
                + LOCATION_COLUMNS
                + " FROM directory.location l WHERE l.location_id = ?"
                + lock,
            LOCATION_MAPPER,
            id.value())
        .stream()
        .findFirst();
  }

  private static String sourceColumns() {
    return "source_issuer, source_resource_type, source_reference, provenance_method, "
        + "provenance_observed_at, provenance_recorded_at, provenance_actor_kind, "
        + "provenance_actor_issuer, provenance_actor_identifier_type, provenance_actor_identifier, "
        + "provenance_reason_code";
  }

  private static Object[] sourceAndProvenanceParameters(
      DirectorySource source, DirectoryProvenance provenance) {
    ExternalReference sourceReference = source.reference();
    ActorReference actor = provenance.recordedBy();
    return new Object[] {
      sourceReference.issuer().value(),
      sourceReference.resourceType(),
      sourceReference.value(),
      provenance.method(),
      Timestamp.from(provenance.observedAt()),
      Timestamp.from(provenance.recordedAt()),
      actor.kind().name(),
      actor.identity().issuer().value(),
      actor.identity().resourceType(),
      actor.identity().value(),
      provenance.reasonCode()
    };
  }

  private static DirectorySource source(java.sql.ResultSet result) throws java.sql.SQLException {
    return source(result, "");
  }

  private static DirectorySource source(java.sql.ResultSet result, String prefix)
      throws java.sql.SQLException {
    return new DirectorySource(
        new ExternalReference(
            new IssuerId(result.getString(prefix + "source_issuer")),
            result.getString(prefix + "source_resource_type"),
            result.getString(prefix + "source_reference")));
  }

  private static DirectoryProvenance provenance(java.sql.ResultSet result)
      throws java.sql.SQLException {
    return provenance(result, "");
  }

  private static DirectoryProvenance provenance(java.sql.ResultSet result, String prefix)
      throws java.sql.SQLException {
    ActorKind kind = ActorKind.valueOf(result.getString(prefix + "provenance_actor_kind"));
    return new DirectoryProvenance(
        result.getString(prefix + "provenance_method"),
        result.getTimestamp(prefix + "provenance_observed_at").toInstant(),
        new ActorReference(
            kind,
            new ExternalReference(
                new IssuerId(result.getString(prefix + "provenance_actor_issuer")),
                result.getString(prefix + "provenance_actor_identifier_type"),
                result.getString(prefix + "provenance_actor_identifier"))),
        result.getTimestamp(prefix + "provenance_recorded_at").toInstant(),
        result.getString(prefix + "provenance_reason_code"));
  }

  private static Facility facilityFromSearch(java.sql.ResultSet result)
      throws java.sql.SQLException {
    return new Facility(
        new InternalId(result.getObject("f_id", UUID.class)),
        new InternalId(result.getObject("f_organization_id", UUID.class)),
        result.getString("f_name"),
        source(result, "f_"),
        provenance(result, "f_"),
        DirectoryLifecycleStatus.valueOf(result.getString("f_lifecycle_status")),
        result.getLong("f_version"));
  }

  private static DirectoryLocation locationFrom(java.sql.ResultSet result)
      throws java.sql.SQLException {
    return locationFrom(
        result,
        "location_id",
        "facility_id",
        "name",
        "longitude",
        "latitude",
        "is_primary",
        "",
        "version");
  }

  private static DirectoryLocation locationFromSearch(java.sql.ResultSet result)
      throws java.sql.SQLException {
    return locationFrom(
        result,
        "l_id",
        "l_facility_id",
        "l_name",
        "l_longitude",
        "l_latitude",
        "l_is_primary",
        "l_",
        "l_version");
  }

  private static DirectoryLocation locationFrom(
      java.sql.ResultSet result,
      String idColumn,
      String facilityColumn,
      String nameColumn,
      String longitudeColumn,
      String latitudeColumn,
      String primaryColumn,
      String provenancePrefix,
      String versionColumn)
      throws java.sql.SQLException {
    return new DirectoryLocation(
        new InternalId(result.getObject(idColumn, UUID.class)),
        new InternalId(result.getObject(facilityColumn, UUID.class)),
        result.getString(nameColumn),
        coordinate(result, longitudeColumn, latitudeColumn),
        result.getBoolean(primaryColumn),
        source(result, provenancePrefix),
        provenance(result, provenancePrefix),
        result.getLong(versionColumn));
  }

  private static Optional<Wgs84Coordinate> coordinate(
      java.sql.ResultSet result, String longitudeColumn, String latitudeColumn)
      throws java.sql.SQLException {
    double longitude = result.getDouble(longitudeColumn);
    boolean longitudeMissing = result.wasNull();
    double latitude = result.getDouble(latitudeColumn);
    boolean latitudeMissing = result.wasNull();
    if (longitudeMissing && latitudeMissing) {
      return Optional.empty();
    }
    if (longitudeMissing || latitudeMissing) {
      throw new java.sql.SQLException("stored location coordinate is incomplete");
    }
    return Optional.of(new Wgs84Coordinate(longitude, latitude));
  }

  private String snapshot(Organization organization) {
    ObjectNode snapshot = objectMapper.createObjectNode();
    snapshot.put("id", organization.id().toString());
    snapshot.put("name", organization.name());
    organization
        .parentOrganizationId()
        .ifPresent(id -> snapshot.put("parentOrganizationId", id.toString()));
    snapshot.put("sourceIssuer", organization.source().reference().issuer().value());
    snapshot.put("sourceResourceType", organization.source().reference().resourceType());
    snapshot.put("sourceReference", organization.source().reference().value());
    snapshot.put("provenanceMethod", organization.provenance().method());
    snapshot.put("provenanceObservedAt", organization.provenance().observedAt().toString());
    snapshot.put("provenanceRecordedAt", organization.provenance().recordedAt().toString());
    snapshot.put("provenanceActorKind", organization.provenance().recordedBy().kind().name());
    snapshot.put(
        "provenanceActorIssuer",
        organization.provenance().recordedBy().identity().issuer().value());
    snapshot.put(
        "provenanceActorIdentifierType",
        organization.provenance().recordedBy().identity().resourceType());
    snapshot.put(
        "provenanceActorIdentifier", organization.provenance().recordedBy().identity().value());
    snapshot.put("provenanceReasonCode", organization.provenance().reasonCode());
    snapshot.put("status", organization.status().name());
    snapshot.put("version", organization.version());
    return json(snapshot);
  }

  private String snapshot(Facility facility) {
    ObjectNode snapshot = objectMapper.createObjectNode();
    snapshot.put("id", facility.id().toString());
    snapshot.put("managingOrganizationId", facility.managingOrganizationId().toString());
    snapshot.put("name", facility.name());
    snapshot.put("sourceIssuer", facility.source().reference().issuer().value());
    snapshot.put("sourceResourceType", facility.source().reference().resourceType());
    snapshot.put("sourceReference", facility.source().reference().value());
    snapshot.put("provenanceMethod", facility.provenance().method());
    snapshot.put("provenanceObservedAt", facility.provenance().observedAt().toString());
    snapshot.put("provenanceRecordedAt", facility.provenance().recordedAt().toString());
    snapshot.put("provenanceActorKind", facility.provenance().recordedBy().kind().name());
    snapshot.put(
        "provenanceActorIssuer", facility.provenance().recordedBy().identity().issuer().value());
    snapshot.put(
        "provenanceActorIdentifierType",
        facility.provenance().recordedBy().identity().resourceType());
    snapshot.put(
        "provenanceActorIdentifier", facility.provenance().recordedBy().identity().value());
    snapshot.put("provenanceReasonCode", facility.provenance().reasonCode());
    snapshot.put("status", facility.status().name());
    snapshot.put("version", facility.version());
    return json(snapshot);
  }

  private String snapshot(DirectoryLocation location) {
    ObjectNode snapshot = objectMapper.createObjectNode();
    snapshot.put("id", location.id().toString());
    snapshot.put("facilityId", location.facilityId().toString());
    snapshot.put("name", location.name());
    location
        .coordinate()
        .ifPresent(
            coordinate -> {
              snapshot.put("longitude", coordinate.longitude());
              snapshot.put("latitude", coordinate.latitude());
            });
    snapshot.put("primary", location.primary());
    snapshot.put("sourceIssuer", location.source().reference().issuer().value());
    snapshot.put("sourceResourceType", location.source().reference().resourceType());
    snapshot.put("sourceReference", location.source().reference().value());
    snapshot.put("provenanceMethod", location.provenance().method());
    snapshot.put("provenanceObservedAt", location.provenance().observedAt().toString());
    snapshot.put("provenanceRecordedAt", location.provenance().recordedAt().toString());
    snapshot.put("provenanceActorKind", location.provenance().recordedBy().kind().name());
    snapshot.put(
        "provenanceActorIssuer", location.provenance().recordedBy().identity().issuer().value());
    snapshot.put(
        "provenanceActorIdentifierType",
        location.provenance().recordedBy().identity().resourceType());
    snapshot.put(
        "provenanceActorIdentifier", location.provenance().recordedBy().identity().value());
    snapshot.put("provenanceReasonCode", location.provenance().reasonCode());
    snapshot.put("version", location.version());
    return json(snapshot);
  }

  private String json(ObjectNode snapshot) {
    try {
      return objectMapper.writeValueAsString(snapshot);
    } catch (JacksonException failure) {
      throw new IllegalStateException("Could not encode a Directory revision snapshot", failure);
    }
  }

  private static InternalId directoryId(ExternalReference reference, String type) {
    if (reference == null
        || !DirectoryAuthoritiesIssuer.VALUE.equals(reference.issuer().value())
        || !type.equals(reference.resourceType())) {
      return null;
    }
    try {
      return InternalId.parse(reference.value());
    } catch (IllegalArgumentException malformed) {
      return null;
    }
  }

  private static final class DirectoryAuthoritiesIssuer {
    private static final String VALUE = "urn:ayuvahak:directory";
  }
}
