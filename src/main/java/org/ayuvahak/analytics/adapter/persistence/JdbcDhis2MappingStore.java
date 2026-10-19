package org.ayuvahak.analytics.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.application.Dhis2MappingConflictException;
import org.ayuvahak.analytics.application.Dhis2MappingStore;
import org.ayuvahak.analytics.domain.Dhis2MappingVersion;
import org.ayuvahak.analytics.domain.Dhis2PeriodFormat;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC storage for version-immutable Analytics mappings to preconfigured DHIS2 metadata. */
@Repository
public class JdbcDhis2MappingStore implements Dhis2MappingStore {

  private static final String SELECT_VERSION =
      "SELECT m.mapping_key, m.mapping_version, m.indicator_key, m.indicator_version, "
          + "m.organization_issuer, m.organization_reference_type, m.organization_id, m.facility_id, m.partner_key, "
          + "m.data_element_uid, m.org_unit_uid, m.data_set_uid, m.category_option_combo_uid, "
          + "m.attribute_option_combo_uid, m.period_format, m.disclosure_policy_key, "
          + "m.disclosure_output_key, m.exclusive_write_authority, m.zero_is_significant, "
          + "m.effective_from, m.effective_until, m.mapping_fingerprint "
          + "FROM analytics.dhis2_mapping_version m ";

  private static final org.springframework.jdbc.core.RowMapper<Dhis2MappingVersion> MAPPER =
      (result, row) -> fromRow(result);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcDhis2MappingStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public PublishResult publish(Dhis2MappingVersion mapping, Instant recordedAt) {
    try {
      return transactions.execute(
          status -> {
            jdbc.update(
                "INSERT INTO analytics.dhis2_mapping_head(mapping_key, active_version, lifecycle_state, updated_at) "
                    + "VALUES (?, NULL, 'INACTIVE', ?) ON CONFLICT (mapping_key) DO NOTHING",
                mapping.mappingKey(),
                Timestamp.from(recordedAt));
            jdbc.queryForObject(
                "SELECT mapping_key FROM analytics.dhis2_mapping_head WHERE mapping_key = ? FOR UPDATE",
                String.class,
                mapping.mappingKey());
            assertMappingKeyScope(mapping);
            assertDisclosureScope(mapping, recordedAt);
            Optional<Dhis2MappingVersion> existing =
                findVersion(mapping.mappingKey(), mapping.version());
            if (existing.isPresent()) {
              if (existing.orElseThrow().fingerprint().equals(mapping.fingerprint())) {
                return new PublishResult(existing.orElseThrow(), false);
              }
              throw new Dhis2MappingConflictException();
            }
            Integer maxVersion =
                jdbc.queryForObject(
                    "SELECT COALESCE(MAX(mapping_version), 0) FROM analytics.dhis2_mapping_version WHERE mapping_key = ?",
                    Integer.class,
                    mapping.mappingKey());
            if (mapping.version() != maxVersion + 1) {
              throw new Dhis2MappingConflictException();
            }
            jdbc.update(
                "INSERT INTO analytics.dhis2_mapping_version (mapping_key, mapping_version, indicator_key, "
                    + "indicator_version, organization_issuer, organization_reference_type, organization_id, facility_id, "
                    + "partner_key, data_element_uid, org_unit_uid, data_set_uid, category_option_combo_uid, "
                    + "attribute_option_combo_uid, period_format, disclosure_policy_key, disclosure_output_key, "
                    + "exclusive_write_authority, zero_is_significant, effective_from, effective_until, "
                    + "mapping_fingerprint, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                mapping.mappingKey(),
                mapping.version(),
                mapping.indicatorKey(),
                mapping.indicatorVersion(),
                mapping.organization().issuer().value(),
                mapping.organization().resourceType(),
                UUID.fromString(mapping.organization().value()),
                mapping.facility().map(value -> UUID.fromString(value.value())).orElse(null),
                mapping.partnerKey().value(),
                mapping.dataElementUid(),
                mapping.orgUnitUid(),
                mapping.dataSetUid().orElse(null),
                mapping.categoryOptionComboUid().orElse(null),
                mapping.attributeOptionComboUid().orElse(null),
                mapping.periodFormat().name(),
                mapping.disclosurePolicyKey(),
                mapping.disclosureOutputKey(),
                mapping.exclusiveWriteAuthority(),
                mapping.zeroIsSignificant(),
                Timestamp.from(mapping.effectiveFrom()),
                mapping.effectiveUntil().map(Timestamp::from).orElse(null),
                mapping.fingerprint(),
                Timestamp.from(recordedAt));
            jdbc.update(
                "UPDATE analytics.dhis2_mapping_head SET active_version = ?, lifecycle_state = 'ACTIVE', updated_at = ? WHERE mapping_key = ?",
                mapping.version(),
                Timestamp.from(recordedAt),
                mapping.mappingKey());
            return new PublishResult(mapping, true);
          });
    } catch (DuplicateKeyException concurrentPublish) {
      return findVersion(mapping.mappingKey(), mapping.version())
          .filter(existing -> existing.fingerprint().equals(mapping.fingerprint()))
          .map(existing -> new PublishResult(existing, false))
          .orElseThrow(Dhis2MappingConflictException::new);
    }
  }

