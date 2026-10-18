package org.ayuvahak.analytics.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.ayuvahak.analytics.domain.AggregateDisclosureEquation;
import org.ayuvahak.analytics.domain.AggregateSnapshot;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.analytics.domain.DisclosureCandidate;
import org.ayuvahak.analytics.domain.DisclosureReservation;
import org.ayuvahak.analytics.domain.DisclosureReservationState;
import org.ayuvahak.analytics.domain.FederatedAggregateAcknowledgement;
import org.ayuvahak.analytics.domain.FederatedAggregateEnvelope;
import org.ayuvahak.analytics.domain.FederatedAggregateMappingVersion;
import org.ayuvahak.analytics.domain.FederatedAggregateMappingVersion.Direction;
import org.ayuvahak.analytics.domain.FederatedAggregatePublication;
import org.ayuvahak.analytics.domain.FederatedAggregatePublication.State;
import org.ayuvahak.analytics.domain.FederatedAggregateSnapshot;
import org.ayuvahak.analytics.domain.IndicatorDefinitionVersion;
import org.ayuvahak.platform.audit.application.AuditPersistenceUnavailableException;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.SourceKind;
import org.ayuvahak.platform.exchange.application.FederationOperationFingerprint;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationDisposition;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationException;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationPort;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationRequest;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.federation.application.DeploymentIdentityRegistrationService;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One-shot, disclosure-gated aggregate publication through Phase 9 and Phase 11 peer trust. */
@Service
public final class FederatedAggregatePublicationService {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final AllowedOperation OPERATION =
      AllowedOperation.analyticsAggregateSnapshot(
          org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction.OUTBOUND);

  private final AnalyticsStore snapshots;
  private final AnalyticsRevisionStore revisions;
  private final FederatedAggregateMappingStore mappings;
  private final FederatedAggregatePublicationStore publications;
  private final AnalyticsDisclosureReservationService disclosure;
  private final FederatedAggregateEnvelopeCodec mapper;
  private final FederationPeerOperationPort exchange;
  private final DeploymentIdentityRegistrationService deploymentIdentity;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final Set<UUID> activeAttempts = ConcurrentHashMap.newKeySet();
  private static final java.time.Duration STALE_SENDING_AFTER = java.time.Duration.ofMinutes(2);

