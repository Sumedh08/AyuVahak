package org.ayuvahak.analytics.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.ayuvahak.analytics.application.FederatedAggregateException;
import org.ayuvahak.analytics.application.FederatedAggregateReceiptStore;
import org.ayuvahak.analytics.domain.FederatedAggregateReceipt;
import org.ayuvahak.analytics.domain.FederatedAggregateSnapshot;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.exchange.application.FederationOperationFingerprint;
import org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC inbox receipt deduplicated by authenticated source deployment and immutable snapshot ID. */
@Repository
public class JdbcFederatedAggregateReceiptStore implements FederatedAggregateReceiptStore {

  private final JdbcTemplate jdbc;

  public JdbcFederatedAggregateReceiptStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public RecordResult recordOrReplay(
      FederationPeerInboundAuthorization authorization,
      ExternalReference targetOrganization,
      FederatedAggregateSnapshot snapshot,
      UUID operationId,
      FederationOperationFingerprint operationFingerprint,
      FederatedAggregateReceipt.State state,
      String resultClass,
      String mappingKey,
      Integer mappingVersion,
      Instant receivedAt) {
    UUID receiptId = UUID.randomUUID();
    int inserted =
        jdbc.update(
            "INSERT INTO analytics.federated_aggregate_receipt "
                + "(receipt_id, source_deployment_id, operation_id, operation_fingerprint, content_fingerprint, source_snapshot_id, "
                + "revision_series_id, revision_number, indicator_key, indicator_version, definition_fingerprint, "
                + "source_fingerprint, source_organization_issuer, source_organization_reference_type, "
                + "source_organization_id, target_organization_id, period_start_local, period_end_exclusive_local, "
                + "period_timezone, period_start_utc, period_end_exclusive_utc, timezone_rules_version, completeness, "
                + "aggregate_value, attribution, computed_at, peer_key, peer_configuration_version, mapping_key, "
                + "mapping_version, receipt_state, result_class, received_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING",
            receiptId,
            snapshot.sourceDeployment().value(),
            operationId,
            operationFingerprint.value(),
            snapshot.contentFingerprint(),
            snapshot.sourceSnapshotId(),
            snapshot.revisionSeriesId(),
            snapshot.revisionNumber(),
            snapshot.indicatorKey(),
            snapshot.indicatorVersion(),
            snapshot.definitionFingerprint(),
            snapshot.sourceFingerprint(),
            snapshot.sourceOrganization().issuer().value(),
            snapshot.sourceOrganization().resourceType(),
            snapshot.sourceOrganization().value(),
            targetOrganization.value(),
            java.sql.Date.valueOf(snapshot.period().startInclusiveLocal()),
            java.sql.Date.valueOf(snapshot.period().endExclusiveLocal()),
            snapshot.period().timeZoneId(),
            Timestamp.from(snapshot.period().startInclusiveUtc()),
            Timestamp.from(snapshot.period().endExclusiveUtc()),
            snapshot.period().timeZoneRulesVersion(),
            snapshot.completeness().name(),
            snapshot.value(),
            snapshot.attribution().name(),
            Timestamp.from(snapshot.computedAt()),
            authorization.partnerKey().value(),
            authorization.profileVersion(),
            mappingKey,
            mappingVersion,
            state.name(),
            resultClass,
            Timestamp.from(receivedAt));

    var existing =
        jdbc.query(
            "SELECT receipt_id, operation_id, operation_fingerprint, content_fingerprint, target_organization_id, "
                + "receipt_state, result_class, peer_key, peer_configuration_version, mapping_key, mapping_version, received_at "
                + "FROM analytics.federated_aggregate_receipt WHERE source_deployment_id = ? "
                + "AND source_snapshot_id = ? FOR UPDATE",
            (result, row) ->
                new ExistingReceipt(
                    result.getObject("receipt_id", UUID.class),
                    result.getObject("operation_id", UUID.class),
                    result.getString("operation_fingerprint"),
                    result.getString("content_fingerprint"),
                    result.getString("target_organization_id"),
                    FederatedAggregateReceipt.State.valueOf(result.getString("receipt_state")),
                    result.getString("result_class"),
                    result.getString("peer_key"),
                    result.getInt("peer_configuration_version"),
                    result.getString("mapping_key"),
                    (Integer) result.getObject("mapping_version"),
                    result.getTimestamp("received_at").toInstant()),
            snapshot.sourceDeployment().value(),
            snapshot.sourceSnapshotId());
    if (existing.isEmpty()) {
      throw new FederatedAggregateException(FederatedAggregateException.Reason.RECEIPT_CONFLICT);
    }
    ExistingReceipt stored = existing.getFirst();
    if (!stored.contentFingerprint().equals(snapshot.contentFingerprint())) {
      throw new FederatedAggregateException(FederatedAggregateException.Reason.RECEIPT_CONFLICT);
    }
    if (stored.operationId().equals(operationId)
        && !stored.operationFingerprint().equals(operationFingerprint.value())) {
      throw new FederatedAggregateException(FederatedAggregateException.Reason.RECEIPT_CONFLICT);
    }
    if (!stored.targetOrganizationId().equals(targetOrganization.value())) {
      throw new FederatedAggregateException(FederatedAggregateException.Reason.RECEIPT_CONFLICT);
    }
    if (inserted == 1) {
      return new RecordResult(
          receipt(stored, authorization, targetOrganization, snapshot, operationId, false), true);
    }
    return new RecordResult(
        receipt(stored, authorization, targetOrganization, snapshot, operationId, true), false);
  }

  @Override
  public void attachAudit(UUID receiptId, UUID auditRecordId) {
    if (jdbc.update(
            "UPDATE analytics.federated_aggregate_receipt SET audit_record_id = ? "
                + "WHERE receipt_id = ? AND audit_record_id IS NULL",
            auditRecordId,
            receiptId)
        != 1) {
      throw new FederatedAggregateException(FederatedAggregateException.Reason.RECEIPT_CONFLICT);
    }
  }

  private static FederatedAggregateReceipt receipt(
      ExistingReceipt stored,
      FederationPeerInboundAuthorization authorization,
      ExternalReference targetOrganization,
      FederatedAggregateSnapshot snapshot,
      UUID operationId,
      boolean replayed) {
    return new FederatedAggregateReceipt(
        stored.receiptId(),
        snapshot,
        operationId,
        targetOrganization,
        authorization.partnerKey(),
        stored.peerConfigurationVersion(),
        java.util.Optional.ofNullable(stored.mappingKey()),
        java.util.Optional.ofNullable(stored.mappingVersion()),
        stored.state(),
        stored.resultClass(),
        stored.receivedAt(),
        replayed);
  }

  private record ExistingReceipt(
      UUID receiptId,
      UUID operationId,
      String operationFingerprint,
      String contentFingerprint,
      String targetOrganizationId,
      FederatedAggregateReceipt.State state,
      String resultClass,
      String peerKey,
      int peerConfigurationVersion,
      String mappingKey,
      Integer mappingVersion,
      Instant receivedAt) {}
}
