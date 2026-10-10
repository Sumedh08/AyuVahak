package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.util.Optional;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.adapter.configuration.TransferProperties;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Bounded Transfer-owned scan and command dispatch for due request expiry. */
@Service
@Lazy
public final class TransferRequestExpirySweep {

  private static final Logger logger = LoggerFactory.getLogger(TransferRequestExpirySweep.class);

  private final TransferStore store;
  private final TransferRequestLifecycleCommands commands;
  private final ExecutionContextFactory contextFactory;
  private final TransferProperties properties;
  private final ActorReference worker;
  private final Clock clock;
  private final ObjectMapper objectMapper;

  public TransferRequestExpirySweep(
      TransferStore store,
      TransferRequestLifecycleCommands commands,
      ExecutionContextFactory contextFactory,
      TransferProperties properties,
      AuthenticationProperties authentication,
      @Qualifier("auditClock") Clock clock,
      ObjectMapper objectMapper) {
    this.store = store;
    this.commands = commands;
    this.contextFactory = contextFactory;
    this.properties = properties;
    this.worker =
        ActorReference.service(
            new IssuerId(authentication.issuer()), properties.getExpiryWorkerClientId());
    this.clock = clock;
    this.objectMapper = objectMapper;
  }

  public int expireDueRequests() {
    var now = clock.instant();
    var due = store.findDueRequestAttempts(now, properties.getExpiryBatchSize());
    int expired = 0;
    for (TransferRequestAttempt attempt : due) {
      var journey = store.findJourney(attempt.journeyId());
      if (journey.isEmpty()) {
        logger.error(
            "Transfer request expiry deferred correlationId={} code=TRANSFER_JOURNEY_MISSING",
            CorrelationId.generate());
        continue;
      }
      CorrelationId correlationId = CorrelationId.generate();
      try {
        var context =
            contextFactory.forBackgroundWork(
                worker,
                RequestedExecutionScope.organization(journey.orElseThrow().originOrganization()),
                correlationId,
                Optional.empty());
        var fingerprintDocument =
            objectMapper
                .createObjectNode()
                .put("expectedVersion", attempt.version())
                .put("expiresAt", attempt.expiresAt().toString());
        commands.expire(
            context,
            attempt.id(),
            attempt.version(),
            "transfer-expiry-" + attempt.id() + "-" + attempt.version(),
            RequestFingerprint.from(fingerprintDocument));
        expired++;
      } catch (PlatformProblemException failure) {
        if (failure.code() == PlatformErrorCode.TRANSFER_STATE_CONFLICT
            || failure.code() == PlatformErrorCode.TRANSFER_REQUEST_NOT_PENDING) {
          continue;
        }
        logger.warn(
            "Transfer request expiry deferred correlationId={} code={}",
            correlationId,
            failure.code());
      } catch (RuntimeException failure) {
        logger.warn(
            "Transfer request expiry deferred correlationId={} failureType={}",
            correlationId,
            failure.getClass().getSimpleName());
      }
    }
    return expired;
  }
}