  @Override
  public void attachAudit(String mappingKey, int version, UUID auditRecordId) {
    if (jdbc.update(
            "UPDATE analytics.dhis2_mapping_version SET audit_record_id = ? WHERE mapping_key = ? AND mapping_version = ? AND audit_record_id IS NULL",
            auditRecordId,
            mappingKey,
            version)
        != 1) {
      throw new Dhis2MappingConflictException();
    }
    jdbc.update(
        "UPDATE analytics.dhis2_mapping_head SET audit_record_id = ? WHERE mapping_key = ?",
        auditRecordId,
        mappingKey);
  }

  @Override
  public void deactivate(String mappingKey, Instant changedAt) {
    if (jdbc.update(
            "UPDATE analytics.dhis2_mapping_head SET lifecycle_state = 'INACTIVE', updated_at = ? WHERE mapping_key = ? AND lifecycle_state = 'ACTIVE'",
            Timestamp.from(changedAt),
            mappingKey)
        != 1) {
      throw new Dhis2MappingConflictException();
    }
  }

  @Override
  public void attachLifecycleAudit(String mappingKey, UUID auditRecordId) {
    if (jdbc.update(
            "UPDATE analytics.dhis2_mapping_head SET audit_record_id = ? WHERE mapping_key = ?",
            auditRecordId,
            mappingKey)
        != 1) {
      throw new Dhis2MappingConflictException();
    }
  }

  @Override
  public Optional<Dhis2MappingVersion> activeMapping(
      String indicatorKey, int indicatorVersion, ExternalReference organization, Instant at) {
    return activeMapping(indicatorKey, indicatorVersion, organization, Optional.empty(), at);
  }

