package org.ayuvahak.analytics.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.application.FederatedAggregateMappingConflictException;
import org.ayuvahak.analytics.application.FederatedAggregateMappingStore;
import org.ayuvahak.analytics.domain.FederatedAggregateMappingVersion;
import org.ayuvahak.analytics.domain.FederatedAggregateMappingVersion.Direction;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC storage for version-immutable, administrator-approved Analytics peer mappings. */
@Repository
public class JdbcFederatedAggregateMappingStore implements FederatedAggregateMappingStore {

  private static final String SELECT_VERSION =
      "SELECT m.mapping_key, m.mapping_version, m.direction, m.indicator_key, m.indicator_version, "
          + "m.definition_fingerprint, m.source_organization_issuer, m.source_organization_reference_type, "
          + "m.source_organization_id, m.partner_key, m.peer_deployment_id, m.partner_configuration_version, "
          + "m.scope_organization_issuer, m.scope_organization_reference_type, m.scope_organization_id, "
          + "m.disclosure_policy_key, m.disclosure_output_key, m.effective_from, m.effective_until, "
          + "m.mapping_fingerprint FROM analytics.federated_aggregate_mapping_version m ";

  private static final org.springframework.jdbc.core.RowMapper<FederatedAggregateMappingVersion>
      MAPPER = (result, row) -> fromRow(result);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcFederatedAggregateMappingStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public PublishResult publish(FederatedAggregateMappingVersion mapping, Instant recordedAt) {
    try {
      return transactions.execute(
          status -> {
            jdbc.update(
                "INSERT INTO analytics.federated_aggregate_mapping_head "
                    + "(mapping_key, active_version, lifecycle_state, updated_at) "
                    + "VALUES (?, NULL, 'INACTIVE', ?) ON CONFLICT (mapping_key) DO NOTHING",
                mapping.mappingKey(),
                Timestamp.from(recordedAt));
            jdbc.queryForObject(
                "SELECT mapping_key FROM analytics.federated_aggregate_mapping_head "
                    + "WHERE mapping_key = ? FOR UPDATE",
                String.class,
                mapping.mappingKey());
            assertMappingKeyScope(mapping);
            assertSharedDisclosureScope(mapping, recordedAt);
            Optional<FederatedAggregateMappingVersion> existing =
                findVersion(mapping.mappingKey(), mapping.version());
            if (existing.isPresent()) {
              if (existing.orElseThrow().fingerprint().equals(mapping.fingerprint())) {
                return new PublishResult(existing.orElseThrow(), false);
              }
              throw new FederatedAggregateMappingConflictException();
            }
            Integer maxVersion =
                jdbc.queryForObject(
                    "SELECT COALESCE(MAX(mapping_version), 0) FROM "
                        + "analytics.federated_aggregate_mapping_version WHERE mapping_key = ?",
                    Integer.class,
                    mapping.mappingKey());
            if (mapping.version() != maxVersion + 1) {
              throw new FederatedAggregateMappingConflictException();
            }
            jdbc.update(
                "INSERT INTO analytics.federated_aggregate_mapping_version "
                    + "(mapping_key, mapping_version, direction, indicator_key, indicator_version, "
                    + "definition_fingerprint, source_organization_issuer, source_organization_reference_type, "
                    + "source_organization_id, partner_key, peer_deployment_id, partner_configuration_version, "
                    + "scope_organization_issuer, scope_organization_reference_type, scope_organization_id, "
                    + "disclosure_policy_key, disclosure_output_key, effective_from, effective_until, "
                    + "mapping_fingerprint, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                mapping.mappingKey(),
                mapping.version(),
                mapping.direction().name(),
                mapping.indicatorKey(),
                mapping.indicatorVersion(),
                mapping.definitionFingerprint(),
                mapping.sourceOrganization().issuer().value(),
                mapping.sourceOrganization().resourceType(),
                mapping.sourceOrganization().value(),
                mapping.partnerKey().value(),
                mapping.peerDeployment().value(),
                mapping.partnerConfigurationVersion(),
                mapping.peerOrganizationScope().issuer().value(),
                mapping.peerOrganizationScope().resourceType(),
                mapping.peerOrganizationScope().value(),
                mapping.disclosurePolicyKey(),
                mapping.disclosureOutputKey(),
                Timestamp.from(mapping.effectiveFrom()),
                mapping.effectiveUntil().map(Timestamp::from).orElse(null),
                mapping.fingerprint(),
                Timestamp.from(recordedAt));
            jdbc.update(
                "UPDATE analytics.federated_aggregate_mapping_head SET active_version = ?, "
                    + "lifecycle_state = 'ACTIVE', updated_at = ? WHERE mapping_key = ?",
                mapping.version(),
                Timestamp.from(recordedAt),
                mapping.mappingKey());
            return new PublishResult(mapping, true);
          });
    } catch (DuplicateKeyException concurrentPublish) {
      return findVersion(mapping.mappingKey(), mapping.version())
          .filter(existing -> existing.fingerprint().equals(mapping.fingerprint()))
          .map(existing -> new PublishResult(existing, false))
          .orElseThrow(FederatedAggregateMappingConflictException::new);
    }
  }

