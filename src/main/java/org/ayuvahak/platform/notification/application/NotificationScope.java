package org.ayuvahak.platform.notification.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** One exact, server-validated organization or facility scope for notification access. */
public record NotificationScope(
    ExecutionScope.Kind kind,
    Optional<ExternalReference> organization,
    Optional<ExternalReference> facility) {

  public NotificationScope {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(organization, "organization");
    Objects.requireNonNull(facility, "facility");
    switch (kind) {
      case PLATFORM -> {
        if (organization.isPresent() || facility.isPresent()) {
          throw new IllegalArgumentException(
              "platform notification scope cannot contain references");
        }
      }
      case ORGANIZATION -> {
        requireType(organization, "Organization");
        if (facility.isPresent()) {
          throw new IllegalArgumentException(
              "organization notification scope cannot contain a facility");
        }
      }
      case FACILITY -> {
        requireType(organization, "Organization");
        requireType(facility, "Facility");
      }
    }
  }

  public static NotificationScope from(ExecutionScope scope) {
    Objects.requireNonNull(scope, "scope");
    return new NotificationScope(scope.kind(), scope.organization(), scope.facility());
  }

  /**
   * Hash a length-delimited canonical scope representation; raw organization IDs are not stored.
   */
  public String key() {
    if (kind == ExecutionScope.Kind.PLATFORM) {
      throw new IllegalArgumentException("platform-wide notification scope is not supported");
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      add(digest, kind.name());
      organization.ifPresent(reference -> addReference(digest, reference));
      facility.ifPresent(reference -> addReference(digest, reference));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void requireType(Optional<ExternalReference> reference, String expected) {
    if (reference.isEmpty() || !expected.equals(reference.orElseThrow().resourceType())) {
      throw new IllegalArgumentException("notification scope reference type is invalid");
    }
  }

  private static void addReference(MessageDigest digest, ExternalReference reference) {
    add(digest, reference.issuer().value());
    add(digest, reference.resourceType());
    add(digest, reference.value());
  }

  private static void add(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update((byte) (bytes.length >>> 24));
    digest.update((byte) (bytes.length >>> 16));
    digest.update((byte) (bytes.length >>> 8));
    digest.update((byte) bytes.length);
    digest.update(bytes);
  }
}
