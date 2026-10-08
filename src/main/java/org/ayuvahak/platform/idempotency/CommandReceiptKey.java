package org.ayuvahak.platform.idempotency;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/**
 * Owner-local receipt lookup key. Actor, scope, operation, and target are folded into a digest so
 * owner tables need not repeat raw principal or target values in every command receipt.
 */
public record CommandReceiptKey(String scopeDigest, IdempotencyKeyDigest idempotencyKey) {

  public CommandReceiptKey {
    if (scopeDigest == null || !scopeDigest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("command receipt scope must be a SHA-256 digest");
    }
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
  }

  /**
   * Build an owner-local key from trusted execution context. The stable target scope is hashed and
   * not retained; callers must not pass request content or a sensitive display value.
   */
  public static CommandReceiptKey from(
      ExecutionContext context, String operation, String targetScope, String rawKey) {
    Objects.requireNonNull(context, "context");
    requireScopeToken(operation, "operation");
    requireTarget(targetScope);

    MessageDigest digest = sha256Digest();
    ActorReference actor = context.actor();
    append(digest, actor.kind().name());
    appendReference(digest, actor.identity());
    ExecutionScope executionScope = context.scope();
    append(digest, executionScope.kind().name());
    executionScope.organization().ifPresent(reference -> appendReference(digest, reference));
    executionScope.facility().ifPresent(reference -> appendReference(digest, reference));
    append(digest, operation);
    append(digest, targetScope);

    return new CommandReceiptKey(
        java.util.HexFormat.of().formatHex(digest.digest()), IdempotencyKeyDigest.from(rawKey));
  }

  private static void appendReference(MessageDigest digest, ExternalReference reference) {
    append(digest, reference.issuer().value());
    append(digest, reference.resourceType());
    append(digest, reference.value());
  }

  private static void append(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private static void requireScopeToken(String value, String label) {
    if (value == null || !value.matches("[a-z][a-z0-9.-]{0,63}")) {
      throw new IllegalArgumentException(label + " must be a stable lowercase operation token");
    }
  }

  private static void requireTarget(String targetScope) {
    if (targetScope == null
        || targetScope.isBlank()
        || !targetScope.equals(targetScope.strip())
        || targetScope.length() > 256
        || targetScope.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("target scope must be a bounded, nonblank identifier");
    }
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JDK SHA-256 provider is unavailable", exception);
    }
  }

  @Override
  public String toString() {
    return "CommandReceiptKey[scope=redacted, idempotencyKey=redacted]";
  }
}