  public FederatedAggregatePublicationService(
      AnalyticsStore snapshots,
      AnalyticsRevisionStore revisions,
      FederatedAggregateMappingStore mappings,
      FederatedAggregatePublicationStore publications,
      AnalyticsDisclosureReservationService disclosure,
      FederatedAggregateEnvelopeCodec mapper,
      FederationPeerOperationPort exchange,
      DeploymentIdentityRegistrationService deploymentIdentity,
      AuthorizationService authorization,
      AuditWriter audit,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.snapshots = Objects.requireNonNull(snapshots, "Analytics snapshot store");
    this.revisions = Objects.requireNonNull(revisions, "Analytics revision store");
    this.mappings = Objects.requireNonNull(mappings, "aggregate federation mappings");
    this.publications = Objects.requireNonNull(publications, "aggregate publication store");
    this.disclosure = Objects.requireNonNull(disclosure, "shared disclosure gate");
    this.mapper = Objects.requireNonNull(mapper, "aggregate envelope mapper");
    this.exchange = Objects.requireNonNull(exchange, "Phase 9 federation exchange");
    this.deploymentIdentity = Objects.requireNonNull(deploymentIdentity, "deployment identity");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public FederatedAggregatePublication publish(
      ExecutionContext context, FederatedAggregatePublicationRequest request) {
    Objects.requireNonNull(request, "publication request");
    AggregateSnapshot snapshot =
        snapshots
            .findSnapshotById(request.snapshotId())
            .orElseThrow(
                () ->
                    new FederatedAggregateException(
                        FederatedAggregateException.Reason.SNAPSHOT_NOT_FOUND));
    requirePublicationAuthorization(context, snapshot);
    IndicatorDefinitionVersion definition =
        snapshots
            .findDefinition(snapshot.indicatorKey(), snapshot.indicatorVersion())
            .orElseThrow(
                () ->
                    new FederatedAggregateException(
                        FederatedAggregateException.Reason.DEFINITION_INCOMPATIBLE));
    Instant now = clock.instant();
    FederatedAggregateMappingVersion mapping =
        mappings
            .activeByKey(request.mappingKey(), now)
            .filter(value -> value.direction() == Direction.OUTBOUND)
            .orElseThrow(
                () ->
                    new FederatedAggregateException(
                        FederatedAggregateException.Reason.MAPPING_NOT_EFFECTIVE));
    if (!mapping.indicatorKey().equals(snapshot.indicatorKey())
        || mapping.indicatorVersion() != snapshot.indicatorVersion()
        || !mapping.sourceOrganization().equals(snapshot.organization())
        || !mapping.definitionFingerprint().equals(definition.fingerprint())
        || !definition.governance().effectiveFor(snapshot.period())
        || !definition.governance().calendarAccepts(snapshot.period())
        || definition
            .governance()
            .requiredDisclosurePolicyKey()
            .filter(key -> !key.equals(mapping.disclosurePolicyKey()))
            .isPresent()) {
      throw new FederatedAggregateException(
          FederatedAggregateException.Reason.DEFINITION_INCOMPATIBLE);
    }
    DeploymentId localDeployment = localDeployment();
    FederatedAggregateSnapshot wireSnapshot;
    var revision =
        revisions
            .currentRevision(snapshot.snapshotId())
            .orElseThrow(
                () ->
                    new FederatedAggregateException(
                        FederatedAggregateException.Reason.SNAPSHOT_NOT_CURRENT));
    if (!revision.snapshotId().equals(snapshot.snapshotId())) {
      throw new FederatedAggregateException(
          FederatedAggregateException.Reason.SNAPSHOT_NOT_CURRENT);
    }
    try {
      wireSnapshot =
          FederatedAggregateSnapshot.from(snapshot, definition, revision, localDeployment);
    } catch (IllegalArgumentException invalid) {
      throw new FederatedAggregateException(FederatedAggregateException.Reason.INCOMPLETE_SNAPSHOT);
    }
    byte[] body =
        mapper.encode(
            new FederatedAggregateEnvelope(wireSnapshot, mapping.peerOrganizationScope()));
    String fingerprint = operationFingerprint(wireSnapshot, mapping);
    FederatedAggregatePublication proposed =
        new FederatedAggregatePublication(
            UUID.randomUUID(),
            fingerprint,
            localDeployment,
            mapping.peerDeployment(),
            snapshot.snapshotId(),
            revision.seriesId(),
            revision.revisionNumber(),
            mapping.mappingKey(),
            mapping.version(),
            mapping.partnerKey(),
            mapping.partnerConfigurationVersion(),
            definition.fingerprint(),
            wireSnapshot.contentFingerprint(),
            Optional.empty(),
            State.READY,
            "READY",
            Optional.empty(),
            now,
            now);
    FederatedAggregatePublication publication = createOrReplay(context, proposed);
    if (!activeAttempts.add(publication.operationId())) {
      return publications
          .find(publication.operationId())
          .orElseThrow(
              () ->
                  new FederatedAggregateException(
                      FederatedAggregateException.Reason.STORE_UNAVAILABLE));
    }
    try {
      return dispatch(
          context, publication, mapping, localDeployment, wireSnapshot, snapshot, definition, body);
    } finally {
      activeAttempts.remove(publication.operationId());
    }
  }

  private FederatedAggregatePublication dispatch(
      ExecutionContext context,
      FederatedAggregatePublication publication,
      FederatedAggregateMappingVersion mapping,
      DeploymentId localDeployment,
      FederatedAggregateSnapshot wireSnapshot,
      AggregateSnapshot snapshot,
      IndicatorDefinitionVersion definition,
      byte[] body) {
    if (publication.state() == State.READY) {
      DisclosureReservation reservation =
          reserveDisclosure(context, publication, mapping, snapshot, definition);
      if (reservation.state() != DisclosureReservationState.RESERVED) {
        return transition(
            context,
            publication,
            State.WITHHELD,
            "DISCLOSURE_" + reservation.state().name(),
            Optional.empty());
      }
      publication = attachReservation(publication, reservation.reservationId());
      Optional<FederatedAggregatePublication> claimed =
          publications.claimForSend(publication.operationId(), clock.instant());
      if (claimed.isEmpty()) {
        return latest(publication.operationId());
      }
      publication = claimed.orElseThrow();
    } else if (publication.state() == State.SENDING) {
      if (publication.updatedAt().plus(STALE_SENDING_AFTER).isAfter(clock.instant())) {
        return publication;
      }
      if (publication.disclosureReservationId().isEmpty()) {
        throw new FederatedAggregateException(
            FederatedAggregateException.Reason.OPERATION_CONFLICT);
      }
      publication =
          completePotentiallyDisclosed(
              context,
              publication,
              State.OUTCOME_UNKNOWN,
              "SENDING_RECOVERED_AFTER_RESTART",
              Optional.empty());
    } else if (publication.state() != State.OUTCOME_UNKNOWN) {
      return publication;
    }

    if (publication.disclosureReservationId().isEmpty()) {
      throw new FederatedAggregateException(FederatedAggregateException.Reason.OPERATION_CONFLICT);
    }
    return sendSameOperation(context, publication, mapping, localDeployment, wireSnapshot, body);
  }

  private FederatedAggregatePublication sendSameOperation(
      ExecutionContext context,
      FederatedAggregatePublication publication,
      FederatedAggregateMappingVersion mapping,
      DeploymentId localDeployment,
      FederatedAggregateSnapshot wireSnapshot,
      byte[] body) {
    try {
      var response =
          exchange.execute(
              new FederationPeerOperationRequest(
                  mapping.partnerKey(),
                  localDeployment,
                  mapping.peerDeployment(),
                  mapping.peerOrganizationScope(),
                  OPERATION,
                  new InternalId(publication.operationId()),
                  mapping.partnerConfigurationVersion(),
                  body,
                  FederationOperationFingerprint.calculate(
                      localDeployment,
                      mapping.peerDeployment(),
                      mapping.peerOrganizationScope(),
                      OPERATION,
                      body),
                  Optional.of(context.correlationId())));
      if (response.configurationVersion() != mapping.partnerConfigurationVersion()
          || response.contentType().filter("application/json"::equals).isEmpty()) {
        return completePotentiallyDisclosed(
            context,
            publication,
            State.OUTCOME_UNKNOWN,
            "ACKNOWLEDGEMENT_UNVERIFIED",
            Optional.empty());
      }
      FederatedAggregateAcknowledgement acknowledgement =
          mapper.decodeAcknowledgement(response.body());
      if (!acknowledgement.receivingDeployment().equals(mapping.peerDeployment())
          || !acknowledgement.operationId().equals(publication.operationId())
          || !acknowledgement.sourceSnapshotId().equals(wireSnapshot.sourceSnapshotId())
          || !acknowledgement.contentFingerprint().equals(wireSnapshot.contentFingerprint())) {
        return completePotentiallyDisclosed(
            context,
            publication,
            State.OUTCOME_UNKNOWN,
            "ACKNOWLEDGEMENT_MISMATCH",
            Optional.empty());
      }
      State state =
          acknowledgement.state()
                  == org.ayuvahak.analytics.domain.FederatedAggregateReceipt.State.COMBINABLE
              ? State.ACKNOWLEDGED
              : State.INCOMPATIBLE;
      return completeDisclosed(
          context,
          publication,
          state,
          state == State.ACKNOWLEDGED ? "REMOTE_RECEIPT_COMBINABLE" : "REMOTE_RECEIPT_INCOMPATIBLE",
          acknowledgement.receiptId());
    } catch (FederationPeerOperationException failure) {
      State state =
          failure.disposition() == FederationPeerOperationDisposition.REMOTE_REJECTED
              ? State.REJECTED
              : State.OUTCOME_UNKNOWN;
      return completePotentiallyDisclosed(
          context, publication, state, "PEER_" + failure.failure().name(), Optional.empty());
    } catch (RuntimeException uncertain) {
      return completePotentiallyDisclosed(
          context, publication, State.OUTCOME_UNKNOWN, "PEER_OUTCOME_UNKNOWN", Optional.empty());
    }
  }

  private FederatedAggregatePublication latest(UUID operationId) {
    return publications
        .find(operationId)
        .orElseThrow(
            () ->
                new FederatedAggregateException(
                    FederatedAggregateException.Reason.STORE_UNAVAILABLE));
  }

  private DisclosureReservation reserveDisclosure(
      ExecutionContext context,
      FederatedAggregatePublication publication,
      FederatedAggregateMappingVersion mapping,
      AggregateSnapshot snapshot,
      IndicatorDefinitionVersion definition) {
    int policyVersion =
        disclosure.activePolicyVersionForRelease(context, mapping.disclosurePolicyKey());
    AggregateDisclosureEquation equation;
    try {
      equation = AggregateDisclosureEquation.from(snapshot, definition);
    } catch (IllegalArgumentException | ArithmeticException invalidEquation) {
      throw new FederatedAggregateException(
          FederatedAggregateException.Reason.INCOMPLETE_SNAPSHOT, invalidEquation);
    }
    DisclosureCandidate candidate =
        new DisclosureCandidate(
            mapping.disclosurePolicyKey(),
            policyVersion,
            publication.operationId(),
            equation.cellValues(),
            List.of(equation.output(mapping.disclosureOutputKey())));
    return disclosure.reserve(context, candidate);
  }

  private FederatedAggregatePublication createOrReplay(
      ExecutionContext context, FederatedAggregatePublication proposed) {
    try {
      return transactions.execute(
          status -> {
            FederatedAggregatePublicationStore.CreateResult result =
                publications.createOrReplay(proposed);
            if (!result.created()) {
              return result.publication();
            }
            InternalId auditRecordId =
                audit.recordMutation(
                    mutationEvidence(
                        context,
                        "ANALYTICS_FEDERATED_AGGREGATE_PREPARED",
                        proposed.operationId(),
                        "READY"));
            publications.attachAudit(proposed.operationId(), auditRecordId.value());
            return result.publication();
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException unavailable) {
      throw new FederatedAggregateException(
          FederatedAggregateException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private FederatedAggregatePublication attachReservation(
      FederatedAggregatePublication publication, UUID reservationId) {
    return transactions.execute(
        status ->
            publications.attachDisclosureReservation(
                publication.operationId(), reservationId, clock.instant()));
  }

  private FederatedAggregatePublication completeDisclosed(
      ExecutionContext context,
      FederatedAggregatePublication publication,
      State next,
      String result,
      UUID receiptId) {
    try {
      return transactions.execute(
          status -> {
            publication
                .disclosureReservationId()
                .ifPresent(id -> disclosure.markDisclosed(context, id));
            return transitionInTransaction(
                context, publication, next, result, Optional.of(receiptId));
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException unavailable) {
      throw new FederatedAggregateException(
          FederatedAggregateException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private FederatedAggregatePublication completePotentiallyDisclosed(
      ExecutionContext context,
      FederatedAggregatePublication publication,
      State next,
      String result,
      Optional<UUID> receiptId) {
    try {
      return transactions.execute(
          status -> {
            publication
                .disclosureReservationId()
                .ifPresent(id -> disclosure.markPotentiallyDisclosed(context, id));
            return transitionInTransaction(context, publication, next, result, receiptId);
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException unavailable) {
      throw new FederatedAggregateException(
          FederatedAggregateException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private FederatedAggregatePublication transition(
      ExecutionContext context,
      FederatedAggregatePublication publication,
      State next,
      String result,
      Optional<UUID> receiptId) {
    try {
      return transactions.execute(
          status -> transitionInTransaction(context, publication, next, result, receiptId));
    } catch (DataAccessException | AuditPersistenceUnavailableException unavailable) {
      throw new FederatedAggregateException(
          FederatedAggregateException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private FederatedAggregatePublication transitionInTransaction(
      ExecutionContext context,
      FederatedAggregatePublication publication,
      State next,
      String result,
      Optional<UUID> receiptId) {
    FederatedAggregatePublication changed =
        publications.transition(
            publication.operationId(),
            publication.state(),
            next,
            result,
            receiptId,
            clock.instant());
    audit.recordMutation(
        mutationEvidence(
            context,
            "ANALYTICS_FEDERATED_AGGREGATE_" + next.name(),
            publication.operationId(),
            result));
    return changed;
  }

  private void requirePublicationAuthorization(
      ExecutionContext context, AggregateSnapshot snapshot) {
    UUID targetId = snapshot.snapshotId();
    if (context == null
        || context.actor().kind() != ActorKind.SERVICE
        || !(context.sourceKind() == SourceKind.INTEGRATION
            || context.sourceKind() == SourceKind.BACKGROUND_WORK
            || context.sourceKind() == SourceKind.SCHEDULED_WORK)
        || context.scope().kind() != ExecutionScope.Kind.ORGANIZATION
        || context.scope().organization().filter(snapshot.organization()::equals).isEmpty()
        || !DIRECTORY_ISSUER.equals(snapshot.organization().issuer().value())
        || !authorization
            .evaluate(context, AnalyticsAuthorities.federatedAggregatePublication().code())
            .isAllowed()) {
      recordDenial(context, targetId);
      throw new FederatedAggregateException(FederatedAggregateException.Reason.NOT_AUTHORIZED);
    }
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

  private void recordDenial(ExecutionContext context, UUID targetId) {
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.ofNullable(context).map(ExecutionContext::actor),
              Optional.ofNullable(context).map(ExecutionContext::scope),
              "ANALYTICS_FEDERATED_AGGREGATE_PUBLICATION_DENIED",
              "FederatedAggregatePublication",
              Optional.of(new InternalId(targetId)),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of("AUTHORIZATION_OR_STATE_DENIED"),
              authenticationEvidence(context),
              context == null
                  ? AuditAuthorizationOutcome.NOT_EVALUATED
                  : AuditAuthorizationOutcome.DENIED,
              context == null ? new CorrelationId(targetId.toString()) : context.correlationId()));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new FederatedAggregateException(
          FederatedAggregateException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private AuditEvidence mutationEvidence(
      ExecutionContext context, String action, UUID operationId, String reason) {
    return new AuditEvidence(
        AuditActionKind.PROTECTED_MUTATION,
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        action,
        "FederatedAggregatePublication",
        Optional.of(new InternalId(operationId)),
        clock.instant(),
        AuditOutcome.SUCCEEDED,
        Optional.of(reason),
        authenticationEvidence(context),
        AuditAuthorizationOutcome.ALLOWED,
        context.correlationId());
  }

  private static AuditAuthenticationEvidence authenticationEvidence(ExecutionContext context) {
    if (context == null) {
      return AuditAuthenticationEvidence.NONE;
    }
    return switch (context.authenticationMechanism()) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }

  private static String operationFingerprint(
      FederatedAggregateSnapshot snapshot, FederatedAggregateMappingVersion mapping) {
    String canonical =
        String.join(
            "\n",
            snapshot.sourceDeployment().value(),
            mapping.peerDeployment().value(),
            snapshot.sourceSnapshotId().toString(),
            snapshot.contentFingerprint(),
            mapping.fingerprint(),
            Integer.toString(mapping.partnerConfigurationVersion()));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }
}
