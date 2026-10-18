package org.ayuvahak.analytics.application;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.domain.FederatedAggregateEnvelope;
import org.ayuvahak.analytics.domain.FederatedAggregateMappingVersion;
import org.ayuvahak.analytics.domain.FederatedAggregateMappingVersion.Direction;
import org.ayuvahak.analytics.domain.FederatedAggregateReceipt;
import org.ayuvahak.analytics.domain.FederatedAggregateSnapshot;
import org.ayuvahak.platform.audit.application.AuditPersistenceUnavailableException;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.application.FederationOperationFingerprint;
import org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorization;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.ayuvahak.platform.federation.application.DeploymentIdentityRegistrationService;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Validates and records only the named aggregate snapshot received from a trusted peer. */
@Service
public final class FederatedAggregateReceiptService {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";

  private final FederatedAggregateMappingStore mappings;
  private final FederatedAggregateReceiptStore receipts;
  private final AnalyticsStore analytics;
  private final DeploymentIdentityRegistrationService deploymentIdentity;
  private final AuditWriter audit;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public FederatedAggregateReceiptService(
      FederatedAggregateMappingStore mappings,
      FederatedAggregateReceiptStore receipts,
      AnalyticsStore analytics,
      DeploymentIdentityRegistrationService deploymentIdentity,
      AuditWriter audit,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.mappings = Objects.requireNonNull(mappings, "aggregate federation mappings");
    this.receipts = Objects.requireNonNull(receipts, "aggregate receipt store");
    this.analytics = Objects.requireNonNull(analytics, "Analytics store");
    this.deploymentIdentity = Objects.requireNonNull(deploymentIdentity, "deployment identity");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public FederatedAggregateReceipt receive(
      FederationPeerInboundAuthorization peer,
      UUID operationId,
      FederationOperationFingerprint operationFingerprint,
      FederatedAggregateEnvelope envelope) {
    Objects.requireNonNull(peer, "authorized peer");
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(operationFingerprint, "operation fingerprint");
    Objects.requireNonNull(envelope, "aggregate envelope");
    requireAuthorized(peer, operationId);
    FederatedAggregateSnapshot snapshot = envelope.snapshot();
    DeploymentId localDeployment = localDeployment();
    if (!snapshot.sourceDeployment().equals(peer.peerDeployment())) {
      throw new FederatedAggregateException(FederatedAggregateException.Reason.NOT_AUTHORIZED);
    }
    ExternalReference localOrganization =
        localOrganization(envelope.targetOrganization(), localDeployment);
    if (!peer.scope().equals(localOrganization)) {
      throw new FederatedAggregateException(FederatedAggregateException.Reason.NOT_AUTHORIZED);
    }
    java.time.Instant now = clock.instant();
    FederatedAggregateMappingVersion mapping =
        mappings
            .activeMapping(
                Direction.INBOUND,
                snapshot.indicatorKey(),
                snapshot.indicatorVersion(),
                snapshot.sourceOrganization(),
                peer.peerDeployment(),
                localOrganization,
                now)
            .filter(value -> value.partnerKey().equals(peer.partnerKey()))
            .filter(value -> value.partnerConfigurationVersion() == peer.profileVersion())
            .orElseThrow(
                () ->
                    new FederatedAggregateException(
                        FederatedAggregateException.Reason.MAPPING_NOT_FOUND));
    FederatedAggregateReceipt.State state =
        compatibility(snapshot, mapping)
            ? FederatedAggregateReceipt.State.COMBINABLE
            : FederatedAggregateReceipt.State.INCOMPATIBLE;
    String resultClass =
        state == FederatedAggregateReceipt.State.COMBINABLE ? "COMPATIBLE" : "INCOMPATIBLE";
    try {
      return transactions.execute(
          status -> {
            FederatedAggregateReceiptStore.RecordResult result =
                receipts.recordOrReplay(
                    peer,
                    localOrganization,
                    snapshot,
                    operationId,
                    operationFingerprint,
                    state,
                    resultClass,
                    mapping.mappingKey(),
                    mapping.version(),
                    now);
            if (result.created()) {
              InternalId auditRecordId =
                  audit.recordMutation(
                      receiptEvidence(peer, operationId, result.receipt(), resultClass));
              receipts.attachAudit(result.receipt().receiptId(), auditRecordId.value());
            }
            return result.receipt();
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException unavailable) {
      throw new FederatedAggregateException(
          FederatedAggregateException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private boolean compatibility(
      FederatedAggregateSnapshot snapshot, FederatedAggregateMappingVersion mapping) {
    if (!snapshot.definitionFingerprint().equals(mapping.definitionFingerprint())) {
      return false;
    }
    return analytics
        .findDefinition(snapshot.indicatorKey(), snapshot.indicatorVersion())
        .filter(definition -> definition.fingerprint().equals(mapping.definitionFingerprint()))
        .filter(definition -> definition.fingerprint().equals(snapshot.definitionFingerprint()))
        .filter(
            definition ->
                FederatedAggregateSnapshot.attribution(definition) == snapshot.attribution())
        .isPresent();
  }

  private void requireAuthorized(FederationPeerInboundAuthorization peer, UUID operationId) {
    if (peer.authenticatedPeerPrincipal().kind() != ActorKind.SERVICE
        || !peer.operation()
            .equals(
                PartnerTrustConfiguration.AllowedOperation.analyticsAggregateSnapshot(
                    PartnerTrustConfiguration.Direction.INBOUND))) {
      throw new FederatedAggregateException(FederatedAggregateException.Reason.NOT_AUTHORIZED);
    }
  }

  private ExternalReference localOrganization(
      ExternalReference remoteOrganization, DeploymentId localDeployment) {
    if (!remoteOrganization
            .issuer()
            .value()
            .equals(FederatedAggregateMappingVersion.deploymentNamespace(localDeployment))
        || !"Organization".equals(remoteOrganization.resourceType())) {
      throw new FederatedAggregateException(FederatedAggregateException.Reason.NOT_AUTHORIZED);
    }
    return new ExternalReference(
        new IssuerId(DIRECTORY_ISSUER), "Organization", remoteOrganization.value());
  }

  private DeploymentId localDeployment() {
    try {
      return deploymentIdentity
          .current()
          .map(identity -> identity.deploymentId())
          .orElseThrow(
              () ->
                  new FederatedAggregateException(
                      FederatedAggregateException.Reason.STORE_UNAVAILABLE));
    } catch (DataAccessException unavailable) {
      throw new FederatedAggregateException(
          FederatedAggregateException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private AuditEvidence receiptEvidence(
      FederationPeerInboundAuthorization peer,
      UUID operationId,
      FederatedAggregateReceipt receipt,
      String resultClass) {
    return new AuditEvidence(
        AuditActionKind.PROTECTED_MUTATION,
        Optional.of(peer.authenticatedPeerPrincipal()),
        Optional.empty(),
        "ANALYTICS_FEDERATED_AGGREGATE_RECEIVED",
        "FederatedAggregateReceipt",
        Optional.of(new InternalId(receipt.receiptId())),
        clock.instant(),
        AuditOutcome.SUCCEEDED,
        Optional.of(resultClass),
        AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN,
        AuditAuthorizationOutcome.ALLOWED,
        new CorrelationId(operationId.toString()));
  }
}
