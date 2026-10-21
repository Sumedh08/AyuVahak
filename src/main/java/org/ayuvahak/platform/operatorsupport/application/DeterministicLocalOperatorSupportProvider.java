package org.ayuvahak.platform.operatorsupport.application;

import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** No-model deterministic adapter that returns only reviewed excerpts for local acceptance. */
@Component
public final class DeterministicLocalOperatorSupportProvider implements OperatorSupportProvider {

  @Override
  public ProviderResponse answer(
      OperatorSupportQuestionKey questionKey, List<ApprovedOperatorSupportSource> approvedSources) {
    Objects.requireNonNull(questionKey, "question key");
    approvedSources = List.copyOf(approvedSources);
    if (approvedSources.isEmpty()) {
      return ProviderResponse.abstained(OperatorSupportResponse.ReasonCode.NO_APPROVED_EVIDENCE);
    }
    String answer =
        approvedSources.stream()
            .map(ApprovedOperatorSupportSource::excerpt)
            .reduce((left, right) -> left + "\n\n" + right)
            .orElse("");
    List<String> sourceIds =
        approvedSources.stream().map(ApprovedOperatorSupportSource::sourceId).toList();
    return ProviderResponse.advisory(answer, sourceIds);
  }
}
