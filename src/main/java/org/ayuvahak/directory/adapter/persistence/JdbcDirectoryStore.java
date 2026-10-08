package org.ayuvahak.directory.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.directory.application.DirectoryScopeLookupUnavailableException;
import org.ayuvahak.directory.application.DirectoryStore;
import org.ayuvahak.directory.domain.DirectoryLifecycleStatus;
import org.ayuvahak.directory.domain.DirectoryProvenance;
import org.ayuvahak.directory.domain.DirectorySource;
import org.ayuvahak.directory.domain.Facility;
import org.ayuvahak.directory.domain.Organization;
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
    return new DirectorySource(
        new ExternalReference(
            new IssuerId(result.getString("source_issuer")),
            result.getString("source_resource_type"),
            result.getString("source_reference")));
  }

  private static DirectoryProvenance provenance(java.sql.ResultSet result)
      throws java.sql.SQLException {
    ActorKind kind = ActorKind.valueOf(result.getString("provenance_actor_kind"));
    return new DirectoryProvenance(
        result.getString("provenance_method"),
        result.getTimestamp("provenance_observed_at").toInstant(),
        new ActorReference(
            kind,
            new ExternalReference(
                new IssuerId(result.getString("provenance_actor_issuer")),
                result.getString("provenance_actor_identifier_type"),
                result.getString("provenance_actor_identifier"))),
        result.getTimestamp("provenance_recorded_at").toInstant(),
        result.getString("provenance_reason_code"));
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
