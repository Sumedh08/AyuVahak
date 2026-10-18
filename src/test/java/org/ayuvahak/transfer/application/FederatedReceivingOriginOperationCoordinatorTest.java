package org.ayuvahak.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.ayuvahak.platform.audit.application.AuditRecordAppender;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
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
import org.ayuvahak.platform.exchange.application.FederationOperationFingerprint;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class FederatedReceivingOriginOperationCoordinatorTest {

  private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

  @Test
  void ambiguousPhase9ResultPersistsUnknownWithoutChangingStableOperationIdentity() {
    InternalId journeyId = InternalId.generate();
    InternalId operationId = InternalId.generate();
    var fingerprint = new FederationOperationFingerprint("a".repeat(64));
    FederatedOriginReceivingAttempt original =
        FederatedOriginReceivingAttempt.pending(
            InternalId.generate(),
            journeyId,
            3,
            new DeploymentId("SYN-DEPLOYMENT-A"),
            new DeploymentId("SYN-DEPLOYMENT-B"),
            new PartnerKey("synthetic-peer-b"),
            7,
            operationId,
            fingerprint,
            "b".repeat(64),
            "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            NOW);
    InMemoryStore store = new InMemoryStore(original);
    ExecutionContext context = context(journeyId);
    int[] calls = {0};
    FederatedReceivingOriginExchange exchange =
        (attempt, statusOnly) -> {
          assertThat(attempt.operationId()).isEqualTo(operationId);
          assertThat(attempt.requestFingerprint()).isEqualTo(fingerprint.value());
          calls[0]++;
          if (!statusOnly) {
            throw FederatedReceivingOriginExchangeException.outcomeUnknown();
          }
          return new FederatedReceivingOriginExchangeResult(
              FederatedOriginReceivingAttempt.RemoteOutcome.ACCEPTED_UNRESERVED,
              FederatedOriginReceivingAttempt.RemoteReservationStatus.OUTCOME_UNKNOWN,
              InternalId.generate(),
              Optional.empty(),
              Optional.of(supportingAssessment(NOW.plusSeconds(1), NOW.plusSeconds(1))),
              Optional.of(supportingAssessment(NOW.plusSeconds(1), NOW.plusSeconds(1))),
              Optional.of(NOW.plusSeconds(1)),
              NOW.plusSeconds(1),
              4);
        };
    FederatedReceivingOriginOperationCoordinator coordinator =
        new FederatedReceivingOriginOperationCoordinator(
            store,
            transferStore(journeyId),
            exchange,
            audit(),
            outbox(),
            new tools.jackson.databind.ObjectMapper(),
            noOpTransactions(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    FederatedOriginReceivingAttempt result = coordinator.submit(context, operationId);

    assertThat(result.state()).isEqualTo(FederatedOriginReceivingAttempt.State.OUTCOME_UNKNOWN);
    assertThat(result.operationId()).isEqualTo(operationId);
    assertThat(result.requestFingerprint()).isEqualTo(fingerprint.value());
    assertThat(calls[0]).isEqualTo(1);

    FederatedOriginReceivingAttempt recovered = coordinator.reconcile(context, operationId);

    assertThat(recovered.state()).isEqualTo(FederatedOriginReceivingAttempt.State.OUTCOME_UNKNOWN);
    assertThat(recovered.remoteOutcome())
        .contains(FederatedOriginReceivingAttempt.RemoteOutcome.ACCEPTED_UNRESERVED);
    assertThat(recovered.operationId()).isEqualTo(operationId);
    assertThat(recovered.requestFingerprint()).isEqualTo(fingerprint.value());
    assertThat(calls[0]).isEqualTo(2);
  }

  @Test
  void resendsSameStableOperationAfterStatusConfirmsNoReceiverRecord() {
    InternalId journeyId = InternalId.generate();
    InternalId operationId = InternalId.generate();
    FederatedOriginReceivingAttempt sent =
        FederatedOriginReceivingAttempt.pending(
                InternalId.generate(),
                journeyId,
                1,
                new DeploymentId("SYN-DEPLOYMENT-A"),
                new DeploymentId("SYN-DEPLOYMENT-B"),
                new PartnerKey("synthetic-peer-b"),
                7,
                operationId,
                new FederationOperationFingerprint("c".repeat(64)),
                "d".repeat(64),
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                NOW.minusSeconds(400))
            .withState(FederatedOriginReceivingAttempt.State.SENT, NOW.minusSeconds(400));
    InMemoryStore store = new InMemoryStore(sent);
    ExecutionContext context = context(journeyId);
    List<Boolean> statusOnlyCalls = new ArrayList<>();
    FederatedReceivingOriginExchange exchange =
        (attempt, statusOnly) -> {
          statusOnlyCalls.add(statusOnly);
          assertThat(attempt.operationId()).isEqualTo(operationId);
          assertThat(attempt.requestFingerprint()).isEqualTo(sent.requestFingerprint());
          if (statusOnly) {
            throw FederatedReceivingOriginExchangeException.notSent();
          }
          return new FederatedReceivingOriginExchangeResult(
              FederatedOriginReceivingAttempt.RemoteOutcome.RECEIVED,
              FederatedOriginReceivingAttempt.RemoteReservationStatus.NOT_REQUESTED,
              InternalId.generate(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              NOW,
              1);
        };
    FederatedReceivingOriginOperationCoordinator coordinator =
        new FederatedReceivingOriginOperationCoordinator(
            store,
            transferStore(journeyId),
            exchange,
            audit(),
            outbox(),
            new tools.jackson.databind.ObjectMapper(),
            noOpTransactions(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    FederatedOriginReceivingAttempt statusCheck = coordinator.submit(context, operationId);
    FederatedOriginReceivingAttempt recovered = coordinator.submit(context, operationId);

    assertThat(statusCheck.state()).isEqualTo(FederatedOriginReceivingAttempt.State.PENDING);
    assertThat(statusOnlyCalls).containsExactly(true, false);
    assertThat(recovered.state()).isEqualTo(FederatedOriginReceivingAttempt.State.SENT);
  }

  @Test
  void acceptedButPendingReservationStaysUnknownUntilSameOperationReportsReservation() {
    InternalId journeyId = InternalId.generate();
    InternalId operationId = InternalId.generate();
    InternalId receivingRequestId = InternalId.generate();
    FederatedOriginReceivingAttempt original =
        FederatedOriginReceivingAttempt.pending(
            InternalId.generate(),
            journeyId,
            1,
            new DeploymentId("SYN-DEPLOYMENT-A"),
            new DeploymentId("SYN-DEPLOYMENT-B"),
            new PartnerKey("synthetic-peer-b"),
            7,
            operationId,
            new FederationOperationFingerprint("e".repeat(64)),
            "f".repeat(64),
            "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            NOW);
    InMemoryStore store = new InMemoryStore(original);
    List<Boolean> statusCalls = new ArrayList<>();
    InternalId reservationId = InternalId.generate();
    FederatedReceivingOriginExchange exchange =
        (attempt, statusOnly) -> {
          statusCalls.add(statusOnly);
          if (!statusOnly) {
            return new FederatedReceivingOriginExchangeResult(
                FederatedOriginReceivingAttempt.RemoteOutcome.ACCEPTED_UNRESERVED,
                FederatedOriginReceivingAttempt.RemoteReservationStatus.PENDING,
                receivingRequestId,
                Optional.empty(),
                Optional.of(supportingAssessment(NOW.plusSeconds(1), NOW.plusSeconds(1))),
                Optional.of(supportingAssessment(NOW.plusSeconds(1), NOW.plusSeconds(1))),
                Optional.of(NOW.plusSeconds(1)),
                NOW.plusSeconds(1),
                2);
          }
          return new FederatedReceivingOriginExchangeResult(
              FederatedOriginReceivingAttempt.RemoteOutcome.ACCEPTED_RESERVED,
              FederatedOriginReceivingAttempt.RemoteReservationStatus.RESERVED,
              receivingRequestId,
              Optional.of(reservationId),
              Optional.of(supportingAssessment(NOW.plusSeconds(1), NOW.plusSeconds(2))),
              Optional.of(supportingAssessment(NOW.plusSeconds(1), NOW.plusSeconds(2))),
              Optional.of(NOW.plusSeconds(1)),
              NOW.plusSeconds(2),
              3);
        };
    ExecutionContext context = context(journeyId);
    FederatedReceivingOriginOperationCoordinator coordinator =
        new FederatedReceivingOriginOperationCoordinator(
            store,
            transferStore(journeyId),
            exchange,
            audit(),
            outbox(),
            new tools.jackson.databind.ObjectMapper(),
            noOpTransactions(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    FederatedOriginReceivingAttempt pending = coordinator.submit(context, operationId);
    FederatedOriginReceivingAttempt resolved = coordinator.reconcile(context, operationId);

    assertThat(pending.state()).isEqualTo(FederatedOriginReceivingAttempt.State.OUTCOME_UNKNOWN);
    assertThat(pending.remoteReservationStatus())
        .contains(FederatedOriginReceivingAttempt.RemoteReservationStatus.PENDING);
    assertThat(resolved.state()).isEqualTo(FederatedOriginReceivingAttempt.State.RESOLVED);
    assertThat(resolved.remoteReservationStatus())
        .contains(FederatedOriginReceivingAttempt.RemoteReservationStatus.RESERVED);
    assertThat(resolved.remoteCapacityReservationId()).contains(reservationId);
    assertThat(statusCalls).containsExactly(false, true);
  }

  private static ExecutionContext context(InternalId journeyId) {
    ActorReference actor =
        ActorReference.human(new IssuerId("urn:synthetic:identity"), "SYN-OPERATOR-001");
    ExternalReference organization = reference("Organization", InternalId.generate());
    ExternalReference facility = reference("Facility", InternalId.generate());
    return new ExecutionContextFactory((ignoredActor, ignoredScope) -> true)
        .forInteractiveRequest(
            actor,
            RequestedExecutionScope.facility(organization, facility),
            CorrelationId.generate());
  }

  private static org.ayuvahak.transfer.application.TransferStore transferStore(
      InternalId journeyId) {
    ActorReference actor =
        ActorReference.human(new IssuerId("urn:synthetic:identity"), "SYN-OPERATOR-001");
    ExternalReference organization = reference("Organization", InternalId.generate());
    ExternalReference facility = reference("Facility", InternalId.generate());
    TransferJourney[] current = {
      new TransferJourney(
          journeyId,
          InternalId.generate(),
          organization,
          Optional.of(facility),
          actor,
          NOW,
          false,
          4)
    };
    return (org.ayuvahak.transfer.application.TransferStore)
        java.lang.reflect.Proxy.newProxyInstance(
            org.ayuvahak.transfer.application.TransferStore.class.getClassLoader(),
            new Class<?>[] {org.ayuvahak.transfer.application.TransferStore.class},
            (proxy, method, args) -> {
              if ("lockJourney".equals(method.getName())) {
                return Optional.of(current[0]);
              }
              if ("advanceJourneyVersion".equals(method.getName())) {
                long expected = (long) args[1];
                if (current[0].version() != expected) {
                  return false;
                }
                current[0] =
                    new TransferJourney(
                        current[0].id(),
                        current[0].referralId(),
                        current[0].originOrganization(),
                        current[0].originFacility(),
                        current[0].createdBy(),
                        current[0].createdAt(),
                        current[0].transportRequired(),
                        current[0].version() + 1);
                return true;
              }
              if (Optional.class.isAssignableFrom(method.getReturnType())) {
                return Optional.empty();
              }
              if (method.getReturnType() == boolean.class) {
                return false;
              }
              return null;
            });
  }

  private static AuditWriter audit() {
    return new AuditWriter(
        new AuditRecordAppender() {
          @Override
          public void appendInOwnerTransaction(
              InternalId id, AuditEvidence evidence, Instant recordedAt) {}

          @Override
          public void appendDurably(InternalId id, AuditEvidence evidence, Instant recordedAt) {}
        },
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static OutboxWriter outbox() {
    return new OutboxWriter(
        new OutboxStore() {
          @Override
          public void record(EventEnvelope event) {}

          @Override
          public void recordWaitingForConsumer(EventEnvelope event) {}

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
          public boolean renewLease(LeaseToken token, Instant now, java.time.Duration duration) {
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
              LeaseToken token, Instant failedAt, Instant nextAttemptAt, int maximumAttempts) {
            return null;
          }
        });
  }

  private static ExternalReference reference(String type, InternalId id) {
    return new ExternalReference(new IssuerId("urn:ayuvahak:directory"), type, id.toString());
  }

  private static final class InMemoryStore implements FederatedOriginReceivingAttemptStore {
    private final Map<InternalId, FederatedOriginReceivingAttempt> attempts = new HashMap<>();

    private InMemoryStore(FederatedOriginReceivingAttempt attempt) {
      attempts.put(attempt.operationId(), attempt);
    }

    @Override
    public TransferStore.ReceiptClaim claimReceipt(
        CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt) {
      return new TransferStore.ReceiptClaim(
          TransferStore.ReceiptState.OWNER, Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Override
    public void completeReceipt(
        CommandReceiptKey key,
        int responseStatus,
        InternalId attemptId,
        long attemptNumber,
        Instant recordedAt) {}

    @Override
    public void insert(FederatedOriginReceivingAttempt attempt) {
      attempts.put(attempt.operationId(), attempt);
    }

    @Override
    public Optional<FederatedOriginReceivingAttempt> findById(InternalId attemptId) {
      return attempts.values().stream().filter(value -> value.id().equals(attemptId)).findFirst();
    }

    @Override
    public Optional<FederatedOriginReceivingAttempt> lockByOperationId(InternalId operationId) {
      return Optional.ofNullable(attempts.get(operationId));
    }

    @Override
    public int latestAttemptNumber(InternalId journeyId) {
      return attempts.values().stream()
          .filter(value -> value.journeyId().equals(journeyId))
          .mapToInt(FederatedOriginReceivingAttempt::attemptNumber)
          .max()
          .orElse(0);
    }

    @Override
    public boolean update(FederatedOriginReceivingAttempt attempt, long expectedVersion) {
      FederatedOriginReceivingAttempt current = attempts.get(attempt.operationId());
      if (current == null || current.version() != expectedVersion) {
        return false;
      }
      attempts.put(attempt.operationId(), attempt);
      return true;
    }
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

  private static FederatedOriginReceivingAttempt.RemoteAssessmentEvidence supportingAssessment(
      Instant occurredAt, Instant recordedAt) {
    return new FederatedOriginReceivingAttempt.RemoteAssessmentEvidence(
        FederatedOriginReceivingAttempt.AssessmentOutcome.SUPPORTS,
        "SYNTHETIC_POSITIVE_REVIEW",
        occurredAt,
        recordedAt);
  }
}
