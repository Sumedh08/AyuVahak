package org.ayuvahak.analytics.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.application.FederatedAggregateException;
import org.ayuvahak.analytics.application.FederatedAggregatePublicationStore;
import org.ayuvahak.analytics.domain.FederatedAggregatePublication;
import org.ayuvahak.analytics.domain.FederatedAggregatePublication.State;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC lifecycle for one-shot peer aggregate publications. */
@Repository
public class JdbcFederatedAggregatePublicationStore implements FederatedAggregatePublicationStore {

  private static final String SELECT_OPERATION =
      "SELECT operation_id, operation_fingerprint, source_deployment_id, peer_deployment_id, "
          + "source_snapshot_id, revision_series_id, revision_number, mapping_key, mapping_version, "
          + "partner_key, partner_configuration_version, definition_fingerprint, content_fingerprint, "
          + "disclosure_reservation_id, operation_state, result_class, receipt_id, created_at, updated_at "
          + "FROM analytics.federated_aggregate_publication ";

  private static final org.springframework.jdbc.core.RowMapper<FederatedAggregatePublication>
      MAPPER = (result, row) -> fromRow(result);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcFederatedAggregatePublicationStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public CreateResult createOrReplay(FederatedAggregatePublication proposed) {
    try {
      return transactions.execute(
          status -> {
            int inserted =
                jdbc.update(
                    "INSERT INTO analytics.federated_aggregate_publication "
                        + "(operation_id, operation_fingerprint, source_deployment_id, peer_deployment_id, "
                        + "source_snapshot_id, revision_series_id, revision_number, mapping_key, mapping_version, "
                        + "partner_key, partner_configuration_version, definition_fingerprint, content_fingerprint, "
                        + "operation_state, result_class, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (source_deployment_id, peer_deployment_id, source_snapshot_id, mapping_key, mapping_version) "
                        + "DO NOTHING",
                    proposed.operationId(),
                    proposed.operationFingerprint(),
                    proposed.sourceDeployment().value(),
                    proposed.peerDeployment().value(),
                    proposed.sourceSnapshotId(),
                    proposed.revisionSeriesId(),
                    proposed.revisionNumber(),
                    proposed.mappingKey(),
                    proposed.mappingVersion(),
                    proposed.partnerKey().value(),
                    proposed.partnerConfigurationVersion(),
                    proposed.definitionFingerprint(),
                    proposed.contentFingerprint(),
                    proposed.state().name(),
                    proposed.resultClass(),
                    Timestamp.from(proposed.createdAt()),
                    Timestamp.from(proposed.updatedAt()));
            FederatedAggregatePublication stored =
                findByIdentity(proposed)
                    .orElseThrow(FederatedAggregatePublicationStoreFailure::new);
            if (!stored.operationFingerprint().equals(proposed.operationFingerprint())) {
              throw new FederatedAggregateException(
                  FederatedAggregateException.Reason.OPERATION_CONFLICT);
            }
            return new CreateResult(stored, inserted == 1);
          });
    } catch (DuplicateKeyException conflictingIdentity) {
      throw new FederatedAggregateException(FederatedAggregateException.Reason.OPERATION_CONFLICT);
    }
  }

  @Override
  public Optional<FederatedAggregatePublication> find(UUID operationId) {
    return jdbc.query(SELECT_OPERATION + "WHERE operation_id = ?", MAPPER, operationId).stream()
        .findFirst();
  }

  @Override
  public FederatedAggregatePublication attachDisclosureReservation(
      UUID operationId, UUID reservationId, Instant changedAt) {
    int changed =
        jdbc.update(
            "UPDATE analytics.federated_aggregate_publication SET disclosure_reservation_id = ?, "
                + "updated_at = ? WHERE operation_id = ? AND operation_state = 'READY' "
                + "AND (disclosure_reservation_id IS NULL OR disclosure_reservation_id = ?)",
            reservationId,
            Timestamp.from(changedAt),
            operationId,
            reservationId);
    if (changed != 1) {
      return find(operationId)
          .filter(
              value -> value.disclosureReservationId().filter(reservationId::equals).isPresent())
          .orElseThrow(
              () ->
                  new FederatedAggregateException(
                      FederatedAggregateException.Reason.OPERATION_CONFLICT));
    }
    return find(operationId)
        .orElseThrow(
            () ->
                new FederatedAggregateException(
                    FederatedAggregateException.Reason.OPERATION_CONFLICT));
  }

