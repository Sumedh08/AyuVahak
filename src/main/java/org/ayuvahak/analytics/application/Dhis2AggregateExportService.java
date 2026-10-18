package org.ayuvahak.analytics.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.ayuvahak.analytics.domain.AggregateDisclosureEquation;
import org.ayuvahak.analytics.domain.AggregateSnapshot;
import org.ayuvahak.analytics.domain.AggregateSnapshotRevision;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.analytics.domain.Dhis2DataValueTuple;
import org.ayuvahak.analytics.domain.Dhis2ExportOperation;
import org.ayuvahak.analytics.domain.Dhis2ExportState;
import org.ayuvahak.analytics.domain.Dhis2MappingVersion;
import org.ayuvahak.analytics.domain.DisclosureCandidate;
import org.ayuvahak.analytics.domain.DisclosureReservation;
import org.ayuvahak.analytics.domain.DisclosureReservationState;
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
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationPort;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationRequest;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationResponse;
import org.ayuvahak.platform.exchange.application.PartnerTransportException;
import org.ayuvahak.platform.exchange.application.PartnerTransportFailure;
import org.ayuvahak.platform.exchange.application.PartnerTrustLookupUnavailableException;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One-value synchronous aggregate delivery through the existing Phase 9 secure exchange. */
@Service
public final class Dhis2AggregateExportService {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final AllowedOperation DHIS2_OPERATION =
      AllowedOperation.analyticsDhis2DataValueSet();

  private final AnalyticsStore snapshots;
  private final AnalyticsRevisionStore revisions;
  private final Dhis2MappingStore mappings;
  private final Dhis2ExportOperationStore operations;
  private final AnalyticsDisclosureReservationService disclosure;
  private final Dhis2DataValueSetPayloadMapper payloadMapper;
  private final Dhis2ImportSummaryResponseParser summaryParser;
  private final PartnerOutboundOperationPort exchange;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final Set<UUID> activeAttempts = ConcurrentHashMap.newKeySet();