  @Override
  public Optional<Dhis2MappingVersion> activeMapping(
      String indicatorKey,
      int indicatorVersion,
      ExternalReference organization,
      Optional<ExternalReference> facility,
      Instant at) {
    return jdbc
        .query(
            SELECT_VERSION
                + "JOIN analytics.dhis2_mapping_head h ON h.mapping_key = m.mapping_key "
                + "AND h.active_version = m.mapping_version AND h.lifecycle_state = 'ACTIVE' "
                + "WHERE m.indicator_key = ? AND m.indicator_version = ? AND m.organization_issuer = ? "
                + "AND m.organization_reference_type = ? AND m.organization_id = ? "
                + "AND m.facility_id IS NOT DISTINCT FROM ? "
                + "AND m.effective_from <= ? AND (m.effective_until IS NULL OR ? < m.effective_until)",
            MAPPER,
            indicatorKey,
            indicatorVersion,
            organization.issuer().value(),
            organization.resourceType(),
            UUID.fromString(organization.value()),
            facility.map(value -> UUID.fromString(value.value())).orElse(null),
            Timestamp.from(at),
            Timestamp.from(at))
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Dhis2MappingVersion> findVersion(String mappingKey, int version) {
    return jdbc
        .query(
            SELECT_VERSION + "WHERE m.mapping_key = ? AND m.mapping_version = ?",
            MAPPER,
            mappingKey,
            version)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Integer> findActiveVersion(String mappingKey) {
    return jdbc
        .query(
            "SELECT active_version FROM analytics.dhis2_mapping_head WHERE mapping_key = ? AND lifecycle_state = 'ACTIVE'",
            (result, row) -> result.getInt("active_version"),
            mappingKey)
        .stream()
        .findFirst();
  }

  private static Dhis2MappingVersion fromRow(ResultSet result) throws SQLException {
    UUID facilityId = result.getObject("facility_id", UUID.class);
    IssuerId organizationIssuer = new IssuerId(result.getString("organization_issuer"));
    return new Dhis2MappingVersion(
        result.getString("mapping_key"),
        result.getInt("mapping_version"),
        result.getString("indicator_key"),
        result.getInt("indicator_version"),
        new ExternalReference(
            organizationIssuer,
            result.getString("organization_reference_type"),
            result.getObject("organization_id", UUID.class).toString()),
        Optional.ofNullable(facilityId)
            .map(id -> new ExternalReference(organizationIssuer, "Facility", id.toString())),
        new PartnerKey(result.getString("partner_key")),
        result.getString("data_element_uid"),
        result.getString("org_unit_uid"),
        Optional.ofNullable(result.getString("data_set_uid")),
        Optional.ofNullable(result.getString("category_option_combo_uid")),
        Optional.ofNullable(result.getString("attribute_option_combo_uid")),
        Dhis2PeriodFormat.valueOf(result.getString("period_format")),
        result.getString("disclosure_policy_key"),
        result.getString("disclosure_output_key"),
        result.getBoolean("exclusive_write_authority"),
        result.getBoolean("zero_is_significant"),
        result.getTimestamp("effective_from").toInstant(),
        Optional.ofNullable(result.getTimestamp("effective_until")).map(Timestamp::toInstant),
        result.getString("mapping_fingerprint"));
  }

  private void assertMappingKeyScope(Dhis2MappingVersion proposed) {
    var existing =
        jdbc.query(
            "SELECT indicator_key, indicator_version, organization_issuer, "
                + "organization_reference_type, organization_id, facility_id FROM analytics.dhis2_mapping_version "
                + "WHERE mapping_key = ? ORDER BY mapping_version LIMIT 1",
            (result, row) ->
                new MappingScope(
                    result.getString("indicator_key"),
                    result.getInt("indicator_version"),
                    result.getString("organization_issuer"),
                    result.getString("organization_reference_type"),
                    result.getObject("organization_id", UUID.class),
                    Optional.ofNullable(result.getObject("facility_id", UUID.class))),
            proposed.mappingKey());
    if (!existing.isEmpty() && !existing.getFirst().matches(proposed)) {
      throw new Dhis2MappingConflictException();
    }
  }

  private void assertDisclosureScope(Dhis2MappingVersion proposed, Instant recordedAt) {
    jdbc.update(
        "INSERT INTO analytics.dhis2_mapping_disclosure_scope (indicator_key, indicator_version, "
            + "organization_issuer, organization_reference_type, organization_id, disclosure_policy_key, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        proposed.indicatorKey(),
        proposed.indicatorVersion(),
        proposed.organization().issuer().value(),
        proposed.organization().resourceType(),
        UUID.fromString(proposed.organization().value()),
        proposed.disclosurePolicyKey(),
        Timestamp.from(recordedAt));
    String configuredPolicy =
        jdbc.queryForObject(
            "SELECT disclosure_policy_key FROM analytics.dhis2_mapping_disclosure_scope "
                + "WHERE indicator_key = ? AND indicator_version = ? AND organization_issuer = ? "
                + "AND organization_reference_type = ? AND organization_id = ? FOR UPDATE",
            String.class,
            proposed.indicatorKey(),
            proposed.indicatorVersion(),
            proposed.organization().issuer().value(),
            proposed.organization().resourceType(),
            UUID.fromString(proposed.organization().value()));
    if (!proposed.disclosurePolicyKey().equals(configuredPolicy)) {
      throw new Dhis2MappingConflictException();
    }
  }

  private record MappingScope(
      String indicatorKey,
      int indicatorVersion,
      String organizationIssuer,
      String organizationReferenceType,
      UUID organizationId,
      Optional<UUID> facilityId) {

    private boolean matches(Dhis2MappingVersion mapping) {
      return indicatorKey.equals(mapping.indicatorKey())
          && indicatorVersion == mapping.indicatorVersion()
          && organizationIssuer.equals(mapping.organization().issuer().value())
          && organizationReferenceType.equals(mapping.organization().resourceType())
          && organizationId.equals(UUID.fromString(mapping.organization().value()))
          && facilityId.equals(mapping.facility().map(value -> UUID.fromString(value.value())));
    }
  }
}
