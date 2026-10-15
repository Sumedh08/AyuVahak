package org.ayuvahak.directory.application;

import java.util.Objects;
import org.ayuvahak.platform.core.ExternalReference;

/** Version-preconditioned source deletion evidence for the Directory-owned feed contract. */
public record DirectoryMcsdSourceDeletion(
    ExternalReference sourceIdentity, String expectedVersionId, String fingerprint) {

  public DirectoryMcsdSourceDeletion {
    Objects.requireNonNull(sourceIdentity, "sourceIdentity");
    if (expectedVersionId == null || !expectedVersionId.matches("[A-Za-z0-9.-]{1,64}")) {
      throw new IllegalArgumentException("a bounded source version precondition is required");
    }
    if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("source deletion fingerprint must be SHA-256 hex");
    }
  }
}
