package org.ayuvahak.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.audit.application.AuditRecordAppender;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScopeValidator;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferDecision;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class TransferPatientContextEligibilityQueryTest {

  private static final IssuerId DIRECTORY = new IssuerId("urn:ayuvahak:directory");
  private static final IssuerId IDP = new IssuerId("https://identity.example.test/realm/synthetic");

  @Test
  void permitsOnlyCurrentAcceptedAttemptForTheSameReceivingFacilityAndPreArrivalTransfer() {
    Fixture fixture = new Fixture();

    Optional<PatientContextParticipation> participation =
        fixture.query.verify(
            fixture.context(fixture.receivingFacility),
            fixture.referralId,
            fixture.transferId,
            fixture.requestAttemptId);

    assertThat(participation)
        .contains(
            new PatientContextParticipation(
                fixture.referralId,
                fixture.transferId,
                fixture.requestAttemptId,
                fixture.referralRevision,
                fixture.receivingFacility));
    assertThat(fixture.audits).hasSize(1);
    assertThat(fixture.audits.getFirst().actionCode())
        .isEqualTo("TRANSFER_PATIENT_CONTEXT_PARTICIPATION_CHECK");
    assertThat(fixture.audits.getFirst().targetId()).contains(fixture.transferId);
  }

  @Test
  void deniesAnotherFacilityEvenWhenTheActorHasAValidGrantThere() {
    Fixture fixture = new Fixture();
    ExternalReference otherFacility = fixture.facility();

    Optional<PatientContextParticipation> participation =
        fixture.query.verify(
            fixture.context(otherFacility),
            fixture.referralId,
            fixture.transferId,
            fixture.requestAttemptId);

    assertThat(participation).isEmpty();
    assertThat(fixture.audits)
        .singleElement()
        .satisfies(
            evidence -> {
              assertThat(evidence.actionCode())
                  .isEqualTo("TRANSFER_PATIENT_CONTEXT_PARTICIPATION_CHECK");
              assertThat(evidence.authorizationOutcome().name()).isEqualTo("DENIED");
            });
  }

  @Test
  void deniesAnOlderAttemptAndAnyTransferThatHasReachedArrival() {
    Fixture fixture = new Fixture();
    fixture.latestAttempt = fixture.attempt(fixture.facility(), InternalId.generate(), 2);
    assertThat(
            fixture.query.verify(
                fixture.context(fixture.receivingFacility),
                fixture.referralId,
                fixture.transferId,
                fixture.requestAttemptId))
        .isEmpty();

    fixture.latestAttempt = fixture.attempt(fixture.receivingFacility);
    fixture.currentTransfer =
        new CoordinatedTransfer(
            fixture.transferId,
            fixture.journeyId,
            fixture.requestAttemptId,
            CoordinatedTransfer.Status.ARRIVED,
            fixture.now,
            3);
    assertThat(
            fixture.query.verify(
                fixture.context(fixture.receivingFacility),
                fixture.referralId,
                fixture.transferId,
                fixture.requestAttemptId))
        .isEmpty();
  }

  @Test
  void deniesWhenTheRequesterHasNoTransferParticipationGrant() {
    Fixture fixture = new Fixture();
    fixture.grantAllowed = false;

    assertThat(
            fixture.query.verify(
                fixture.context(fixture.receivingFacility),
                fixture.referralId,
                fixture.transferId,
                fixture.requestAttemptId))
        .isEmpty();
    assertThat(fixture.audits)
        .singleElement()
        .satisfies(
            evidence -> {
              assertThat(evidence.outcome().name()).isEqualTo("DENIED");
              assertThat(evidence.authorizationOutcome().name()).isEqualTo("DENIED");
            });
  }

  private static final class Fixture {
    private final Instant now = Instant.parse("2026-10-15T08:00:00Z");
    private final InternalId referralId = InternalId.generate();
    private final InternalId transferId = InternalId.generate();
    private final InternalId journeyId = InternalId.generate();
    private final InternalId requestAttemptId = InternalId.generate();
    private final long referralRevision = 2;
    private final ExternalReference receivingOrganization = organization();
    private final ExternalReference receivingFacility = facility();
    private final ExternalReference originFacility = facility();
    private final TransferJourney journey =
        new TransferJourney(
            journeyId,
            referralId,
            receivingOrganization,
            Optional.of(originFacility),
            ActorReference.human(IDP, "synthetic-origin"),
            now.minusSeconds(60),
            1);
    private final TransferRequestAttempt attempt = attempt(receivingFacility);
    private TransferRequestAttempt latestAttempt = attempt;
    private boolean grantAllowed = true;
    private CoordinatedTransfer currentTransfer =
        new CoordinatedTransfer(
            transferId,
            journeyId,
            requestAttemptId,
            CoordinatedTransfer.Status.AWAITING_RESERVATION,
            now,
            1);
    private final TransferDecision acceptedDecision =
        new TransferDecision(
            InternalId.generate(),
            requestAttemptId,
            TransferDecision.Kind.ACCEPTED,
            Optional.empty(),
            Optional.of(transferId),
            ActorReference.human(IDP, "synthetic-receiver"),
            "AcceptanceAuthority",
            receivingOrganization,
            receivingFacility,
            now,
            now,
            1);
    private final List<AuditEvidence> audits = new java.util.ArrayList<>();
    private final AuthorizationService authorization =
        new AuthorizationService(
            (actor, authority, scope) ->
                grantAllowed
                    && authority
                        .code()
                        .equals(TransferAuthorities.patientContextParticipation().code()),
            new FunctionalAuthorityCatalog(
                List.of(TransferAuthorities.patientContextParticipation())));
    private final AuditWriter auditWriter =
        new AuditWriter(
            new AuditRecordAppender() {
              @Override
              public void appendInOwnerTransaction(
                  InternalId id, AuditEvidence evidence, Instant recordedAt) {
                throw new AssertionError("participant checks are non-mutating");
              }

              @Override
              public void appendDurably(InternalId id, AuditEvidence evidence, Instant recordedAt) {
                audits.add(evidence);
              }
            },
            Clock.fixed(now, java.time.ZoneOffset.UTC));
    private final TransferStore store = store();
    private final TransferPatientContextEligibilityQuery query =
        new TransferPatientContextEligibilityQuery(
            store,
            authorization,
            auditWriter,
            transactions(),
            Clock.fixed(now, java.time.ZoneOffset.UTC));

    private ExecutionContext context(ExternalReference facility) {
      ExecutionScopeValidator acceptSyntheticFixture = (actor, scope) -> true;
      return new ExecutionContextFactory(acceptSyntheticFixture)
          .forInteractiveRequest(
              ActorReference.human(IDP, "synthetic-recipient"),
              RequestedExecutionScope.facility(receivingOrganization, facility),
              new CorrelationId("p8-04-transfer-participant-test"));
    }

    private TransferStore store() {
      return (TransferStore)
          Proxy.newProxyInstance(
              TransferStore.class.getClassLoader(),
              new Class<?>[] {TransferStore.class},
              (proxy, method, arguments) ->
                  switch (method.getName()) {
                    case "findRequestAttempt" -> Optional.of(attempt);
                    case "findJourney" -> Optional.of(journey);
                    case "findLatestRequest" -> Optional.of(latestAttempt);
                    case "findDecisionByVersion" ->
                        Optional.of(
                            new TransferStore.DecisionResult(
                                acceptedDecision, Optional.of(currentTransfer)));
                    case "findTransfer" -> Optional.of(currentTransfer);
                    case "findJourneyClosure" -> Optional.empty();
                    default -> throw new UnsupportedOperationException(method.getName());
                  });
    }

    private TransferRequestAttempt attempt(ExternalReference destination) {
      return attempt(destination, requestAttemptId, 1);
    }

    private TransferRequestAttempt attempt(
        ExternalReference destination, InternalId attemptId, int attemptNumber) {
      return new TransferRequestAttempt(
          attemptId,
          journeyId,
          attemptNumber,
          referralId,
          referralRevision,
          InternalId.generate(),
          destination,
          TransferRequestAttempt.Status.ACCEPTED,
          ActorReference.human(IDP, "synthetic-origin"),
          now.minusSeconds(30),
          now.plusSeconds(1800),
          Optional.empty(),
          Optional.empty(),
          1);
    }

    private ExternalReference facility() {
      return new ExternalReference(DIRECTORY, "Facility", InternalId.generate().toString());
    }

    private static ExternalReference organization() {
      return new ExternalReference(DIRECTORY, "Organization", InternalId.generate().toString());
    }
  }

  private static PlatformTransactionManager transactions() {
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
