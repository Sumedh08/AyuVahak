package org.ayuvahak.directory.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.directory.application.ActiveExternalIdentifierConflictException;
import org.ayuvahak.directory.application.DirectoryConceptOwner;
import org.ayuvahak.directory.application.DirectoryExternalIdentifierHistoryEntry;
import org.ayuvahak.directory.application.DirectoryFacilitySearchEntry;
import org.ayuvahak.directory.application.DirectoryScopeLookupUnavailableException;
import org.ayuvahak.directory.application.DirectoryStore;
import org.ayuvahak.directory.application.LocatedFacilitySearchResult;
import org.ayuvahak.directory.application.UnlocatedFacilitySearchResult;
import org.ayuvahak.directory.domain.CapabilityVerificationStatus;
import org.ayuvahak.directory.domain.CareCapability;
import org.ayuvahak.directory.domain.DirectoryConceptType;
import org.ayuvahak.directory.domain.DirectoryExternalIdentifier;
import org.ayuvahak.directory.domain.DirectoryLifecycleStatus;
import org.ayuvahak.directory.domain.DirectoryLocation;
import org.ayuvahak.directory.domain.DirectoryProvenance;
import org.ayuvahak.directory.domain.DirectorySource;
import org.ayuvahak.directory.domain.ExternalIdentifierStatus;
import org.ayuvahak.directory.domain.Facility;
import org.ayuvahak.directory.domain.FacilityCapabilityAssertion;
import org.ayuvahak.directory.domain.HealthcareService;
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
import org.springframework.dao.DuplicateKeyException;
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
  private static final String HEALTHCARE_SERVICE_COLUMNS =
      "healthcare_service_id, organization_id, facility_id, location_id, name, source_issuer, "
          + "source_resource_type, source_reference, provenance_method, provenance_observed_at, "
          + "provenance_recorded_at, provenance_actor_kind, provenance_actor_issuer, "
          + "provenance_actor_identifier_type, provenance_actor_identifier, provenance_reason_code, "
          + "lifecycle_status, version";
  private static final String CARE_CAPABILITY_COLUMNS =
      "care_capability_id, code, display_name, parent_capability_id, source_issuer, "
          + "source_resource_type, source_reference, provenance_method, provenance_observed_at, "
          + "provenance_recorded_at, provenance_actor_kind, provenance_actor_issuer, "
          + "provenance_actor_identifier_type, provenance_actor_identifier, provenance_reason_code, "
          + "lifecycle_status, version";
  private static final String FACILITY_CAPABILITY_ASSERTION_COLUMNS =
      "assertion_id, facility_id, care_capability_id, healthcare_service_id, verification_status, "
          + "source_issuer, source_resource_type, source_reference, provenance_method, "
          + "provenance_observed_at, provenance_recorded_at, provenance_actor_kind, "
          + "provenance_actor_issuer, provenance_actor_identifier_type, provenance_actor_identifier, "
          + "provenance_reason_code, effective_from, effective_until, version";
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
  private static final RowMapper<HealthcareService> HEALTHCARE_SERVICE_MAPPER =
      (result, rowNumber) ->
          new HealthcareService(
              new InternalId(result.getObject("healthcare_service_id", UUID.class)),
              new InternalId(result.getObject("organization_id", UUID.class)),
              new InternalId(result.getObject("facility_id", UUID.class)),
              Optional.ofNullable(result.getObject("location_id", UUID.class)).map(InternalId::new),
              result.getString("name"),
              source(result),
              provenance(result),
              DirectoryLifecycleStatus.valueOf(result.getString("lifecycle_status")),
              result.getLong("version"));
  private static final RowMapper<CareCapability> CARE_CAPABILITY_MAPPER =
      (result, rowNumber) ->
          new CareCapability(
              new InternalId(result.getObject("care_capability_id", UUID.class)),
              result.getString("code"),
              result.getString("display_name"),
              Optional.ofNullable(result.getObject("parent_capability_id", UUID.class))
                  .map(InternalId::new),
              source(result),
              provenance(result),
              DirectoryLifecycleStatus.valueOf(result.getString("lifecycle_status")),
              result.getLong("version"));
  private static final RowMapper<FacilityCapabilityAssertion> FACILITY_CAPABILITY_ASSERTION_MAPPER =
      (result, rowNumber) ->
          new FacilityCapabilityAssertion(
              new InternalId(result.getObject("assertion_id", UUID.class)),
              new InternalId(result.getObject("facility_id", UUID.class)),
              new InternalId(result.getObject("care_capability_id", UUID.class)),
              Optional.ofNullable(result.getObject("healthcare_service_id", UUID.class))
                  .map(InternalId::new),
              CapabilityVerificationStatus.valueOf(result.getString("verification_status")),
              source(result),
              provenance(result),
              result.getTimestamp("effective_from").toInstant(),
              Optional.ofNullable(result.getTimestamp("effective_until")).map(Timestamp::toInstant),
              result.getLong("version"));
  private static final RowMapper<DirectoryExternalIdentifierHistoryEntry>
      EXTERNAL_IDENTIFIER_HISTORY_MAPPER =
          (result, rowNumber) ->
              new DirectoryExternalIdentifierHistoryEntry(
                  externalIdentifier(result),
                  result.getBoolean("is_current"),
                  result.getTimestamp("recorded_at").toInstant(),
                  Optional.ofNullable(result.getString("change_reason")));

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
  public void insertHealthcareService(HealthcareService service) {
    Object[] sourceParameters =
        sourceAndProvenanceParameters(service.source(), service.provenance());
    Object[] parameters = new Object[5 + sourceParameters.length + 2];
    parameters[0] = service.id().value();
    parameters[1] = service.organizationId().value();
    parameters[2] = service.facilityId().value();
    parameters[3] = service.locationId().map(InternalId::value).orElse(null);
    parameters[4] = service.name();
    System.arraycopy(sourceParameters, 0, parameters, 5, sourceParameters.length);
    parameters[5 + sourceParameters.length] = Timestamp.from(service.provenance().recordedAt());
    parameters[6 + sourceParameters.length] = Timestamp.from(service.provenance().recordedAt());
    jdbc.update(
        "INSERT INTO directory.healthcare_service (healthcare_service_id, organization_id, "
            + "facility_id, location_id, name, "
            + sourceColumns()
            + ", lifecycle_status, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, "
            + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?)",
        parameters);
  }

  @Override
  public Optional<HealthcareService> findHealthcareService(InternalId id) {
    return findHealthcareService(id, false);
  }

  @Override
  public Optional<HealthcareService> lockHealthcareService(InternalId id) {
    return findHealthcareService(id, true);
  }

  @Override
  public void appendHealthcareServiceRevision(HealthcareService current, String reasonCode) {
    jdbc.update(
        "INSERT INTO directory.healthcare_service_revision (revision_id, healthcare_service_id, "
            + "superseded_version, snapshot, changed_at, reason_code) "
            + "VALUES (?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP, ?)",
        InternalId.generate().value(),
        current.id().value(),
        current.version(),
        snapshot(current),
        reasonCode);
  }

  @Override
  public boolean updateHealthcareService(
      HealthcareService corrected, long expectedVersion, Instant updatedAt) {
    Object[] provenanceParameters = provenanceParameters(corrected.provenance());
    return jdbc.update(
            "UPDATE directory.healthcare_service SET name = ?, location_id = ?, "
                + "provenance_method = ?, provenance_observed_at = ?, provenance_recorded_at = ?, "
                + "provenance_actor_kind = ?, provenance_actor_issuer = ?, "
                + "provenance_actor_identifier_type = ?, provenance_actor_identifier = ?, "
                + "provenance_reason_code = ?, lifecycle_status = ?, version = ?, updated_at = ? "
                + "WHERE healthcare_service_id = ? AND version = ?",
            corrected.name(),
            corrected.locationId().map(InternalId::value).orElse(null),
            provenanceParameters[0],
            provenanceParameters[1],
            provenanceParameters[2],
            provenanceParameters[3],
            provenanceParameters[4],
            provenanceParameters[5],
            provenanceParameters[6],
            provenanceParameters[7],
            corrected.status().name(),
            corrected.version(),
            Timestamp.from(updatedAt),
            corrected.id().value(),
            expectedVersion)
        == 1;
  }

  @Override
  public void insertCareCapability(CareCapability capability) {
    Object[] sourceParameters =
        sourceAndProvenanceParameters(capability.source(), capability.provenance());
    Object[] parameters = new Object[4 + sourceParameters.length + 2];
    parameters[0] = capability.id().value();
    parameters[1] = capability.code();
    parameters[2] = capability.displayName();
    parameters[3] = capability.parentCapabilityId().map(InternalId::value).orElse(null);
    System.arraycopy(sourceParameters, 0, parameters, 4, sourceParameters.length);
    parameters[4 + sourceParameters.length] = Timestamp.from(capability.provenance().recordedAt());
    parameters[5 + sourceParameters.length] = Timestamp.from(capability.provenance().recordedAt());
    jdbc.update(
        "INSERT INTO directory.care_capability (care_capability_id, code, display_name, "
            + "parent_capability_id, "
            + sourceColumns()
            + ", lifecycle_status, version, created_at, updated_at) VALUES (?, ?, ?, ?, "
            + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?)",
        parameters);
  }

  @Override
  public Optional<CareCapability> findCareCapability(InternalId id) {
    return findCareCapability(id, false);
  }

  @Override
  public Optional<CareCapability> findCareCapabilityByCode(String code) {
    return jdbc
        .query(
            "SELECT " + CARE_CAPABILITY_COLUMNS + " FROM directory.care_capability WHERE code = ?",
            CARE_CAPABILITY_MAPPER,
            code)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<CareCapability> lockCareCapability(InternalId id) {
    return findCareCapability(id, true);
  }

  @Override
  public List<CareCapability> listCareCapabilities(boolean activeOnly) {
    return jdbc.query(
        "SELECT "
            + CARE_CAPABILITY_COLUMNS
            + " FROM directory.care_capability WHERE (? = false OR lifecycle_status = 'ACTIVE') "
            + "ORDER BY code, care_capability_id LIMIT 500",
        CARE_CAPABILITY_MAPPER,
        activeOnly);
  }

  @Override
  public void appendCareCapabilityRevision(CareCapability current, String reasonCode) {
    jdbc.update(
        "INSERT INTO directory.care_capability_revision (revision_id, care_capability_id, "
            + "superseded_version, snapshot, changed_at, reason_code) "
            + "VALUES (?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP, ?)",
        InternalId.generate().value(),
        current.id().value(),
        current.version(),
        snapshot(current),
        reasonCode);
  }

  @Override
  public boolean updateCareCapability(
      CareCapability corrected, long expectedVersion, Instant updatedAt) {
    Object[] provenanceParameters = provenanceParameters(corrected.provenance());
    return jdbc.update(
            "UPDATE directory.care_capability SET display_name = ?, provenance_method = ?, "
                + "provenance_observed_at = ?, provenance_recorded_at = ?, provenance_actor_kind = ?, "
                + "provenance_actor_issuer = ?, provenance_actor_identifier_type = ?, "
                + "provenance_actor_identifier = ?, provenance_reason_code = ?, lifecycle_status = ?, "
                + "version = ?, updated_at = ? WHERE care_capability_id = ? AND version = ?",
            corrected.displayName(),
            provenanceParameters[0],
            provenanceParameters[1],
            provenanceParameters[2],
            provenanceParameters[3],
            provenanceParameters[4],
            provenanceParameters[5],
            provenanceParameters[6],
            provenanceParameters[7],
            corrected.status().name(),
            corrected.version(),
            Timestamp.from(updatedAt),
            corrected.id().value(),
            expectedVersion)
        == 1;
  }

  @Override
  public void insertFacilityCapabilityAssertion(FacilityCapabilityAssertion assertion) {
    Object[] sourceParameters =
        sourceAndProvenanceParameters(assertion.source(), assertion.provenance());
    Object[] parameters = new Object[5 + sourceParameters.length + 4];
    parameters[0] = assertion.id().value();
    parameters[1] = assertion.facilityId().value();
    parameters[2] = assertion.capabilityId().value();
    parameters[3] = assertion.healthcareServiceId().map(InternalId::value).orElse(null);
    parameters[4] = assertion.verificationStatus().name();
    System.arraycopy(sourceParameters, 0, parameters, 5, sourceParameters.length);
    parameters[5 + sourceParameters.length] = Timestamp.from(assertion.effectiveFrom());
    parameters[6 + sourceParameters.length] =
        assertion.effectiveUntil().map(Timestamp::from).orElse(null);
    parameters[7 + sourceParameters.length] = Timestamp.from(assertion.provenance().recordedAt());
    parameters[8 + sourceParameters.length] = Timestamp.from(assertion.provenance().recordedAt());
    jdbc.update(
        "INSERT INTO directory.facility_capability_assertion (assertion_id, facility_id, "
            + "care_capability_id, healthcare_service_id, verification_status, "
            + sourceColumns()
            + ", effective_from, effective_until, version, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
        parameters);
  }

  @Override
  public Optional<FacilityCapabilityAssertion> findFacilityCapabilityAssertion(InternalId id) {
    return findFacilityCapabilityAssertion(id, false);
  }

  @Override
  public Optional<FacilityCapabilityAssertion> lockFacilityCapabilityAssertion(InternalId id) {
    return findFacilityCapabilityAssertion(id, true);
  }

  @Override
  public List<FacilityCapabilityAssertion> listFacilityCapabilityAssertions(
      InternalId facilityId, InternalId capabilityId) {
    return jdbc.query(
        "SELECT "
            + FACILITY_CAPABILITY_ASSERTION_COLUMNS
            + " FROM directory.facility_capability_assertion WHERE facility_id = ? "
            + "AND care_capability_id = ? ORDER BY assertion_id",
        FACILITY_CAPABILITY_ASSERTION_MAPPER,
        facilityId.value(),
        capabilityId.value());
  }

  @Override
  public void appendFacilityCapabilityAssertionRevision(
      FacilityCapabilityAssertion current, String reasonCode) {
    jdbc.update(
        "INSERT INTO directory.facility_capability_assertion_revision (revision_id, assertion_id, "
            + "superseded_version, snapshot, changed_at, reason_code) "
            + "VALUES (?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP, ?)",
        InternalId.generate().value(),
        current.id().value(),
        current.version(),
        snapshot(current),
        reasonCode);
  }

  @Override
  public boolean updateFacilityCapabilityAssertion(
      FacilityCapabilityAssertion corrected, long expectedVersion, Instant updatedAt) {
    Object[] sourceParameters =
        sourceAndProvenanceParameters(corrected.source(), corrected.provenance());
    Object[] parameters = new Object[1 + sourceParameters.length + 6];
    parameters[0] = corrected.verificationStatus().name();
    System.arraycopy(sourceParameters, 0, parameters, 1, sourceParameters.length);
    parameters[1 + sourceParameters.length] = Timestamp.from(corrected.effectiveFrom());
    parameters[2 + sourceParameters.length] =
        corrected.effectiveUntil().map(Timestamp::from).orElse(null);
    parameters[3 + sourceParameters.length] = corrected.version();
    parameters[4 + sourceParameters.length] = Timestamp.from(updatedAt);
    parameters[5 + sourceParameters.length] = corrected.id().value();
    parameters[6 + sourceParameters.length] = expectedVersion;
    return jdbc.update(
            "UPDATE directory.facility_capability_assertion SET verification_status = ?, "
                + "source_issuer = ?, source_resource_type = ?, source_reference = ?, "
                + "provenance_method = ?, provenance_observed_at = ?, provenance_recorded_at = ?, "
                + "provenance_actor_kind = ?, provenance_actor_issuer = ?, "
                + "provenance_actor_identifier_type = ?, provenance_actor_identifier = ?, "
                + "provenance_reason_code = ?, effective_from = ?, effective_until = ?, version = ?, "
                + "updated_at = ? WHERE assertion_id = ? AND version = ?",
            parameters)
        == 1;
  }

  @Override
  public Optional<DirectoryConceptOwner> findConceptOwner(
      DirectoryConceptType conceptType, InternalId id) {
    return findConceptOwner(conceptType, id, false);
  }

  @Override
  public Optional<DirectoryConceptOwner> lockConceptOwner(
      DirectoryConceptType conceptType, InternalId id) {
    return findConceptOwner(conceptType, id, true);
  }

  private Optional<DirectoryConceptOwner> findConceptOwner(
      DirectoryConceptType conceptType, InternalId id, boolean forUpdate) {
    String query =
        switch (conceptType) {
          case ORGANIZATION ->
              "SELECT organization_id, NULL::uuid AS facility_id, "
                  + "(lifecycle_status = 'ACTIVE') AS owner_active FROM directory.organization "
                  + "WHERE organization_id = ?";
          case FACILITY ->
              "SELECT f.managing_organization_id AS organization_id, f.facility_id AS facility_id, "
                  + "(f.lifecycle_status = 'ACTIVE' AND o.lifecycle_status = 'ACTIVE') AS owner_active "
                  + "FROM directory.facility f JOIN directory.organization o "
                  + "ON o.organization_id = f.managing_organization_id WHERE f.facility_id = ?";
          case LOCATION ->
              "SELECT f.managing_organization_id AS organization_id, f.facility_id AS facility_id, "
                  + "(f.lifecycle_status = 'ACTIVE' AND o.lifecycle_status = 'ACTIVE') AS owner_active "
                  + "FROM directory.location l JOIN directory.facility f ON f.facility_id = l.facility_id "
                  + "JOIN directory.organization o ON o.organization_id = f.managing_organization_id "
                  + "WHERE l.location_id = ?";
          case HEALTHCARE_SERVICE ->
              "SELECT s.organization_id, s.facility_id, "
                  + "(s.lifecycle_status = 'ACTIVE' AND f.lifecycle_status = 'ACTIVE' "
                  + "AND o.lifecycle_status = 'ACTIVE') AS owner_active "
                  + "FROM directory.healthcare_service s JOIN directory.facility f "
                  + "ON f.facility_id = s.facility_id JOIN directory.organization o "
                  + "ON o.organization_id = s.organization_id WHERE s.healthcare_service_id = ?";
          case CARE_CAPABILITY ->
              "SELECT NULL::uuid AS organization_id, NULL::uuid AS facility_id, "
                  + "(lifecycle_status = 'ACTIVE') AS owner_active "
                  + "FROM directory.care_capability WHERE care_capability_id = ?";
          case FACILITY_CAPABILITY_ASSERTION ->
              "SELECT f.managing_organization_id AS organization_id, f.facility_id AS facility_id, "
                  + "(a.verification_status NOT IN ('WITHDRAWN', 'SUPERSEDED') "
                  + "AND f.lifecycle_status = 'ACTIVE' AND o.lifecycle_status = 'ACTIVE') AS owner_active "
                  + "FROM directory.facility_capability_assertion a JOIN directory.facility f "
                  + "ON f.facility_id = a.facility_id JOIN directory.organization o "
                  + "ON o.organization_id = f.managing_organization_id WHERE a.assertion_id = ?";
        };
    return jdbc
        .query(
            query + (forUpdate ? " FOR UPDATE" : ""),
            (result, rowNumber) ->
                new DirectoryConceptOwner(
                    Optional.ofNullable(result.getObject("organization_id", UUID.class))
                        .map(InternalId::new),
                    Optional.ofNullable(result.getObject("facility_id", UUID.class))
                        .map(InternalId::new),
                    result.getBoolean("owner_active")),
            id.value())
        .stream()
        .findFirst();
  }

  @Override
  public void insertExternalIdentifier(DirectoryExternalIdentifier identifier) {
    try {
      jdbc.update(
          "INSERT INTO directory.external_identifier (identifier_id, concept_type, concept_id, "
              + "issuer, resource_type, identifier_value, lifecycle_status, "
              + "supersedes_identifier_id, provenance_method, provenance_observed_at, "
              + "provenance_recorded_at, provenance_actor_kind, provenance_actor_issuer, "
              + "provenance_actor_identifier_type, provenance_actor_identifier, "
              + "provenance_reason_code, version, created_at, updated_at) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          identifier.id().value(),
          identifier.conceptType().name(),
          identifier.conceptId().value(),
          identifier.externalIdentifier().issuer().value(),
          identifier.externalIdentifier().resourceType(),
          identifier.externalIdentifier().value(),
          identifier.status().name(),
          identifier.supersedesIdentifierId().map(InternalId::value).orElse(null),
          identifier.provenance().method(),
          Timestamp.from(identifier.provenance().observedAt()),
          Timestamp.from(identifier.provenance().recordedAt()),
          identifier.provenance().recordedBy().kind().name(),
          identifier.provenance().recordedBy().identity().issuer().value(),
          identifier.provenance().recordedBy().identity().resourceType(),
          identifier.provenance().recordedBy().identity().value(),
          identifier.provenance().reasonCode(),
          identifier.version(),
          Timestamp.from(identifier.createdAt()),
          Timestamp.from(identifier.updatedAt()));
    } catch (DuplicateKeyException conflict) {
      throw new ActiveExternalIdentifierConflictException(conflict);
    }
  }

  @Override
  public Optional<DirectoryExternalIdentifier> findExternalIdentifier(InternalId id) {
    return findExternalIdentifier(id, false);
  }

  @Override
  public Optional<DirectoryExternalIdentifier> lockExternalIdentifier(InternalId id) {
    return findExternalIdentifier(id, true);
  }

  @Override
  public List<DirectoryExternalIdentifierHistoryEntry> listExternalIdentifiers(
      DirectoryConceptType conceptType,
      InternalId conceptId,
      boolean includeHistory,
      int limit,
      int offset) {
    String currentRows =
        "SELECT identifier_id, concept_type, concept_id, issuer, resource_type, identifier_value, "
            + "lifecycle_status, supersedes_identifier_id, provenance_method, "
            + "provenance_observed_at, provenance_recorded_at, provenance_actor_kind, "
            + "provenance_actor_issuer, provenance_actor_identifier_type, "
            + "provenance_actor_identifier, provenance_reason_code, version, created_at, "
            + "updated_at, updated_at AS recorded_at, NULL::text AS change_reason, "
            + "TRUE AS is_current FROM directory.external_identifier "
            + "WHERE concept_type = ? AND concept_id = ?";
    String query;
    List<Object> parameters = new ArrayList<>();
    if (includeHistory) {
      query =
          "SELECT * FROM ("
              + currentRows
              + " UNION ALL SELECT (snapshot->>'id')::uuid AS identifier_id, "
              + "snapshot->>'conceptType' AS concept_type, (snapshot->>'conceptId')::uuid AS concept_id, "
              + "snapshot->>'issuer' AS issuer, snapshot->>'resourceType' AS resource_type, "
              + "snapshot->>'value' AS identifier_value, snapshot->>'status' AS lifecycle_status, "
              + "NULLIF(snapshot->>'supersedesIdentifierId', '')::uuid AS supersedes_identifier_id, "
              + "snapshot->>'provenanceMethod' AS provenance_method, "
              + "(snapshot->>'provenanceObservedAt')::timestamptz AS provenance_observed_at, "
              + "(snapshot->>'provenanceRecordedAt')::timestamptz AS provenance_recorded_at, "
              + "snapshot->>'provenanceActorKind' AS provenance_actor_kind, "
              + "snapshot->>'provenanceActorIssuer' AS provenance_actor_issuer, "
              + "snapshot->>'provenanceActorIdentifierType' AS provenance_actor_identifier_type, "
              + "snapshot->>'provenanceActorIdentifier' AS provenance_actor_identifier, "
              + "snapshot->>'provenanceReasonCode' AS provenance_reason_code, "
              + "(snapshot->>'version')::bigint AS version, "
              + "(snapshot->>'createdAt')::timestamptz AS created_at, "
              + "(snapshot->>'updatedAt')::timestamptz AS updated_at, changed_at AS recorded_at, "
              + "reason_code AS change_reason, FALSE AS is_current "
              + "FROM directory.external_identifier_revision WHERE snapshot->>'conceptType' = ? "
              + "AND snapshot->>'conceptId' = ?"
              + ") history ORDER BY recorded_at DESC, identifier_id, version DESC LIMIT ? OFFSET ?";
      parameters.add(conceptType.name());
      parameters.add(conceptId.value());
      parameters.add(conceptType.name());
      parameters.add(conceptId.toString());
    } else {
      query =
          currentRows
              + " AND lifecycle_status = 'ACTIVE' ORDER BY updated_at DESC, identifier_id LIMIT ? OFFSET ?";
      parameters.add(conceptType.name());
      parameters.add(conceptId.value());
    }
    parameters.add(limit);
    parameters.add(offset);
    return jdbc.query(query, EXTERNAL_IDENTIFIER_HISTORY_MAPPER, parameters.toArray());
  }

  @Override
  public void appendExternalIdentifierRevision(
      DirectoryExternalIdentifier current, String reasonCode, Instant changedAt) {
    jdbc.update(
        "INSERT INTO directory.external_identifier_revision (revision_id, identifier_id, "
            + "superseded_version, snapshot, changed_at, reason_code) VALUES (?, ?, ?, ?::jsonb, ?, ?)",
        InternalId.generate().value(),
        current.id().value(),
        current.version(),
        externalIdentifierSnapshot(current),
        Timestamp.from(changedAt),
        reasonCode);
  }

  @Override
  public boolean updateExternalIdentifier(
      DirectoryExternalIdentifier corrected, long expectedVersion, Instant updatedAt) {
    return jdbc.update(
            "UPDATE directory.external_identifier SET lifecycle_status = ?, provenance_method = ?, "
                + "provenance_observed_at = ?, provenance_recorded_at = ?, provenance_actor_kind = ?, "
                + "provenance_actor_issuer = ?, provenance_actor_identifier_type = ?, "
                + "provenance_actor_identifier = ?, provenance_reason_code = ?, version = ?, updated_at = ? "
                + "WHERE identifier_id = ? AND version = ? AND lifecycle_status = 'ACTIVE'",
            corrected.status().name(),
            corrected.provenance().method(),
            Timestamp.from(corrected.provenance().observedAt()),
            Timestamp.from(corrected.provenance().recordedAt()),
            corrected.provenance().recordedBy().kind().name(),
            corrected.provenance().recordedBy().identity().issuer().value(),
            corrected.provenance().recordedBy().identity().resourceType(),
            corrected.provenance().recordedBy().identity().value(),
            corrected.provenance().reasonCode(),
            corrected.version(),
            Timestamp.from(updatedAt),
            corrected.id().value(),
            expectedVersion)
        == 1;
  }

  @Override
  public List<LocatedFacilitySearchResult> findNearbyFacilities(
      Optional<InternalId> organizationId,
      Optional<InternalId> facilityId,
      Wgs84Coordinate center,
      double radiusMeters,
      List<String> requiredCapabilityCodes,
      Instant evaluatedAt,
      int limit,
      int offset) {
    String point =
        "ST_SetSRID(ST_MakePoint(?::double precision, ?::double precision), 4326)::geography";
    StringBuilder query =
        new StringBuilder(
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
                + "AND (?::uuid IS NULL OR f.facility_id = ?) ");
    List<Object> parameters = new ArrayList<>();
    parameters.add(center.longitude());
    parameters.add(center.latitude());
    parameters.add(center.longitude());
    parameters.add(center.latitude());
    parameters.add(radiusMeters);
    parameters.add(center.longitude());
    parameters.add(center.latitude());
    parameters.add(radiusMeters);
    parameters.add(organizationId.map(InternalId::value).orElse(null));
    parameters.add(organizationId.map(InternalId::value).orElse(null));
    parameters.add(facilityId.map(InternalId::value).orElse(null));
    parameters.add(facilityId.map(InternalId::value).orElse(null));
    appendCapabilityEligibility(query, parameters, requiredCapabilityCodes, evaluatedAt);
    query.append("ORDER BY distance_meters, f.facility_id LIMIT ? OFFSET ?");
    parameters.add(limit);
    parameters.add(offset);
    return jdbc.query(
        query.toString(),
        (result, row) ->
            new LocatedFacilitySearchResult(
                facilityFromSearch(result),
                locationFromSearch(result),
                result.getDouble("distance_meters")),
        parameters.toArray());
  }

  @Override
  public List<UnlocatedFacilitySearchResult> findUnlocatedFacilities(
      Optional<InternalId> organizationId,
      Optional<InternalId> facilityId,
      List<String> requiredCapabilityCodes,
      Instant evaluatedAt,
      int limit,
      int offset) {
    StringBuilder query =
        new StringBuilder(
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
                + "AND (?::uuid IS NULL OR f.facility_id = ?) ");
    List<Object> parameters = new ArrayList<>();
    parameters.add(organizationId.map(InternalId::value).orElse(null));
    parameters.add(organizationId.map(InternalId::value).orElse(null));
    parameters.add(facilityId.map(InternalId::value).orElse(null));
    parameters.add(facilityId.map(InternalId::value).orElse(null));
    appendCapabilityEligibility(query, parameters, requiredCapabilityCodes, evaluatedAt);
    query.append("ORDER BY f.facility_id LIMIT ? OFFSET ?");
    parameters.add(limit);
    parameters.add(offset);
    return jdbc.query(
        query.toString(),
        (result, row) ->
            new UnlocatedFacilitySearchResult(
                facilityFromSearch(result),
                result.getObject("l_id", UUID.class) == null
                    ? Optional.empty()
                    : Optional.of(locationFromSearch(result))),
        parameters.toArray());
  }

  @Override
  public List<DirectoryFacilitySearchEntry> findFacilities(
      Optional<InternalId> organizationId,
      Optional<InternalId> facilityId,
      List<String> requiredCapabilityCodes,
      Instant evaluatedAt,
      int limit,
      int offset) {
    StringBuilder query =
        new StringBuilder(
            "SELECT "
                + FACILITY_SEARCH_COLUMNS
                + ", "
                + LOCATION_SEARCH_COLUMNS
                + " FROM directory.facility f "
                + "JOIN directory.organization o ON o.organization_id = f.managing_organization_id "
                + "LEFT JOIN directory.location l ON l.facility_id = f.facility_id AND l.is_primary "
                + "WHERE f.lifecycle_status = 'ACTIVE' AND o.lifecycle_status = 'ACTIVE' "
                + "AND (?::uuid IS NULL OR f.managing_organization_id = ?) "
                + "AND (?::uuid IS NULL OR f.facility_id = ?) ");
    List<Object> parameters = new ArrayList<>();
    parameters.add(organizationId.map(InternalId::value).orElse(null));
    parameters.add(organizationId.map(InternalId::value).orElse(null));
    parameters.add(facilityId.map(InternalId::value).orElse(null));
    parameters.add(facilityId.map(InternalId::value).orElse(null));
    appendCapabilityEligibility(query, parameters, requiredCapabilityCodes, evaluatedAt);
    query.append("ORDER BY f.facility_id LIMIT ? OFFSET ?");
    parameters.add(limit);
    parameters.add(offset);
    return jdbc.query(
        query.toString(),
        (result, row) ->
            new DirectoryFacilitySearchEntry(
                facilityFromSearch(result),
                result.getObject("l_id", UUID.class) == null
                    ? Optional.empty()
                    : Optional.of(locationFromSearch(result))),
        parameters.toArray());
  }

  private static void appendCapabilityEligibility(
      StringBuilder query,
      List<Object> parameters,
      List<String> requiredCapabilityCodes,
      Instant evaluatedAt) {
    for (String code : requiredCapabilityCodes) {
      query.append(
          "AND EXISTS (SELECT 1 FROM directory.facility_capability_assertion a "
              + "JOIN directory.care_capability c ON c.care_capability_id = a.care_capability_id "
              + "LEFT JOIN directory.healthcare_service s "
              + "ON s.healthcare_service_id = a.healthcare_service_id "
              + "WHERE a.facility_id = f.facility_id AND c.code = ? "
              + "AND c.lifecycle_status = 'ACTIVE' AND a.verification_status = 'VERIFIED' "
              + "AND a.effective_from <= ? AND (a.effective_until IS NULL OR a.effective_until > ?) "
              + "AND (a.healthcare_service_id IS NULL OR s.lifecycle_status = 'ACTIVE')) ");
      Timestamp evaluatedAtTimestamp = Timestamp.from(evaluatedAt);
      parameters.add(code);
      parameters.add(evaluatedAtTimestamp);
      parameters.add(evaluatedAtTimestamp);
    }
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

  private Optional<DirectoryExternalIdentifier> findExternalIdentifier(
      InternalId id, boolean forUpdate) {
    String lock = forUpdate ? " FOR UPDATE" : "";
    return jdbc
        .query(
            "SELECT * FROM directory.external_identifier WHERE identifier_id = ?" + lock,
            (result, rowNumber) -> externalIdentifier(result),
            id.value())
        .stream()
        .findFirst();
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

  private Optional<HealthcareService> findHealthcareService(InternalId id, boolean forUpdate) {
    String lock = forUpdate ? " FOR UPDATE" : "";
    return jdbc
        .query(
            "SELECT "
                + HEALTHCARE_SERVICE_COLUMNS
                + " FROM directory.healthcare_service WHERE healthcare_service_id = ?"
                + lock,
            HEALTHCARE_SERVICE_MAPPER,
            id.value())
        .stream()
        .findFirst();
  }

  private Optional<CareCapability> findCareCapability(InternalId id, boolean forUpdate) {
    String lock = forUpdate ? " FOR UPDATE" : "";
    return jdbc
        .query(
            "SELECT "
                + CARE_CAPABILITY_COLUMNS
                + " FROM directory.care_capability WHERE care_capability_id = ?"
                + lock,
            CARE_CAPABILITY_MAPPER,
            id.value())
        .stream()
        .findFirst();
  }

  private Optional<FacilityCapabilityAssertion> findFacilityCapabilityAssertion(
      InternalId id, boolean forUpdate) {
    String lock = forUpdate ? " FOR UPDATE" : "";
    return jdbc
        .query(
            "SELECT "
                + FACILITY_CAPABILITY_ASSERTION_COLUMNS
                + " FROM directory.facility_capability_assertion WHERE assertion_id = ?"
                + lock,
            FACILITY_CAPABILITY_ASSERTION_MAPPER,
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

  private static Object[] provenanceParameters(DirectoryProvenance provenance) {
    ActorReference actor = provenance.recordedBy();
    return new Object[] {
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

  private static DirectoryExternalIdentifier externalIdentifier(java.sql.ResultSet result)
      throws java.sql.SQLException {
    return new DirectoryExternalIdentifier(
        new InternalId(result.getObject("identifier_id", UUID.class)),
        DirectoryConceptType.valueOf(result.getString("concept_type")),
        new InternalId(result.getObject("concept_id", UUID.class)),
        new ExternalReference(
            new IssuerId(result.getString("issuer")),
            result.getString("resource_type"),
            result.getString("identifier_value")),
        ExternalIdentifierStatus.valueOf(result.getString("lifecycle_status")),
        Optional.ofNullable(result.getObject("supersedes_identifier_id", UUID.class))
            .map(InternalId::new),
        provenance(result),
        result.getLong("version"),
        result.getTimestamp("created_at").toInstant(),
        result.getTimestamp("updated_at").toInstant());
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

  private String snapshot(HealthcareService service) {
    ObjectNode snapshot = objectMapper.createObjectNode();
    snapshot.put("id", service.id().toString());
    snapshot.put("organizationId", service.organizationId().toString());
    snapshot.put("facilityId", service.facilityId().toString());
    service.locationId().ifPresent(id -> snapshot.put("locationId", id.toString()));
    snapshot.put("name", service.name());
    snapshot.put("sourceIssuer", service.source().reference().issuer().value());
    snapshot.put("sourceResourceType", service.source().reference().resourceType());
    snapshot.put("sourceReference", service.source().reference().value());
    snapshot.put("provenanceMethod", service.provenance().method());
    snapshot.put("provenanceObservedAt", service.provenance().observedAt().toString());
    snapshot.put("provenanceRecordedAt", service.provenance().recordedAt().toString());
    snapshot.put("provenanceActorKind", service.provenance().recordedBy().kind().name());
    snapshot.put(
        "provenanceActorIssuer", service.provenance().recordedBy().identity().issuer().value());
    snapshot.put(
        "provenanceActorIdentifierType",
        service.provenance().recordedBy().identity().resourceType());
    snapshot.put("provenanceActorIdentifier", service.provenance().recordedBy().identity().value());
    snapshot.put("provenanceReasonCode", service.provenance().reasonCode());
    snapshot.put("status", service.status().name());
    snapshot.put("version", service.version());
    return json(snapshot);
  }

  private String snapshot(CareCapability capability) {
    ObjectNode snapshot = objectMapper.createObjectNode();
    snapshot.put("id", capability.id().toString());
    snapshot.put("code", capability.code());
    snapshot.put("displayName", capability.displayName());
    capability
        .parentCapabilityId()
        .ifPresent(id -> snapshot.put("parentCapabilityId", id.toString()));
    snapshot.put("sourceIssuer", capability.source().reference().issuer().value());
    snapshot.put("sourceResourceType", capability.source().reference().resourceType());
    snapshot.put("sourceReference", capability.source().reference().value());
    snapshot.put("provenanceMethod", capability.provenance().method());
    snapshot.put("provenanceObservedAt", capability.provenance().observedAt().toString());
    snapshot.put("provenanceRecordedAt", capability.provenance().recordedAt().toString());
    snapshot.put("provenanceActorKind", capability.provenance().recordedBy().kind().name());
    snapshot.put(
        "provenanceActorIssuer", capability.provenance().recordedBy().identity().issuer().value());
    snapshot.put(
        "provenanceActorIdentifierType",
        capability.provenance().recordedBy().identity().resourceType());
    snapshot.put(
        "provenanceActorIdentifier", capability.provenance().recordedBy().identity().value());
    snapshot.put("provenanceReasonCode", capability.provenance().reasonCode());
    snapshot.put("status", capability.status().name());
    snapshot.put("version", capability.version());
    return json(snapshot);
  }

  private String snapshot(FacilityCapabilityAssertion assertion) {
    ObjectNode snapshot = objectMapper.createObjectNode();
    snapshot.put("id", assertion.id().toString());
    snapshot.put("facilityId", assertion.facilityId().toString());
    snapshot.put("capabilityId", assertion.capabilityId().toString());
    assertion
        .healthcareServiceId()
        .ifPresent(id -> snapshot.put("healthcareServiceId", id.toString()));
    snapshot.put("verificationStatus", assertion.verificationStatus().name());
    snapshot.put("sourceIssuer", assertion.source().reference().issuer().value());
    snapshot.put("sourceResourceType", assertion.source().reference().resourceType());
    snapshot.put("sourceReference", assertion.source().reference().value());
    snapshot.put("provenanceMethod", assertion.provenance().method());
    snapshot.put("provenanceObservedAt", assertion.provenance().observedAt().toString());
    snapshot.put("provenanceRecordedAt", assertion.provenance().recordedAt().toString());
    snapshot.put("provenanceActorKind", assertion.provenance().recordedBy().kind().name());
    snapshot.put(
        "provenanceActorIssuer", assertion.provenance().recordedBy().identity().issuer().value());
    snapshot.put(
        "provenanceActorIdentifierType",
        assertion.provenance().recordedBy().identity().resourceType());
    snapshot.put(
        "provenanceActorIdentifier", assertion.provenance().recordedBy().identity().value());
    snapshot.put("provenanceReasonCode", assertion.provenance().reasonCode());
    snapshot.put("effectiveFrom", assertion.effectiveFrom().toString());
    assertion.effectiveUntil().ifPresent(until -> snapshot.put("effectiveUntil", until.toString()));
    snapshot.put("version", assertion.version());
    return json(snapshot);
  }

  private String externalIdentifierSnapshot(DirectoryExternalIdentifier identifier) {
    ObjectNode snapshot = objectMapper.createObjectNode();
    snapshot.put("id", identifier.id().toString());
    snapshot.put("conceptType", identifier.conceptType().name());
    snapshot.put("conceptId", identifier.conceptId().toString());
    snapshot.put("issuer", identifier.externalIdentifier().issuer().value());
    snapshot.put("resourceType", identifier.externalIdentifier().resourceType());
    snapshot.put("value", identifier.externalIdentifier().value());
    snapshot.put("status", identifier.status().name());
    identifier
        .supersedesIdentifierId()
        .ifPresent(id -> snapshot.put("supersedesIdentifierId", id.toString()));
    snapshot.put("provenanceMethod", identifier.provenance().method());
    snapshot.put("provenanceObservedAt", identifier.provenance().observedAt().toString());
    snapshot.put("provenanceRecordedAt", identifier.provenance().recordedAt().toString());
    snapshot.put("provenanceActorKind", identifier.provenance().recordedBy().kind().name());
    snapshot.put(
        "provenanceActorIssuer", identifier.provenance().recordedBy().identity().issuer().value());
    snapshot.put(
        "provenanceActorIdentifierType",
        identifier.provenance().recordedBy().identity().resourceType());
    snapshot.put(
        "provenanceActorIdentifier", identifier.provenance().recordedBy().identity().value());
    snapshot.put("provenanceReasonCode", identifier.provenance().reasonCode());
    snapshot.put("version", identifier.version());
    snapshot.put("createdAt", identifier.createdAt().toString());
    snapshot.put("updatedAt", identifier.updatedAt().toString());
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
