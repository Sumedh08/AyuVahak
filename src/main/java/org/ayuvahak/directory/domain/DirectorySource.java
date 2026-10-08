package org.ayuvahak.directory.domain;

import java.util.Objects;
import org.ayuvahak.platform.core.ExternalReference;

/** Issuer-attributed source reference for a Directory projection fact. */
public record DirectorySource(ExternalReference reference) {

  public DirectorySource {
    Objects.requireNonNull(reference, "source reference");
  }
}