  @Override
  public void attachAudit(String mappingKey, int version, UUID auditRecordId) {
    if (jdbc.update(
            "UPDATE analytics.federated_aggregate_mapping_version SET audit_record_id = ? "
                + "WHERE mapping_key = ? AND mapping_version = ? AND audit_record_id IS NULL",
            auditRecordId,
            mappingKey,
            version)
        != 1) {
      throw new FederatedAggregateMappingConflictException();
    }
    jdbc.update(
        "UPDATE analytics.federated_aggregate_mapping_head SET audit_record_id = ? WHERE mapping_key = ?",
        auditRecordId,
        mappingKey);
  }

  @Override
  public void deactivate(String mappingKey, Instant changedAt) {
    if (jdbc.update(
            "UPDATE analytics.federated_aggregate_mapping_head SET lifecycle_state = 'INACTIVE', "
                + "updated_at = ? WHERE mapping_key = ? AND lifecycle_state = 'ACTIVE'",
            Timestamp.from(changedAt),
            mappingKey)
        != 1) {
      throw new FederatedAggregateMappingConflictException();
    }
  }

  @Override
  public void attachLifecycleAudit(String mappingKey, UUID auditRecordId) {
    if (jdbc.update(
            "UPDATE analytics.federated_aggregate_mapping_head SET audit_record_id = ? WHERE mapping_key = ?",
            auditRecordId,
            mappingKey)
        != 1) {
      throw new FederatedAggregateMappingConflictException();
    }
  }

  @Override
  public Optional<FederatedAggregateMappingVersion> activeMapping(
      Direction direction,
      String indicatorKey,
      int indicatorVersion,
      ExternalReference sourceOrganization,
      DeploymentId peerDeployment,
      ExternalReference peerOrganizationScope,
      Instant at) {
    return jdbc
        .query(
            SELECT_VERSION
                + "JOIN analytics.federated_aggregate_mapping_head h ON h.mapping_key = m.mapping_key "
                + "AND h.active_version = m.mapping_version AND h.lifecycle_state = 'ACTIVE' "
                + "WHERE m.direction = ? AND m.indicator_key = ? AND m.indicator_version = ? "
                + "AND m.source_organization_issuer = ? AND m.source_organization_reference_type = ? "
                + "AND m.source_organization_id = ? AND m.peer_deployment_id = ? "
                + "AND m.scope_organization_issuer = ? AND m.scope_organization_reference_type = ? "
                + "AND m.scope_organization_id = ? AND m.effective_from <= ? "
                + "AND (m.effective_until IS NULL OR ? < m.effective_until)",
            MAPPER,
            direction.name(),
            indicatorKey,
            indicatorVersion,
            sourceOrganization.issuer().value(),
            sourceOrganization.resourceType(),
            sourceOrganization.value(),
            peerDeployment.value(),
            peerOrganizationScope.issuer().value(),
            peerOrganizationScope.resourceType(),
            peerOrganizationScope.value(),
            Timestamp.from(at),
            Timestamp.from(at))
        .stream()
        .findFirst();
  }

