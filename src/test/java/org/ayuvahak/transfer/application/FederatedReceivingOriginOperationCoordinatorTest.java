package org.ayuvahak.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.exchange.application.FederationOperationFingerprint;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt;
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
              InternalId.generate(),
              Optional.empty(),
              4);
        };
    FederatedReceivingOriginOperationCoordinator coordinator =
        new FederatedReceivingOriginOperationCoordinator(
            store, exchange, noOpTransactions(), Clock.fixed(NOW, ZoneOffset.UTC));

    FederatedOriginReceivingAttempt result = coordinator.submit(operationId);

    assertThat(result.state()).isEqualTo(FederatedOriginReceivingAttempt.State.OUTCOME_UNKNOWN);
    assertThat(result.operationId()).isEqualTo(operationId);
    assertThat(result.requestFingerprint()).isEqualTo(fingerprint.value());
    assertThat(calls[0]).isEqualTo(1);

    FederatedOriginReceivingAttempt recovered = coordinator.reconcile(operationId);

    assertThat(recovered.state()).isEqualTo(FederatedOriginReceivingAttempt.State.RESOLVED);
    assertThat(recovered.remoteOutcome())
        .contains(FederatedOriginReceivingAttempt.RemoteOutcome.ACCEPTED_UNRESERVED);
    assertThat(recovered.operationId()).isEqualTo(operationId);
    assertThat(recovered.requestFingerprint()).isEqualTo(fingerprint.value());
    assertThat(calls[0]).isEqualTo(2);
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
}
