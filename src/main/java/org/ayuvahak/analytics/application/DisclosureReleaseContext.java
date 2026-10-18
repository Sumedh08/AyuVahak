package org.ayuvahak.analytics.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.ayuvahak.analytics.domain.DisclosureOutput;
import org.ayuvahak.analytics.domain.DisclosurePolicyVersion;
import org.ayuvahak.analytics.domain.DisclosureReservation;

/** Transaction-scoped locked history view; instances are not persisted or retained. */
public record DisclosureReleaseContext(
    DisclosurePolicyVersion policy,
    Set<UUID> lineageIds,
    List<DisclosureOutput> priorOutputs,
    Map<String, Long> priorCellValues,
    boolean historyComplete,
    long historyCheckpoint,
    Optional<DisclosureReservation> replay) {

  public DisclosureReleaseContext {
    lineageIds = Set.copyOf(lineageIds);
    priorOutputs = List.copyOf(priorOutputs);
    priorCellValues = Map.copyOf(priorCellValues);
    replay = replay == null ? Optional.empty() : replay;
  }
}
