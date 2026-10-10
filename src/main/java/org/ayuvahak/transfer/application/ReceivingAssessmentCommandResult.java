package org.ayuvahak.transfer.application;

import java.util.Objects;
import org.ayuvahak.transfer.domain.ReceivingAssessment;

/** Stable result of recording or replaying a receiving assessment. */
public record ReceivingAssessmentCommandResult(
    ReceivingAssessment assessment, boolean commandReplayed) {

  public ReceivingAssessmentCommandResult {
    Objects.requireNonNull(assessment, "assessment");
  }
}
