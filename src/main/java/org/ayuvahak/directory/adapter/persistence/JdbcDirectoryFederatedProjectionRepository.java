package org.ayuvahak.directory.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.directory.application.DirectoryFederatedFact;
import org.ayuvahak.directory.application.DirectoryFederatedProjection;
import org.ayuvahak.directory.application.DirectoryFederatedProjectionPolicy;
import org.ayuvahak.directory.application.DirectoryFederatedProjectionRepository;
import org.ayuvahak.directory.application.DirectoryRemoteResourceReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL adapter for the Directory-owned federated discovery projection. */
@Repository
@Lazy
public class JdbcDirectoryFederatedProjectionRepository
    implements DirectoryFederatedProjectionRepository {

  private static final String SELECT_BY_ID =
      "SELECT source_deployment_id, source_context, resource_type, resource_id, "
          + "organization_resource_id, display_name, source_active, source_version, "
          + "source_updated_at, source_policy_version, partner_key, "
          + "partner_configuration_version, fact_fingerprint, projection_status, "
          + "conflict_fingerprint, observed_at, received_at, valid_until "
          + "FROM directory.federated_remote_projection "
          + "WHERE source_deployment_id = ? AND source_context = ? "
          + "AND resource_type = ? AND resource_id = ?";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  public JdbcDirectoryFederatedProjectionRepository(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    transaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
  }

  @Override
  public DirectoryFederatedProjectionPolicy.Result reconcile(
      DirectoryFederatedFact fact,
      PartnerKey partnerKey,
      int partnerConfigurationVersion,
      Instant receivedAt,
      Duration freshness) {
    Objects.requireNonNull(fact, "fact");
    Objects.requireNonNull(partnerKey, "partnerKey");
    Objects.requireNonNull(receivedAt, "receivedAt");
    Objects.requireNonNull(freshness, "freshness");
    return reconcilePage(
            List.of(fact),
            partnerKey,
            partnerConfigurationVersion,
            fact.sourceUpdatedAt().orElse(receivedAt),
            receivedAt,
            freshness)
        .getFirst();
  }

  @Override
  public List<DirectoryFederatedProjectionPolicy.Result> reconcilePage(
      List<DirectoryFederatedFact> facts,
      PartnerKey partnerKey,
      int partnerConfigurationVersion,
      Instant sourceObservedAt,
      Instant receivedAt,
      Duration freshness) {
    Objects.requireNonNull(facts, "facts");
    List<DirectoryFederatedFact> pageFacts = List.copyOf(facts);
    Objects.requireNonNull(partnerKey, "partnerKey");
    Objects.requireNonNull(sourceObservedAt, "sourceObservedAt");
    Objects.requireNonNull(receivedAt, "receivedAt");
    Objects.requireNonNull(freshness, "freshness");
    if (pageFacts.size() > 100
        || pageFacts.stream().map(DirectoryFederatedFact::reference).distinct().count()
            != pageFacts.size()) {
      throw new IllegalArgumentException(
          "remote Directory page is duplicated or exceeds its bound");
    }
    try {
      return Objects.requireNonNull(
          transaction.execute(
              status ->
                  pageFacts.stream()
                      .map(
                          fact ->
                              reconcileInTransaction(
                                  fact,
                                  partnerKey,
                                  partnerConfigurationVersion,
                                  sourceObservedAt,
                                  receivedAt,
                                  freshness))
                      .toList()),
          "projection page reconciliation result");
    } catch (DataAccessException unavailable) {
      throw new DirectoryFederatedProjectionStoreUnavailableException(unavailable);
    }
  }

  @Override
  public Optional<DirectoryFederatedProjection> find(DirectoryRemoteResourceReference reference) {
    Objects.requireNonNull(reference, "reference");
    try {
      return jdbc
          .query(
              SELECT_BY_ID,
              JdbcDirectoryFederatedProjectionRepository::mapProjection,
              reference.deploymentId().value(),
              reference.context(),
              reference.resourceType(),
              reference.resourceId())
          .stream()
          .findFirst();
    } catch (DataAccessException unavailable) {
      throw new DirectoryFederatedProjectionStoreUnavailableException(unavailable);
    }
  }

  @Override
  public List<DirectoryFederatedProjection> findByRemoteScope(
      DeploymentId sourceDeployment, ExternalReference remoteScope) {
    Objects.requireNonNull(sourceDeployment, "sourceDeployment");
    Objects.requireNonNull(remoteScope, "remoteScope");
    try {
      if ("Organization".equals(remoteScope.resourceType())) {
        return jdbc.query(
            SELECT_BY_ID.substring(0, SELECT_BY_ID.indexOf("WHERE"))
                + "WHERE source_deployment_id = ? AND source_context = 'Directory' "
                + "AND ((resource_type = 'Organization' AND resource_id = ?) "
                + "OR organization_resource_id = ?) ORDER BY resource_type, resource_id",
            JdbcDirectoryFederatedProjectionRepository::mapProjection,
            sourceDeployment.value(),
            remoteScope.value(),
            remoteScope.value());
      }
      if ("Facility".equals(remoteScope.resourceType())) {
        return jdbc.query(
            SELECT_BY_ID.substring(0, SELECT_BY_ID.indexOf("WHERE"))
                + "WHERE source_deployment_id = ? AND source_context = 'Directory' "
                + "AND resource_type = 'Facility' AND resource_id = ? ORDER BY resource_id",
            JdbcDirectoryFederatedProjectionRepository::mapProjection,
            sourceDeployment.value(),
            remoteScope.value());
      }
      throw new IllegalArgumentException("remote Directory scope must be Organization or Facility");
    } catch (DataAccessException unavailable) {
      throw new DirectoryFederatedProjectionStoreUnavailableException(unavailable);
    }
  }

  private DirectoryFederatedProjectionPolicy.Result reconcileInTransaction(
      DirectoryFederatedFact incoming,
      PartnerKey partnerKey,
      int partnerConfigurationVersion,
      Instant sourceObservedAt,
      Instant receivedAt,
      Duration freshness) {
    Optional<DirectoryFederatedProjection> current = selectForUpdate(incoming.reference());
    DirectoryFederatedProjectionPolicy.Result result =
        DirectoryFederatedProjectionPolicy.reconcile(
            current,
            incoming,
            partnerKey,
            partnerConfigurationVersion,
            sourceObservedAt,
            receivedAt,
            freshness);
    if (current.isEmpty()) {
      int inserted = insertIfAbsent(result.projection());
      if (inserted == 1) {
        return result;
      }
      // A concurrent first observation won the unique key. Read its committed version and
      // reconcile against it instead of failing or overwriting it blindly.
      current = selectForUpdate(incoming.reference());
      if (current.isEmpty()) {
        throw new IllegalStateException("concurrent projection insert was not visible");
      }
      result =
          DirectoryFederatedProjectionPolicy.reconcile(
              current,
              incoming,
              partnerKey,
              partnerConfigurationVersion,
              sourceObservedAt,
              receivedAt,
              freshness);
    }
    if (result.outcome() != DirectoryFederatedProjectionPolicy.Outcome.STALE_IGNORED) {
      update(result.projection());
    }
    return result;
  }

  private Optional<DirectoryFederatedProjection> selectForUpdate(
      DirectoryRemoteResourceReference reference) {
    return jdbc
        .query(
            SELECT_BY_ID + " FOR UPDATE",
            JdbcDirectoryFederatedProjectionRepository::mapProjection,
            reference.deploymentId().value(),
            reference.context(),
            reference.resourceType(),
            reference.resourceId())
        .stream()
        .findFirst();
  }

  private int insertIfAbsent(DirectoryFederatedProjection projection) {
    return jdbc.update(
        "INSERT INTO directory.federated_remote_projection (source_deployment_id, "
            + "source_context, resource_type, resource_id, organization_resource_id, display_name, "
            + "source_active, source_version, source_updated_at, source_policy_version, partner_key, "
            + "partner_configuration_version, fact_fingerprint, projection_status, "
            + "conflict_fingerprint, observed_at, received_at, valid_until) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (source_deployment_id, source_context, resource_type, resource_id) "
            + "DO NOTHING",
        parameters(projection));
  }

  private void update(DirectoryFederatedProjection projection) {
    DirectoryFederatedFact fact = projection.fact();
    Object[] values = parameters(projection);
    jdbc.update(
        "UPDATE directory.federated_remote_projection SET organization_resource_id = ?, "
            + "display_name = ?, source_active = ?, source_version = ?, source_updated_at = ?, "
            + "source_policy_version = ?, partner_key = ?, partner_configuration_version = ?, "
            + "fact_fingerprint = ?, projection_status = ?, conflict_fingerprint = ?, "
            + "observed_at = ?, received_at = ?, valid_until = ? WHERE source_deployment_id = ? "
            + "AND source_context = ? AND resource_type = ? AND resource_id = ?",
        values[4],
        values[5],
        values[6],
        values[7],
        values[8],
        values[9],
        values[10],
        values[11],
        values[12],
        values[13],
        values[14],
        values[15],
        values[16],
        values[17],
        fact.reference().deploymentId().value(),
        fact.reference().context(),
        fact.reference().resourceType(),
        fact.reference().resourceId());
  }

  private static Object[] parameters(DirectoryFederatedProjection projection) {
    DirectoryFederatedFact fact = projection.fact();
    return new Object[] {
      fact.reference().deploymentId().value(),
      fact.reference().context(),
      fact.reference().resourceType(),
      fact.reference().resourceId(),
      fact.organization().map(DirectoryRemoteResourceReference::resourceId).orElse(null),
      fact.displayName(),
      fact.active(),
      fact.sourceVersion(),
      fact.sourceUpdatedAt().map(Timestamp::from).orElse(null),
      fact.sourcePolicyVersion(),
      projection.partnerKey().value(),
      projection.partnerConfigurationVersion(),
      projection.factFingerprint(),
      projection.status().name(),
      projection.conflictFingerprint().orElse(null),
      Timestamp.from(projection.observedAt()),
      Timestamp.from(projection.receivedAt()),
      Timestamp.from(projection.validUntil())
    };
  }

  private static DirectoryFederatedProjection mapProjection(ResultSet result, int row)
      throws SQLException {
    var deployment = new DeploymentId(result.getString("source_deployment_id"));
    var resource =
        new DirectoryRemoteResourceReference(
            deployment,
            result.getString("source_context"),
            result.getString("resource_type"),
            result.getString("resource_id"));
    String organizationId = result.getString("organization_resource_id");
    Optional<DirectoryRemoteResourceReference> organization =
        Optional.ofNullable(organizationId)
            .map(
                id ->
                    new DirectoryRemoteResourceReference(
                        deployment, "Directory", "Organization", id));
    Timestamp sourceUpdated = result.getTimestamp("source_updated_at");
    var fact =
        new DirectoryFederatedFact(
            resource,
            organization,
            result.getString("display_name"),
            result.getBoolean("source_active"),
            result.getLong("source_version"),
            Optional.ofNullable(sourceUpdated).map(Timestamp::toInstant),
            result.getString("source_policy_version"));
    String conflictFingerprint = result.getString("conflict_fingerprint");
    return new DirectoryFederatedProjection(
        fact,
        new PartnerKey(result.getString("partner_key")),
        result.getInt("partner_configuration_version"),
        result.getString("fact_fingerprint").trim(),
        DirectoryFederatedProjection.Status.valueOf(result.getString("projection_status")),
        Optional.ofNullable(conflictFingerprint).map(String::trim),
        result.getTimestamp("observed_at").toInstant(),
        result.getTimestamp("received_at").toInstant(),
        result.getTimestamp("valid_until").toInstant());
  }
}
