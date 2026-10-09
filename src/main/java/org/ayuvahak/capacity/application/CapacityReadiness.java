package org.ayuvahak.capacity.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.capacity.domain.CapacityReadinessEvaluation;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/**
 * Narrow Capacity-owned contract for evaluating explicit dimensions using one query-time view of
 * current evidence and each dimension's active, versioned source/freshness/readiness policy.
 *
 * <p>Callers supply only CapacityDimension codes. Capacity never translates CareCapability or
 * Referral data. A READY result means every requested dimension has current, attributed evidence
 * that passes its configured rule; it is not a clinical assessment, Acceptance, or reservation.
 * Missing evidence is UNKNOWN, and an unavailable Capacity store fails as unavailable.
 */
@FunctionalInterface
public interface CapacityReadiness {

  CapacityReadinessEvaluation evaluateOperationalReadiness(
      ExecutionContext context,
      ExternalReference facility,
      List<String> requiredDimensionCodes,
      Optional<Instant> evaluationTime);
}
