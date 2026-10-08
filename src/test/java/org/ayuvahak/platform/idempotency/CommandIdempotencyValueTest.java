package org.ayuvahak.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CommandIdempotencyValueTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void digestsValidKeysWithoutRetainingRawKey() {
    var digest = IdempotencyKeyDigest.from("client-key-42");

    assertThat(digest.value()).matches("[0-9a-f]{64}");
    assertThat(digest.value()).isEqualTo(IdempotencyKeyDigest.from("client-key-42").value());
    assertThat(digest.toString()).doesNotContain("client-key-42");
  }

  @Test
  void rejectsBlankOversizedAndNonVisibleAsciiKeys() {
    assertThatThrownBy(() -> IdempotencyKeyDigest.from(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> IdempotencyKeyDigest.from("x".repeat(129)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> IdempotencyKeyDigest.from("unsafe\nkey"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> IdempotencyKeyDigest.from("non-ascii-☃"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fingerprintsCanonicalJsonWithSortedObjectFieldsAndNormalizedNumbers() {
    var first = objectMapper.createObjectNode();
    first.put("z", 1.0);
    first.putObject("a").put("second", "value").put("first", true);

    var second = objectMapper.createObjectNode();
    second.putObject("a").put("first", true).put("second", "value");
    second.put("z", 1);

    assertThat(RequestFingerprint.from(first).value())
        .isEqualTo(RequestFingerprint.from(second).value());
    assertThat(RequestFingerprint.from(first).value()).matches("[0-9a-f]{64}");
  }

  @Test
  void fingerprintsChangeWhenNormalizedCommandMeaningChanges() {
    var original = objectMapper.createObjectNode().put("authority", "AuditReview");
    var changed = objectMapper.createObjectNode().put("authority", "EventDeliveryOperations");

    assertThat(RequestFingerprint.from(original)).isNotEqualTo(RequestFingerprint.from(changed));
  }

  @Test
  void restoresOnlyAValidatedPersistedFingerprintDigest() {
    var fingerprint =
        RequestFingerprint.from(objectMapper.createObjectNode().put("action", "read"));

    assertThat(RequestFingerprint.fromDigest(fingerprint.value())).isEqualTo(fingerprint);
    assertThatThrownBy(() -> RequestFingerprint.fromDigest("not-a-digest"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void commandReceiptKeyScopesTheKeyToActorExecutionScopeOperationAndTarget() {
    var issuer = new IssuerId("https://issuer.example.test");
    var actor = ActorReference.human(issuer, "person-17");
    var context = interactiveContext(actor, RequestedExecutionScope.platform(), "corr-17");
    String rawKey = "retry-9";

    var first = CommandReceiptKey.from(context, "platform-grant.create", "platform-grants", rawKey);
    var sameScopeAndOperation =
        CommandReceiptKey.from(
            interactiveContext(actor, RequestedExecutionScope.platform(), "corr-another"),
            "platform-grant.create",
            "platform-grants",
            rawKey);
    var differentOperation =
        CommandReceiptKey.from(context, "platform-grant.revoke", "platform-grants", rawKey);
    var differentTarget =
        CommandReceiptKey.from(
            context, "platform-grant.create", "platform-grants/target-2", rawKey);
    var differentScope =
        CommandReceiptKey.from(
            interactiveContext(
                actor,
                RequestedExecutionScope.organization(
                    new ExternalReference(issuer, "Organization", "org-1")),
                "corr-17"),
            "platform-grant.create",
            "platform-grants",
            rawKey);
    var differentActor =
        CommandReceiptKey.from(
            new ExecutionContextFactory((ignoredActor, ignoredScope) -> true)
                .forInteractiveRequest(
                    ActorReference.human(new IssuerId("https://issuer.example.test"), "person-18"),
                    RequestedExecutionScope.platform(),
                    new org.ayuvahak.platform.core.CorrelationId("corr-18")),
            "platform-grant.create",
            "platform-grants",
            rawKey);

    assertThat(first).isEqualTo(sameScopeAndOperation);
    assertThat(first).isNotEqualTo(differentOperation);
    assertThat(first).isNotEqualTo(differentTarget);
    assertThat(first).isNotEqualTo(differentScope);
    assertThat(first).isNotEqualTo(differentActor);
    assertThat(first.toString()).doesNotContain("person-17", "retry-9", "issuer.example.test");
  }

  @Test
  void comparesAnExistingReceiptByFingerprintWithoutReplayingDifferentInput() {
    var fingerprint =
        RequestFingerprint.from(objectMapper.createObjectNode().put("action", "read"));
    var receipt =
        new CommandReceipt(
            fingerprint,
            201,
            InternalId.generate(),
            new RecordedAt(Instant.parse("2026-01-01T00:00:00Z")));

    assertThat(CommandReceiptDecision.forRequest(receipt, fingerprint))
        .isInstanceOf(CommandReceiptDecision.Replay.class);
    assertThat(
            CommandReceiptDecision.forRequest(
                receipt,
                RequestFingerprint.from(objectMapper.createObjectNode().put("action", "write"))))
        .isEqualTo(CommandReceiptDecision.KeyReused.INSTANCE);
  }

  @Test
  void consumerReceiptKeyIsOwnedByConsumerAndEventIdentity() {
    var event = InternalId.generate();

    assertThat(new ConsumerReceiptKey("audit-projection", event))
        .isEqualTo(new ConsumerReceiptKey("audit-projection", event));
    assertThat(new ConsumerReceiptKey("audit-projection", event))
        .isNotEqualTo(new ConsumerReceiptKey("metric-projection", event));
    assertThatThrownBy(() -> new ConsumerReceiptKey("unsafe consumer", event))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static org.ayuvahak.platform.executioncontext.ExecutionContext interactiveContext(
      ActorReference actor, RequestedExecutionScope scope, String correlationId) {
    return new ExecutionContextFactory((ignoredActor, ignoredScope) -> true)
        .forInteractiveRequest(actor, scope, new CorrelationId(correlationId));
  }
}
