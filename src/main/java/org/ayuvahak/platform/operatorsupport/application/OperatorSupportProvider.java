package org.ayuvahak.platform.operatorsupport.application;

import java.util.List;

/** Narrow advisory port: the only inputs are a finite topic key and approved source excerpts. */
@FunctionalInterface
public interface OperatorSupportProvider {

  ProviderResponse answer(
      OperatorSupportQuestionKey questionKey, List<ApprovedOperatorSupportSource> approvedSources);
}
