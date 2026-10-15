package org.ayuvahak.platform.exchange.application;

import java.util.Objects;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;

/** HTTP-level outcome of a protected, idempotent partner-profile command. */
public record PartnerProfileCommandResult(
    int status, PartnerProfileVersion profile, boolean replayed) {

  public PartnerProfileCommandResult {
    if (status < 200 || status > 299) {
      throw new IllegalArgumentException("partner command result must be successful");
    }
    Objects.requireNonNull(profile, "profile");
  }
}