  @Override
  public Optional<FederatedAggregatePublication> claimForSend(UUID operationId, Instant changedAt) {
    int changed =
        jdbc.update(
            "UPDATE analytics.federated_aggregate_publication SET operation_state = 'SENDING', "
                + "result_class = 'SEND_STARTED', updated_at = ? WHERE operation_id = ? "
                + "AND operation_state = 'READY' AND disclosure_reservation_id IS NOT NULL",
            Timestamp.from(changedAt),
            operationId);
    return changed == 1 ? find(operationId) : Optional.empty();
  }

  @Override
  public FederatedAggregatePublication transition(
      UUID operationId,
      State expected,
      State next,
      String resultClass,
      Optional<UUID> receiptId,
      Instant changedAt) {
    int changed =
        jdbc.update(
            "UPDATE analytics.federated_aggregate_publication SET operation_state = ?, result_class = ?, "
                + "receipt_id = COALESCE(?, receipt_id), updated_at = ? WHERE operation_id = ? "
                + "AND operation_state = ?",
            next.name(),
            resultClass,
            receiptId.orElse(null),
            Timestamp.from(changedAt),
            operationId,
            expected.name());
    if (changed != 1) {
      return find(operationId)
          .filter(value -> value.state() == next)
          .orElseThrow(
              () ->
                  new FederatedAggregateException(
                      FederatedAggregateException.Reason.OPERATION_CONFLICT));
    }
    return find(operationId)
        .orElseThrow(
            () ->
                new FederatedAggregateException(
                    FederatedAggregateException.Reason.OPERATION_CONFLICT));
  }

  @Override
  public void attachAudit(UUID operationId, UUID auditRecordId) {
    if (jdbc.update(
            "UPDATE analytics.federated_aggregate_publication SET audit_record_id = ? "
                + "WHERE operation_id = ? AND audit_record_id IS NULL",
            auditRecordId,
            operationId)
        != 1) {
      throw new FederatedAggregateException(FederatedAggregateException.Reason.OPERATION_CONFLICT);
    }
  }

  private Optional<FederatedAggregatePublication> findByIdentity(
      FederatedAggregatePublication identity) {
    return jdbc
        .query(
            SELECT_OPERATION
                + "WHERE source_deployment_id = ? AND peer_deployment_id = ? AND source_snapshot_id = ? "
                + "AND mapping_key = ? AND mapping_version = ? FOR UPDATE",
            MAPPER,
            identity.sourceDeployment().value(),
            identity.peerDeployment().value(),
            identity.sourceSnapshotId(),
            identity.mappingKey(),
            identity.mappingVersion())
        .stream()
        .findFirst();
  }

  private static FederatedAggregatePublication fromRow(ResultSet result) throws SQLException {
    return new FederatedAggregatePublication(
        result.getObject("operation_id", UUID.class),
        result.getString("operation_fingerprint"),
        new DeploymentId(result.getString("source_deployment_id")),
        new DeploymentId(result.getString("peer_deployment_id")),
        result.getObject("source_snapshot_id", UUID.class),
        result.getObject("revision_series_id", UUID.class),
        result.getInt("revision_number"),
        result.getString("mapping_key"),
        result.getInt("mapping_version"),
        new PartnerKey(result.getString("partner_key")),
        result.getInt("partner_configuration_version"),
        result.getString("definition_fingerprint"),
        result.getString("content_fingerprint"),
        Optional.ofNullable(result.getObject("disclosure_reservation_id", UUID.class)),
        State.valueOf(result.getString("operation_state")),
        result.getString("result_class"),
        Optional.ofNullable(result.getObject("receipt_id", UUID.class)),
        result.getTimestamp("created_at").toInstant(),
        result.getTimestamp("updated_at").toInstant());
  }

  private static final class FederatedAggregatePublicationStoreFailure extends RuntimeException {}
}
