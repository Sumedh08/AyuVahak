package org.ayuvahak.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.ayuvahak.matching.application.FederatedCapacityPeerPolicy;
import org.ayuvahak.matching.application.RecommendationApplicabilityResult;
import org.ayuvahak.matching.domain.RemoteCapacityEvidenceCompatibility;
import org.ayuvahak.matching.domain.RemoteFacilityReference;
import org.ayuvahak.platform.audit.application.AuditRecordAppender;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.authorization.application.AuthorizationGrantLookup;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.eventing.application.DeliveryFailureResult;
import org.ayuvahak.platform.eventing.application.LeaseToken;
import org.ayuvahak.platform.eventing.application.LeasedEvent;
import org.ayuvahak.platform.eventing.application.OutboxStore;
import org.ayuvahak.platform.eventing.application.OutboxWriter;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.ObjectMapper;

class FederatedReceivingOriginCommandsTest {

  private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
  private static final DeploymentId ORIGIN = new DeploymentId("SYN-DEPLOYMENT-A");
  private static final DeploymentId PEER = new DeploymentId("SYN-DEPLOYMENT-B");

  @Test
  void persistsAuthorizedStableAttemptBeforeSendAndReplaysSameOperation() {
    Fixture fixture = new Fixture();
    List<Boolean> statusCalls = new ArrayList<>();
    FederatedReceivingOriginExchange exchange =
        (attempt, statusOnly) -> {
          FederatedOriginReceivingAttempt persisted =
              fixture.originStore.lockByOperationId(attempt.operationId()).orElseThrow();
          assertThat(fixture.originStore.wasPending(attempt.operationId())).isTrue();
          assertThat(persisted.state())
              .isEqualTo(
                  statusOnly
                      ? FederatedOriginReceivingAttempt.State.OUTCOME_UNKNOWN
                      : FederatedOriginReceivingAttempt.State.SENT);
          assertThat(persisted.partnerKey()).isEqualTo(new PartnerKey("synthetic-peer-b"));
          assertThat(persisted.configurationVersion()).isEqualTo(7);
          assertThat(new String(persisted.requestBody(), StandardCharsets.UTF_8))
              .doesNotContain("PatientReference");
          statusCalls.add(statusOnly);
          if (!statusOnly) {
            throw org.ayuvahak.transfer.application.FederatedReceivingOriginExchangeException
                .outcomeUnknown();
          }
          return new FederatedReceivingOriginExchangeResult(
              FederatedOriginReceivingAttempt.RemoteOutcome.ACCEPTED_UNRESERVED,
              FederatedOriginReceivingAttempt.RemoteReservationStatus.OUTCOME_UNKNOWN,
              InternalId.generate(),
              Optional.empty(),
              Optional.of(supportingAssessment()),
              Optional.of(supportingAssessment()),
              Optional.of(Instant.EPOCH),
              Instant.EPOCH,
              3);
        };
    fixture.command = fixture.command(exchange);

    var first =
        fixture.command.request(
            fixture.context, fixture.journey.id(), fixture.commandInput, "idem-a");
    var replay =
        fixture.command.request(
            fixture.context, fixture.journey.id(), fixture.commandInput, "idem-a");

    assertThat(first.attempt().state())
        .isEqualTo(FederatedOriginReceivingAttempt.State.OUTCOME_UNKNOWN);
    assertThat(replay.attempt().state())
        .isEqualTo(FederatedOriginReceivingAttempt.State.OUTCOME_UNKNOWN);
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.attempt().operationId()).isEqualTo(first.attempt().operationId());
    assertThat(replay.attempt().requestFingerprint())
        .isEqualTo(first.attempt().requestFingerprint());
    assertThat(fixture.originStore.attempts).hasSize(1);
    assertThat(statusCalls).containsExactly(false, true);
    assertThat(fixture.originStore.receipts).hasSize(1);
    assertThat(fixture.outboxEvents).hasSize(4);
  }

  @Test
  void receivingAcknowledgementRemainsPendingAndReplayDoesNotRaceReceiverCommit() {
    Fixture fixture = new Fixture();
    List<Boolean> statusCalls = new ArrayList<>();
    InternalId receivingRequestId = InternalId.generate();
    fixture.command =
        fixture.command(
            (attempt, statusOnly) -> {
              statusCalls.add(statusOnly);
              return new FederatedReceivingOriginExchangeResult(
                  FederatedOriginReceivingAttempt.RemoteOutcome.RECEIVED,
                  FederatedOriginReceivingAttempt.RemoteReservationStatus.NOT_REQUESTED,
                  receivingRequestId,
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Instant.EPOCH,
                  1);
            });

    var first =
        fixture.command.request(
            fixture.context, fixture.journey.id(), fixture.commandInput, "idem-pending");
    var replay =
        fixture.command.request(
            fixture.context, fixture.journey.id(), fixture.commandInput, "idem-pending");

    assertThat(first.attempt().state()).isEqualTo(FederatedOriginReceivingAttempt.State.SENT);
    assertThat(first.attempt().remoteOutcome())
        .contains(FederatedOriginReceivingAttempt.RemoteOutcome.RECEIVED);
    assertThat(replay.attempt().state()).isEqualTo(FederatedOriginReceivingAttempt.State.SENT);
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.attempt().remoteReceivingRequestId()).contains(receivingRequestId);
    assertThat(statusCalls).containsExactly(false);
  }

  @Test
  void changedContentUnderSameIdempotencyKeyConflictsBeforeAnotherSend() {
    Fixture fixture = new Fixture();
    int[] sends = {0};
    fixture.command =
        fixture.command(
            (attempt, statusOnly) -> {
              sends[0]++;
              throw org.ayuvahak.transfer.application.FederatedReceivingOriginExchangeException
                  .outcomeUnknown();
            });
    fixture.command.request(fixture.context, fixture.journey.id(), fixture.commandInput, "idem-a");
    FederatedReceivingOriginCommand changed =
        new FederatedReceivingOriginCommand(
            fixture.commandInput.expectedJourneyVersion(),
            fixture.commandInput.referralRevision(),
            fixture.commandInput.recommendationId(),
            fixture.commandInput.destinationOrganization(),
            fixture.commandInput.destinationFacility(),
            fixture.commandInput.capacityDimensionCode(),
            fixture.commandInput.capacityUnit(),
            new BigDecimal("2"),
            fixture.commandInput.capacityIntervalStart(),
            fixture.commandInput.capacityIntervalEnd());

    assertThatThrownBy(
            () -> fixture.command.request(fixture.context, fixture.journey.id(), changed, "idem-a"))
        .isInstanceOf(org.ayuvahak.platform.api.PlatformProblemException.class)
        .hasMessageContaining("already used");
    assertThat(fixture.originStore.attempts).hasSize(1);
    assertThat(sends[0]).isEqualTo(1);
  }

  private static final class Fixture {
    final InternalId referralId = InternalId.generate();
    final InternalId recommendationId = InternalId.generate();
    final ExternalReference originOrganization =
        directoryReference("Organization", InternalId.generate());
    final ExternalReference originFacility = directoryReference("Facility", InternalId.generate());
    final ActorReference actor =
        ActorReference.human(new IssuerId("urn:synthetic:identity"), "SYN-OPERATOR-001");
    final TransferJourney journey =
        new TransferJourney(
            InternalId.generate(),
            referralId,
            originOrganization,
            Optional.of(originFacility),
            actor,
            NOW,
            false,
            1);
    final RemoteFacilityReference remoteFacility =
        new RemoteFacilityReference(PEER, "directory", InternalId.generate());
    final ExternalReference remoteOrganization =
        new ExternalReference(
            remoteFacility.toExternalReference().issuer(),
            "Organization",
            InternalId.generate().toString());
    final ExecutionContext context =
        new ExecutionContextFactory((ignoredActor, ignoredScope) -> true)
            .forInteractiveRequest(
                actor,
                RequestedExecutionScope.facility(originOrganization, originFacility),
                CorrelationId.generate());
    final FederatedReceivingOriginCommand commandInput =
        new FederatedReceivingOriginCommand(
            1,
            2,
            recommendationId,
            remoteOrganization,
            remoteFacility,
            "BED",
            "count",
            BigDecimal.ONE,
            NOW.plusSeconds(60),
            NOW.plusSeconds(3600));
    final InMemoryOriginStore originStore = new InMemoryOriginStore();
    final List<EventEnvelope> outboxEvents = new ArrayList<>();
    FederatedReceivingOriginCommands command;
    TransferJourney currentJourney = journey;

    FederatedReceivingOriginCommands command(FederatedReceivingOriginExchange exchange) {
      AuthorizationGrantLookup grants = (ignoredActor, ignoredAuthority, ignoredScope) -> true;
      AuthorizationService authorization =
          new AuthorizationService(
              grants, new FunctionalAuthorityCatalog(List.of(TransferAuthorities.request())));
      AuditWriter audit =
          new AuditWriter(
              new AuditRecordAppender() {
                @Override
                public void appendInOwnerTransaction(
                    InternalId id, AuditEvidence evidence, Instant recordedAt) {}

                @Override
                public void appendDurably(
                    InternalId id, AuditEvidence evidence, Instant recordedAt) {}
              },
              Clock.fixed(NOW, ZoneOffset.UTC));
      OutboxWriter outbox =
          new OutboxWriter(
              new OutboxStore() {
                @Override
                public void record(EventEnvelope event) {
                  outboxEvents.add(event);
                }

                @Override
                public void recordWaitingForConsumer(EventEnvelope event) {
                  outboxEvents.add(event);
                }

                @Override
                public List<LeasedEvent> claimDue(
                    Instant now, java.time.Duration leaseDuration, int maximumAttempts, int limit) {
                  return List.of();
                }

                @Override
                public boolean markUnhandled(LeaseToken token, Instant now) {
                  return false;
                }

                @Override
                public int beginAttempt(LeaseToken token, Instant now, int maximumAttempts) {
                  return 0;
                }

                @Override
                public boolean renewLease(
                    LeaseToken token, Instant now, java.time.Duration duration) {
                  return false;
                }

                @Override
                public boolean markDelivered(LeaseToken token, Instant now) {
                  return false;
                }

                @Override
                public int purgeDeliveredBefore(Instant cutoff, int limit) {
                  return 0;
                }

                @Override
                public DeliveryFailureResult markFailed(
                    LeaseToken token,
                    Instant failedAt,
                    Instant nextAttemptAt,
                    int maximumAttempts) {
                  return null;
                }
              });
      var applicability =
          new MatchingApplicabilityPort() {
            @Override
            public RecommendationApplicabilityResult revalidate(
                ExecutionContext ignoredContext,
                InternalId ignoredReferral,
                InternalId recommendation,
                ExternalReference candidate,
                long referralRevision) {
              return new RecommendationApplicabilityResult(
                  recommendation,
                  candidate,
                  RecommendationApplicabilityResult.Status.APPLICABLE,
                  List.of(),
                  NOW);
            }
          };
      FederatedCapacityPeerPolicy peerPolicy =
          (facility, dimension) ->
              Optional.of(
                  new org.ayuvahak.matching.application.RemoteCapacityPeerTarget(
                      new PartnerKey("synthetic-peer-b"),
                      PEER,
                      7,
                      new RemoteCapacityEvidenceCompatibility("synthetic-v1", 1, 1)));
      FederatedReceivingOriginOperationCoordinator coordinator =
          new FederatedReceivingOriginOperationCoordinator(
              originStore,
              transferStore(),
              exchange,
              audit,
              outbox,
              new ObjectMapper(),
              noOpTransactions(),
              Clock.fixed(NOW, ZoneOffset.UTC));
      return new FederatedReceivingOriginCommands(
          transferStore(),
          originStore,
          applicability,
          peerPolicy,
          () -> Optional.of(ORIGIN),
          authorization,
          audit,
          outbox,
          coordinator,
          new ObjectMapper(),
          noOpTransactions(),
          Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private org.ayuvahak.transfer.application.TransferStore transferStore() {
      return (org.ayuvahak.transfer.application.TransferStore)
          Proxy.newProxyInstance(
              org.ayuvahak.transfer.application.TransferStore.class.getClassLoader(),
              new Class<?>[] {org.ayuvahak.transfer.application.TransferStore.class},
              (proxy, method, args) -> {
                return switch (method.getName()) {
                  case "findJourney", "lockJourney" -> Optional.of(currentJourney);
                  case "lockReferralCoordination", "configureBoundedReceiptWait" -> null;
                  case "findJourneyClosure" -> Optional.empty();
                  case "findLatestRequest" -> Optional.of(rejectedLocalAttempt());
                  case "hasBlockingFederatedDestinationAttempt" -> false;
                  case "findAttemptState" -> Optional.empty();
                  case "advanceJourneyVersion" -> {
                    if (currentJourney.version() != (long) args[1]) {
                      yield false;
                    }
                    currentJourney =
                        new TransferJourney(
                            currentJourney.id(),
                            currentJourney.referralId(),
                            currentJourney.originOrganization(),
                            currentJourney.originFacility(),
                            currentJourney.createdBy(),
                            currentJourney.createdAt(),
                            currentJourney.transportRequired(),
                            currentJourney.version() + 1);
                    yield true;
                  }
                  default -> defaultValue(method.getReturnType());
                };
              });
    }

    private TransferRequestAttempt rejectedLocalAttempt() {
      return new TransferRequestAttempt(
          InternalId.generate(),
          journey.id(),
          1,
          referralId,
          1,
          recommendationId,
          directoryReference("Facility", InternalId.generate()),
          TransferRequestAttempt.Status.REJECTED,
          actor,
          NOW,
          NOW.plusSeconds(300),
          Optional.empty(),
          Optional.empty(),
          1);
    }
  }

  private static final class InMemoryOriginStore implements FederatedOriginReceivingAttemptStore {
    private final Map<InternalId, FederatedOriginReceivingAttempt> attempts = new HashMap<>();
    private final Map<InternalId, List<FederatedOriginReceivingAttempt.State>> history =
        new HashMap<>();
    private final Map<CommandReceiptKey, Receipt> receipts = new HashMap<>();

    @Override
    public TransferStore.ReceiptClaim claimReceipt(
        CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt) {
      Receipt existing = receipts.get(key);
      if (existing == null) {
        receipts.put(key, new Receipt(fingerprint, null, 0));
        return new TransferStore.ReceiptClaim(
            TransferStore.ReceiptState.OWNER, Optional.empty(), Optional.empty(), Optional.empty());
      }
      if (!existing.fingerprint().equals(fingerprint)) {
        return new TransferStore.ReceiptClaim(
            TransferStore.ReceiptState.KEY_REUSED,
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
      }
      if (existing.attemptId() == null) {
        return new TransferStore.ReceiptClaim(
            TransferStore.ReceiptState.IN_PROGRESS,
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
      }
      return new TransferStore.ReceiptClaim(
          TransferStore.ReceiptState.REPLAY,
          Optional.of(HttpStatus.CREATED.value()),
          Optional.of(existing.attemptId()),
          Optional.of(existing.attemptNumber()));
    }

    @Override
    public void completeReceipt(
        CommandReceiptKey key,
        int responseStatus,
        InternalId attemptId,
        long attemptNumber,
        Instant recordedAt) {
      Receipt current = receipts.get(key);
      receipts.put(key, new Receipt(current.fingerprint(), attemptId, attemptNumber));
    }

    @Override
    public void insert(FederatedOriginReceivingAttempt attempt) {
      attempts.put(attempt.operationId(), attempt);
      history
          .computeIfAbsent(attempt.operationId(), ignored -> new ArrayList<>())
          .add(attempt.state());
    }

    @Override
    public Optional<FederatedOriginReceivingAttempt> findById(InternalId attemptId) {
      return attempts.values().stream().filter(a -> a.id().equals(attemptId)).findFirst();
    }

    @Override
    public Optional<FederatedOriginReceivingAttempt> lockByOperationId(InternalId operationId) {
      return Optional.ofNullable(attempts.get(operationId));
    }

    @Override
    public int latestAttemptNumber(InternalId journeyId) {
      return attempts.values().stream()
          .filter(attempt -> attempt.journeyId().equals(journeyId))
          .mapToInt(FederatedOriginReceivingAttempt::attemptNumber)
          .max()
          .orElse(1);
    }

    @Override
    public boolean update(FederatedOriginReceivingAttempt attempt, long expectedVersion) {
      FederatedOriginReceivingAttempt current = attempts.get(attempt.operationId());
      if (current == null || current.version() != expectedVersion) {
        return false;
      }
      attempts.put(attempt.operationId(), attempt);
      history
          .computeIfAbsent(attempt.operationId(), ignored -> new ArrayList<>())
          .add(attempt.state());
      return true;
    }

    boolean wasPending(InternalId operationId) {
      return history
          .getOrDefault(operationId, List.of())
          .contains(FederatedOriginReceivingAttempt.State.PENDING);
    }
  }

  private record Receipt(
      RequestFingerprint fingerprint, InternalId attemptId, long attemptNumber) {}

  private static Object defaultValue(Class<?> type) {
    if (Optional.class.isAssignableFrom(type)) {
      return Optional.empty();
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    return null;
  }

  private static ExternalReference directoryReference(String type, InternalId id) {
    return new ExternalReference(new IssuerId("urn:ayuvahak:directory"), type, id.toString());
  }

  private static PlatformTransactionManager noOpTransactions() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new org.springframework.transaction.support.SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {}

      @Override
      public void rollback(TransactionStatus status) {}
    };
  }

  private static FederatedOriginReceivingAttempt.RemoteAssessmentEvidence supportingAssessment() {
    return new FederatedOriginReceivingAttempt.RemoteAssessmentEvidence(
        FederatedOriginReceivingAttempt.AssessmentOutcome.SUPPORTS,
        "SYNTHETIC_POSITIVE_REVIEW",
        Instant.EPOCH,
        Instant.EPOCH);
  }
}
