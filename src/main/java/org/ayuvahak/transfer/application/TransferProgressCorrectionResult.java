package org.ayuvahak.transfer.application;

import java.util.Objects;
import org.ayuvahak.transfer.domain.TransferProgressCorrection;

/** Safe result of recording or replaying one Transfer progress correction. */
public record TransferProgressCorrectionResult(
    TransferProgressCorrection correction, boolean commandReplayed) {

  public TransferProgressCorrectionResult {
    Objects.requireNonNull(correction, "progress correction");
  }
}
