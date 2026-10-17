package org.ayuvahak.matching.adapter.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.ayuvahak.matching.domain.RemoteCapacityEvidence;

/** Canonical source-content fingerprint; local receipt time is deliberately excluded. */
final class RemoteCapacityEvidenceFingerprint {

  private RemoteCapacityEvidenceFingerprint() {}

  static String calculate(RemoteCapacityEvidence evidence) {
    String canonical =
        String.join(
            "\n",
            evidence.facility().deploymentId().value(),
            evidence.facility().context(),
            evidence.facility().resourceId().toString(),
            evidence.dimensionCode(),
            Long.toString(evidence.sourceSequence()),
            Long.toString(evidence.dimensionVersion()),
            Long.toString(evidence.policyVersion()),
            evidence.sourceProfileVersion(),
            evidence.evidenceId(),
            evidence.outcome().name(),
            evidence.freshness().name(),
            Boolean.toString(evidence.complete()),
            evidence.quantity().map(value -> value.stripTrailingZeros().toPlainString()).orElse(""),
            evidence.state().orElse(""),
            evidence.unit().orElse(""),
            evidence.observedAt().toString(),
            evidence.validUntil().toString());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