  public Dhis2AggregateExportService(
      AnalyticsStore snapshots,
      AnalyticsRevisionStore revisions,
      Dhis2MappingStore mappings,
      Dhis2ExportOperationStore operations,
      AnalyticsDisclosureReservationService disclosure,
      Dhis2DataValueSetPayloadMapper payloadMapper,
      Dhis2ImportSummaryResponseParser summaryParser,
      PartnerOutboundOperationPort exchange,
      AuthorizationService authorization,
      AuditWriter audit,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.snapshots = Objects.requireNonNull(snapshots, "Analytics snapshot store");
    this.revisions = Objects.requireNonNull(revisions, "Analytics revision store");
    this.mappings = Objects.requireNonNull(mappings, "DHIS2 mapping store");
    this.operations = Objects.requireNonNull(operations, "DHIS2 operation store");
    this.disclosure = Objects.requireNonNull(disclosure, "disclosure reservation service");
    this.payloadMapper = Objects.requireNonNull(payloadMapper, "DataValueSet mapper");
    this.summaryParser = Objects.requireNonNull(summaryParser, "import summary parser");
    this.exchange = Objects.requireNonNull(exchange, "Phase 9 outbound exchange");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public Dhis2ExportOperation export(ExecutionContext context, Dhis2ExportRequest request) {
    Objects.requireNonNull(request, "export request");
    AggregateSnapshot snapshot = requireSnapshot(request.snapshotId());
    authorizeExport(context, snapshot, request.operationId());
    if (snapshot.completeness() != org.ayuvahak.analytics.domain.AggregateCompleteness.COMPLETE
        || snapshot.value().isEmpty()) {
      throw new Dhis2ExportException(Dhis2ExportException.Reason.INCOMPLETE_SNAPSHOT);
    }
    AggregateSnapshotRevision current =
        revisions
            .currentRevision(snapshot.snapshotId())
            .orElseThrow(
                () -> new Dhis2ExportException(Dhis2ExportException.Reason.SNAPSHOT_NOT_CURRENT));
    if (!current.snapshotId().equals(snapshot.snapshotId())) {
      throw new Dhis2ExportException(Dhis2ExportException.Reason.SNAPSHOT_NOT_CURRENT);
    }
    Instant now = clock.instant();
    Dhis2MappingVersion mapping =
        mappings
            .activeMapping(
                snapshot.indicatorKey(), snapshot.indicatorVersion(), snapshot.organization(), now)
            .orElseThrow(
                () -> new Dhis2ExportException(Dhis2ExportException.Reason.MAPPING_NOT_FOUND));
    if (!mapping.exclusiveWriteAuthority()) {
      throw new Dhis2ExportException(Dhis2ExportException.Reason.MAPPING_NOT_EFFECTIVE);
    }
    Dhis2DataValueTuple tuple;
    byte[] body;
    try {
      tuple = mapping.tupleFor(snapshot.period());
      body = payloadMapper.map(snapshot, mapping);
    } catch (IllegalArgumentException invalidMappingOrSnapshot) {
      if (snapshot.value().orElse(0) == 0 && !mapping.zeroIsSignificant()) {
        throw new Dhis2ExportException(Dhis2ExportException.Reason.ZERO_NOT_ALLOWED);
      }
      throw new Dhis2ExportException(Dhis2ExportException.Reason.MAPPING_NOT_EFFECTIVE);
    }
    AggregateDisclosureEquation cellEquation = sourceCellEquation(snapshot);

    String fingerprint = operationFingerprint(snapshot, current, mapping, tuple);
    Optional<Dhis2ExportOperation> existing = operations.find(request.operationId());
    Dhis2ExportOperation operation;
    if (existing.isPresent()) {
      operation = existing.orElseThrow();
      if (!operation.fingerprint().equals(fingerprint)) {
        throw new Dhis2ExportOperationConflictException();
      }
      if (operation.state() == Dhis2ExportState.PREPARED
          && !operation
              .partnerConfigurationVersion()
              .equals(Optional.of(activePartnerConfigurationVersion(mapping)))) {
        throw new Dhis2ExportOperationConflictException();
      }
    } else {
      int partnerConfigurationVersion = activePartnerConfigurationVersion(mapping);
      Dhis2ExportOperation proposed =
          new Dhis2ExportOperation(
              request.operationId(),
              fingerprint,
              snapshot.snapshotId(),
              current.seriesId(),
              current.revisionNumber(),
              mapping.mappingKey(),
              mapping.version(),
              mapping.partnerKey().value(),
              Optional.of(partnerConfigurationVersion),
              tuple,
              mapping.dataSetUid(),
              Optional.empty(),
              Dhis2ExportState.PREPARED,
              "PREPARED",
              0,
              Optional.empty(),
              Optional.empty(),
              now,
              now);
      operation = createOrReplay(context, proposed);
      if (!operation
          .partnerConfigurationVersion()
          .equals(Optional.of(partnerConfigurationVersion))) {
        throw new Dhis2ExportOperationConflictException();
      }
    }
    if (operation.state() == Dhis2ExportState.SENDING) {
      if (activeAttempts.contains(operation.operationId())) {
        return operation;
      }
      return markUnknownAfterRestart(context, operation);
    }
    if (operation.state() != Dhis2ExportState.PREPARED) {
      return operation;
    }
    if (!activeAttempts.add(operation.operationId())) {
      return operations.find(operation.operationId()).orElse(operation);
    }
    StartResult start;
    try {
      start = claimReserveAndStart(context, mapping, operation, snapshot, cellEquation);
    } catch (RuntimeException failure) {
      activeAttempts.remove(operation.operationId());
      throw failure;
    }
    operation = start.operation();
    if (!start.started()) {
      activeAttempts.remove(operation.operationId());
      return operation;
    }
    try {
      PartnerOutboundOperationResponse response;
      Dhis2ImportSummary summary;
      try {
        response =
            exchange.execute(
                new PartnerOutboundOperationRequest(
                    mapping.partnerKey(),
                    DHIS2_OPERATION,
                    java.util.List.of("dataValueSets"),
                    body,
                    "dhis2-" + request.operationId(),
                    Optional.of(context.correlationId()),
                    Optional.of(operation.partnerConfigurationVersion().orElseThrow())));
        summary = summaryParser.parse(response.status(), response.contentType(), response.body());
      } catch (Dhis2ImportResponseException malformed) {
        return completePotentiallyDisclosed(
            context, operation, Dhis2ExportState.OUTCOME_UNKNOWN, "MALFORMED_RESPONSE", null, null);
      } catch (PartnerTransportException transportFailure) {
        MappedFailure mapped = classify(transportFailure.failure());
        return completePotentiallyDisclosed(
            context, operation, mapped.state(), mapped.resultClass(), null, null);
      } catch (RuntimeException uncertain) {
        return completePotentiallyDisclosed(
            context,
            operation,
            Dhis2ExportState.OUTCOME_UNKNOWN,
            "EXCHANGE_OUTCOME_UNKNOWN",
            null,
            null);
      }
      return switch (summary.outcome()) {
        case IMPORTED_ACKNOWLEDGED, UPDATED_ACKNOWLEDGED ->
            completeAcknowledged(context, operation, summary, response.configurationVersion());
        case IGNORED_AMBIGUOUS ->
            completePotentiallyDisclosed(
                context,
                operation,
                Dhis2ExportState.RECONCILIATION_REQUIRED,
                "IMPORT_IGNORED_AMBIGUOUS",
                summary,
                response.configurationVersion());
        case UNSUPPORTED_ASYNC ->
            completePotentiallyDisclosed(
                context,
                operation,
                Dhis2ExportState.RECONCILIATION_REQUIRED,
                "ASYNC_PROFILE_UNSUPPORTED",
                summary,
                response.configurationVersion());
      };
    } finally {
      activeAttempts.remove(operation.operationId());
    }
  }

  private int activePartnerConfigurationVersion(Dhis2MappingVersion mapping) {
    try {
      return exchange.activeConfigurationVersion(mapping.partnerKey(), DHIS2_OPERATION);
    } catch (PartnerTransportException | PartnerTrustLookupUnavailableException unavailable) {
      throw new Dhis2ExportException(
          Dhis2ExportException.Reason.TARGET_PROFILE_UNAVAILABLE, unavailable);
    }
  }

  private StartResult claimReserveAndStart(
      ExecutionContext context,
      Dhis2MappingVersion mapping,
      Dhis2ExportOperation operation,
      AggregateSnapshot snapshot,
      AggregateDisclosureEquation cellEquation) {
    try {
      return transactions.execute(
          status -> {
            Dhis2ExportOperationStore.TargetTupleClaimResult claim =
                operations.acquireTargetTupleClaim(operation, clock.instant());
            switch (claim.outcome()) {
              case HELD_BY_OTHER -> {
                return new StartResult(
                    transition(
                        context,
                        operation,
                        Dhis2ExportState.PREPARED,
                        "TARGET_TUPLE_ACTIVE",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                    false);
              }
              case BLOCKED_BY_OLDER -> {
                return new StartResult(
                    transition(
                        context,
                        operation,
                        Dhis2ExportState.PREPARED,
                        "OLDER_REVISION_PENDING",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                    false);
              }
              case STALE_REVISION, CONFLICTING_SERIES -> {
                return new StartResult(
                    transition(
                        context,
                        operation,
                        Dhis2ExportState.REJECTED,
                        claim.outcome()
                                == Dhis2ExportOperationStore.TargetTupleClaimOutcome.STALE_REVISION
                            ? "STALE_OR_DUPLICATE_REVISION"
                            : "TARGET_TUPLE_SERIES_CONFLICT",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                    false);
              }
              case SAME_OPERATION -> {
                return new StartResult(
                    operations.find(operation.operationId()).orElse(operation), false);
              }
              case ACQUIRED -> {
                // Continue in the same transaction so disclosure and send ownership commit
                // together.
              }
            }

            Optional<DisclosureReservation> reservationResult =
                reserveDisclosure(context, mapping, operation, snapshot, cellEquation);
            if (reservationResult.isEmpty()) {
              return new StartResult(
                  transition(
                      context,
                      operation,
                      Dhis2ExportState.WITHHELD,
                      "DISCLOSURE_OVERLAP_REVIEW_REQUIRED",
                      Optional.empty(),
                      Optional.empty(),
                      Optional.empty()),
                  false);
            }
            DisclosureReservation reservation = reservationResult.orElseThrow();
            if (reservation.state() != DisclosureReservationState.RESERVED) {
              return new StartResult(
                  transition(
                      context,
                      operation,
                      Dhis2ExportState.WITHHELD,
                      "DISCLOSURE_" + reservation.state().name(),
                      Optional.empty(),
                      Optional.empty(),
                      Optional.of(reservation.reservationId())),
                  false);
            }
            Dhis2ExportOperation withReservation =
                transition(
                    context,
                    operation,
                    Dhis2ExportState.PREPARED,
                    "DISCLOSURE_RESERVED",
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(reservation.reservationId()));
            Dhis2ExportOperation sending =
                transition(
                    context,
                    withReservation,
                    Dhis2ExportState.SENDING,
                    "SEND_STARTED",
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(reservation.reservationId()));
            return new StartResult(sending, true);
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException unavailable) {
      throw new Dhis2ExportException(Dhis2ExportException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private Dhis2ExportOperation createOrReplay(
      ExecutionContext context, Dhis2ExportOperation proposed) {
    try {
      return transactions.execute(
          status -> {
            Dhis2ExportOperationStore.CreateResult result = operations.createOrReplay(proposed);
            if (!result.created()) {
              return result.operation();
            }
            InternalId auditId =
                audit.recordMutation(
                    mutationEvidence(
                        context,
                        "ANALYTICS_DHIS2_EXPORT_PREPARED",
                        proposed.operationId(),
                        "PREPARED"));
            return operations.update(
                proposed.operationId(),
                Dhis2ExportState.PREPARED,
                Dhis2ExportState.PREPARED,
                "PREPARED",
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                clock.instant(),
                auditId.value());
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException unavailable) {
      throw new Dhis2ExportException(Dhis2ExportException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private Optional<DisclosureReservation> reserveDisclosure(
      ExecutionContext context,
      Dhis2MappingVersion mapping,
      Dhis2ExportOperation operation,
      AggregateSnapshot snapshot,
      AggregateDisclosureEquation cellEquation) {
    try {
      return transactions.execute(
          status -> {
            if (operations.hasUnrepresentableOverlappingRelease(
                mapping, snapshot.period(), operation.operationId())) {
              return Optional.empty();
            }
            int policyVersion =
                disclosure.activePolicyVersionForRelease(context, mapping.disclosurePolicyKey());
            DisclosureCandidate candidate =
                disclosureCandidate(mapping, operation, cellEquation, policyVersion);
            return Optional.of(disclosure.reserve(context, candidate));
          });
    } catch (RuntimeException unavailable) {
      if (unavailable instanceof Dhis2ExportException exportFailure) {
        throw exportFailure;
      }
      if (unavailable instanceof DataAccessException) {
        throw new Dhis2ExportException(Dhis2ExportException.Reason.STORE_UNAVAILABLE, unavailable);
      }
      throw new Dhis2ExportException(
          Dhis2ExportException.Reason.DISCLOSURE_UNAVAILABLE, unavailable);
    }
  }

  private DisclosureCandidate disclosureCandidate(
      Dhis2MappingVersion mapping,
      Dhis2ExportOperation operation,
      AggregateDisclosureEquation cellEquation,
      int policyVersion) {
    return new DisclosureCandidate(
        mapping.disclosurePolicyKey(),
        policyVersion,
        operation.operationId(),
        cellEquation.cellValues(),
        java.util.List.of(cellEquation.output(mapping.disclosureOutputKey())));
  }

  private AggregateDisclosureEquation sourceCellEquation(AggregateSnapshot snapshot) {
    IndicatorDefinitionVersion definition =
        snapshots
            .findDefinition(snapshot.indicatorKey(), snapshot.indicatorVersion())
            .orElseThrow(
                () -> new Dhis2ExportException(Dhis2ExportException.Reason.SNAPSHOT_NOT_CURRENT));
    try {
      return AggregateDisclosureEquation.from(snapshot, definition);
    } catch (IllegalArgumentException | ArithmeticException invalidEquation) {
      throw new Dhis2ExportException(
          Dhis2ExportException.Reason.DISCLOSURE_UNAVAILABLE, invalidEquation);
    }
  }

  private Dhis2ExportOperation completeAcknowledged(
      ExecutionContext context,
      Dhis2ExportOperation operation,
      Dhis2ImportSummary summary,
      int configurationVersion) {
    try {
      return transactions.execute(
          status -> {
            Dhis2ExportOperation current =
                operations
                    .find(operation.operationId())
                    .orElseThrow(Dhis2ExportOperationConflictException::new);
            if (current.state() != Dhis2ExportState.SENDING
                && current.state() != Dhis2ExportState.OUTCOME_UNKNOWN) {
              throw new Dhis2ExportOperationConflictException();
            }
            InternalId auditId =
                audit.recordMutation(
                    mutationEvidence(
                        context,
                        "ANALYTICS_DHIS2_EXPORT_ACKNOWLEDGED",
                        operation.operationId(),
                        summary.outcome().name()));
            Dhis2ExportOperation completed =
                operations.update(
                    operation.operationId(),
                    current.state(),
                    Dhis2ExportState.ACKNOWLEDGED,
                    summary.outcome().name(),
                    1,
                    Optional.of(configurationVersion),
                    Optional.of(counts(summary)),
                    operation.disclosureReservationId(),
                    clock.instant(),
                    auditId.value());
            DisclosureReservation reservation =
                operation
                    .disclosureReservationId()
                    .map(id -> disclosure.markDisclosed(context, id))
                    .orElseThrow(
                        () ->
                            new Dhis2ExportException(
                                Dhis2ExportException.Reason.DISCLOSURE_UNAVAILABLE));
            if (reservation.state() != DisclosureReservationState.DISCLOSED) {
              throw new Dhis2ExportException(Dhis2ExportException.Reason.DISCLOSURE_UNAVAILABLE);
            }
            return completed;
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException unavailable) {
      throw new Dhis2ExportException(Dhis2ExportException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private Dhis2ExportOperation completePotentiallyDisclosed(
      ExecutionContext context,
      Dhis2ExportOperation operation,
      Dhis2ExportState state,
      String resultClass,
      Dhis2ImportSummary summary,
      Integer configurationVersion) {
    try {
      return transactions.execute(
          status -> {
            Dhis2ExportOperation current =
                operations
                    .find(operation.operationId())
                    .orElseThrow(Dhis2ExportOperationConflictException::new);
            if (current.state() != Dhis2ExportState.SENDING
                && current.state() != Dhis2ExportState.OUTCOME_UNKNOWN) {
              throw new Dhis2ExportOperationConflictException();
            }
            if (current.state() == state) {
              operation
                  .disclosureReservationId()
                  .ifPresent(id -> disclosure.markPotentiallyDisclosed(context, id));
              return current;
            }
            InternalId auditId =
                audit.recordMutation(
                    mutationEvidence(
                        context,
                        "ANALYTICS_DHIS2_EXPORT_" + resultClass,
                        operation.operationId(),
                        resultClass));
            Dhis2ExportOperation updated =
                operations.update(
                    operation.operationId(),
                    current.state(),
                    state,
                    resultClass,
                    1,
                    Optional.ofNullable(configurationVersion),
                    Optional.ofNullable(summary).map(Dhis2AggregateExportService::counts),
                    operation.disclosureReservationId(),
                    clock.instant(),
                    auditId.value());
            DisclosureReservation reservation =
                operation
                    .disclosureReservationId()
                    .map(id -> disclosure.markPotentiallyDisclosed(context, id))
                    .orElseThrow(
                        () ->
                            new Dhis2ExportException(
                                Dhis2ExportException.Reason.DISCLOSURE_UNAVAILABLE));
            if (reservation.state() != DisclosureReservationState.POTENTIALLY_DISCLOSED) {
              throw new Dhis2ExportException(Dhis2ExportException.Reason.DISCLOSURE_UNAVAILABLE);
            }
            return updated;
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException unavailable) {
      throw new Dhis2ExportException(Dhis2ExportException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private Dhis2ExportOperation markUnknownAfterRestart(
      ExecutionContext context, Dhis2ExportOperation operation) {
    try {
      return transactions.execute(
          status -> {
            Dhis2ExportOperation current =
                operations
                    .find(operation.operationId())
                    .orElseThrow(Dhis2ExportOperationConflictException::new);
            if (current.state() != Dhis2ExportState.SENDING) {
              if (current.state() == Dhis2ExportState.OUTCOME_UNKNOWN) {
                return current;
              }
              throw new Dhis2ExportOperationConflictException();
            }
            InternalId auditId =
                audit.recordMutation(
                    mutationEvidence(
                        context,
                        "ANALYTICS_DHIS2_EXPORT_RESTART_UNKNOWN",
                        operation.operationId(),
                        "SEND_STATE_SURVIVED_RESTART"));
            Dhis2ExportOperation updated =
                operations.update(
                    operation.operationId(),
                    current.state(),
                    Dhis2ExportState.OUTCOME_UNKNOWN,
                    "RESTART_DURING_SEND",
                    1,
                    Optional.empty(),
                    Optional.empty(),
                    operation.disclosureReservationId(),
                    clock.instant(),
                    auditId.value());
            operation
                .disclosureReservationId()
                .ifPresent(id -> disclosure.markPotentiallyDisclosed(context, id));
            return updated;
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException unavailable) {
      throw new Dhis2ExportException(Dhis2ExportException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private Dhis2ExportOperation transition(
      ExecutionContext context,
      Dhis2ExportOperation operation,
      Dhis2ExportState target,
      String resultClass,
      Optional<Integer> configurationVersion,
      Optional<Dhis2ExportOperation.Dhis2ImportCounts> importCounts,
      Optional<UUID> reservationId) {
    try {
      return transactions.execute(
          status -> {
            InternalId auditId =
                audit.recordMutation(
                    mutationEvidence(
                        context,
                        "ANALYTICS_DHIS2_EXPORT_" + resultClass,
                        operation.operationId(),
                        resultClass));
            return operations.update(
                operation.operationId(),
                operation.state(),
                target,
                resultClass,
                target == Dhis2ExportState.SENDING ? 1 : operation.attemptCount(),
                configurationVersion,
                importCounts,
                reservationId,
                clock.instant(),
                auditId.value());
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException unavailable) {
      throw new Dhis2ExportException(Dhis2ExportException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private AggregateSnapshot requireSnapshot(UUID snapshotId) {
    return snapshots
        .findSnapshotById(snapshotId)
        .orElseThrow(
            () -> new Dhis2ExportException(Dhis2ExportException.Reason.SNAPSHOT_NOT_FOUND));
  }

  private void authorizeExport(
      ExecutionContext context, AggregateSnapshot snapshot, UUID targetId) {
    if (context == null
        || context.actor().kind() != ActorKind.SERVICE
        || !(context.sourceKind() == SourceKind.INTEGRATION
            || context.sourceKind() == SourceKind.BACKGROUND_WORK
            || context.sourceKind() == SourceKind.SCHEDULED_WORK)
        || context.scope().kind() != ExecutionScope.Kind.ORGANIZATION
        || context.scope().organization().filter(snapshot.organization()::equals).isEmpty()
        || !DIRECTORY_ISSUER.equals(snapshot.organization().issuer().value())
        || !authorization
            .evaluate(context, AnalyticsAuthorities.dhis2Export().code())
            .isAllowed()) {
      recordDenial(context, targetId);
      throw new Dhis2ExportException(Dhis2ExportException.Reason.NOT_AUTHORIZED);
    }
  }

  private void recordDenial(ExecutionContext context, UUID targetId) {
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.ofNullable(context).map(ExecutionContext::actor),
              Optional.ofNullable(context).map(ExecutionContext::scope),
              "ANALYTICS_DHIS2_EXPORT_DENIED",
              "Dhis2ExportOperation",
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
      throw new Dhis2ExportException(Dhis2ExportException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private AuditEvidence mutationEvidence(
      ExecutionContext context, String action, UUID operationId, String reason) {
    return new AuditEvidence(
        AuditActionKind.PROTECTED_MUTATION,
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        action,
        "Dhis2ExportOperation",
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
    AuthenticationMechanism mechanism = context.authenticationMechanism();
    return switch (mechanism) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }

  private static Dhis2ExportOperation.Dhis2ImportCounts counts(Dhis2ImportSummary summary) {
    return new Dhis2ExportOperation.Dhis2ImportCounts(
        summary.imported(), summary.updated(), summary.ignored(), summary.deleted());
  }

  private static MappedFailure classify(PartnerTransportFailure failure) {
    return switch (failure) {
      case REMOTE_AUTHENTICATION_FAILURE ->
          new MappedFailure(Dhis2ExportState.REJECTED, "TARGET_AUTHENTICATION_FAILURE");
      case REMOTE_AUTHORIZATION_FAILURE ->
          new MappedFailure(Dhis2ExportState.REJECTED, "TARGET_AUTHORIZATION_FAILURE");
      case REMOTE_4XX, REMOTE_NOT_FOUND ->
          new MappedFailure(Dhis2ExportState.REJECTED, "TARGET_METADATA_VALIDATION_FAILURE");
      case RATE_LIMITED ->
          new MappedFailure(Dhis2ExportState.OUTCOME_UNKNOWN, "TARGET_RATE_LIMITED");
      case REMOTE_5XX ->
          new MappedFailure(Dhis2ExportState.OUTCOME_UNKNOWN, "TARGET_REMOTE_FAILURE");
      case PROFILE_NOT_ACTIVE,
          TRUST_UNAVAILABLE,
          AUTHENTICATION_NOT_SUPPORTED,
          AUTHENTICATION_FAILURE ->
          new MappedFailure(Dhis2ExportState.REJECTED, "TARGET_PROFILE_UNAVAILABLE");
      case DNS_FAILURE,
          DNS_TIMEOUT,
          DNS_CAPACITY_EXCEEDED,
          DESTINATION_REJECTED,
          TLS_FAILURE,
          CONNECT_TIMEOUT,
          READ_TIMEOUT,
          OPERATION_TIMEOUT,
          RESPONSE_TOO_LARGE,
          UNSUPPORTED_CONTENT_ENCODING,
          INVALID_RESPONSE,
          PEER_MISMATCH,
          CONNECTION_FAILURE ->
          new MappedFailure(Dhis2ExportState.OUTCOME_UNKNOWN, "TRANSPORT_OUTCOME_UNKNOWN");
    };
  }

  private static String operationFingerprint(
      AggregateSnapshot snapshot,
      org.ayuvahak.analytics.domain.AggregateSnapshotRevision revision,
      Dhis2MappingVersion mapping,
      Dhis2DataValueTuple tuple) {
    String canonical =
        String.join(
            "\n",
            snapshot.snapshotId().toString(),
            snapshot.inputFingerprint(),
            revision.seriesId().toString(),
            Integer.toString(revision.revisionNumber()),
            mapping.fingerprint(),
            tuple.stableKey(),
            mapping.dataSetUid().orElse(""));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }

  private record MappedFailure(Dhis2ExportState state, String resultClass) {}

  private record StartResult(Dhis2ExportOperation operation, boolean started) {}
}