  @Override
  public Optional<FederatedAggregateMappingVersion> findVersion(String mappingKey, int version) {
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
  public Optional<FederatedAggregateMappingVersion> activeByKey(String mappingKey, Instant at) {
    return jdbc
        .query(
            SELECT_VERSION
                + "JOIN analytics.federated_aggregate_mapping_head h ON h.mapping_key = m.mapping_key "
                + "AND h.active_version = m.mapping_version AND h.lifecycle_state = 'ACTIVE' "
                + "WHERE m.mapping_key = ? AND m.effective_from <= ? "
                + "AND (m.effective_until IS NULL OR ? < m.effective_until)",
            MAPPER,
            mappingKey,
            Timestamp.from(at),
            Timestamp.from(at))
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Integer> findActiveVersion(String mappingKey) {
    return jdbc
        .query(
            "SELECT active_version FROM analytics.federated_aggregate_mapping_head "
                + "WHERE mapping_key = ? AND lifecycle_state = 'ACTIVE'",
            (result, row) -> result.getInt("active_version"),
            mappingKey)
        .stream()
        .findFirst();
  }

  private void assertMappingKeyScope(FederatedAggregateMappingVersion proposed) {
    var existing =
        jdbc.query(
            "SELECT direction, indicator_key, indicator_version, source_organization_issuer, "
                + "source_organization_reference_type, source_organization_id, peer_deployment_id, "
                + "scope_organization_issuer, scope_organization_reference_type, scope_organization_id, "
                + "disclosure_output_key FROM analytics.federated_aggregate_mapping_version "
                + "WHERE mapping_key = ? ORDER BY mapping_version LIMIT 1",
            (result, row) ->
                new MappingScope(
                    result.getString("direction"),
                    result.getString("indicator_key"),
                    result.getInt("indicator_version"),
                    result.getString("source_organization_issuer"),
                    result.getString("source_organization_reference_type"),
                    result.getString("source_organization_id"),
                    result.getString("peer_deployment_id"),
                    result.getString("scope_organization_issuer"),
                    result.getString("scope_organization_reference_type"),
                    result.getString("scope_organization_id"),
                    result.getString("disclosure_output_key")),
            proposed.mappingKey());
    if (!existing.isEmpty() && !existing.getFirst().matches(proposed)) {
      throw new FederatedAggregateMappingConflictException();
    }
  }

  private void assertSharedDisclosureScope(
      FederatedAggregateMappingVersion proposed, Instant recordedAt) {
    ExternalReference organization = proposed.administrativeOrganization();
    UUID organizationId;
    try {
      organizationId = UUID.fromString(organization.value());
    } catch (IllegalArgumentException invalid) {
      throw new FederatedAggregateMappingConflictException();
    }
    jdbc.update(
        "INSERT INTO analytics.dhis2_mapping_disclosure_scope (indicator_key, indicator_version, "
            + "organization_issuer, organization_reference_type, organization_id, disclosure_policy_key, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        proposed.indicatorKey(),
        proposed.indicatorVersion(),
        organization.issuer().value(),
        organization.resourceType(),
        organizationId,
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
            organization.issuer().value(),
            organization.resourceType(),
            organizationId);
    if (!proposed.disclosurePolicyKey().equals(configuredPolicy)) {
      throw new FederatedAggregateMappingConflictException();
    }
  }

  private static FederatedAggregateMappingVersion fromRow(ResultSet result) throws SQLException {
    return new FederatedAggregateMappingVersion(
        result.getString("mapping_key"),
        result.getInt("mapping_version"),
        Direction.valueOf(result.getString("direction")),
        result.getString("indicator_key"),
        result.getInt("indicator_version"),
        result.getString("definition_fingerprint"),
        externalReference(
            result,
            "source_organization_issuer",
            "source_organization_reference_type",
            "source_organization_id"),
        new PartnerKey(result.getString("partner_key")),
        new DeploymentId(result.getString("peer_deployment_id")),
        result.getInt("partner_configuration_version"),
        externalReference(
            result,
            "scope_organization_issuer",
            "scope_organization_reference_type",
            "scope_organization_id"),
        result.getString("disclosure_policy_key"),
        result.getString("disclosure_output_key"),
        result.getTimestamp("effective_from").toInstant(),
        Optional.ofNullable(result.getTimestamp("effective_until")).map(Timestamp::toInstant),
        result.getString("mapping_fingerprint"));
  }

  private static ExternalReference externalReference(
      ResultSet result, String issuerColumn, String typeColumn, String idColumn)
      throws SQLException {
    return new ExternalReference(
        new IssuerId(result.getString(issuerColumn)),
        result.getString(typeColumn),
        result.getString(idColumn));
  }

  private record MappingScope(
      String direction,
      String indicatorKey,
      int indicatorVersion,
      String sourceIssuer,
      String sourceType,
      String sourceId,
      String peerDeployment,
      String scopeIssuer,
      String scopeType,
      String scopeId,
      String disclosureOutputKey) {

    private boolean matches(FederatedAggregateMappingVersion mapping) {
      return direction.equals(mapping.direction().name())
          && indicatorKey.equals(mapping.indicatorKey())
          && indicatorVersion == mapping.indicatorVersion()
          && sourceIssuer.equals(mapping.sourceOrganization().issuer().value())
          && sourceType.equals(mapping.sourceOrganization().resourceType())
          && sourceId.equals(mapping.sourceOrganization().value())
          && peerDeployment.equals(mapping.peerDeployment().value())
          && scopeIssuer.equals(mapping.peerOrganizationScope().issuer().value())
          && scopeType.equals(mapping.peerOrganizationScope().resourceType())
          && scopeId.equals(mapping.peerOrganizationScope().value())
          && disclosureOutputKey.equals(mapping.disclosureOutputKey());
    }
  }
}
