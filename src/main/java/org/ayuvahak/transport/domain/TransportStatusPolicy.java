package org.ayuvahak.transport.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Provider-neutral monotonic transition policy for the Transport request lifecycle. */
public final class TransportStatusPolicy {

  private static final Map<TransportRequest.Status, Set<TransportRequest.Status>> NEXT =
      nextStates();

  private TransportStatusPolicy() {}

  public static boolean permits(TransportRequest.Status current, TransportRequest.Status next) {
    return NEXT.getOrDefault(current, Set.of()).contains(next);
  }

  private static Map<TransportRequest.Status, Set<TransportRequest.Status>> nextStates() {
    EnumMap<TransportRequest.Status, Set<TransportRequest.Status>> states =
        new EnumMap<>(TransportRequest.Status.class);
    states.put(
        TransportRequest.Status.REQUESTED,
        EnumSet.of(
            TransportRequest.Status.AWAITING_ASSIGNMENT, TransportRequest.Status.UNFULFILLED));
    states.put(
        TransportRequest.Status.AWAITING_ASSIGNMENT,
        EnumSet.of(TransportRequest.Status.ASSIGNED, TransportRequest.Status.UNFULFILLED));
    states.put(
        TransportRequest.Status.ASSIGNED,
        EnumSet.of(
            TransportRequest.Status.EN_ROUTE_TO_ORIGIN, TransportRequest.Status.INTERRUPTED));
    states.put(
        TransportRequest.Status.EN_ROUTE_TO_ORIGIN,
        EnumSet.of(TransportRequest.Status.AT_ORIGIN, TransportRequest.Status.INTERRUPTED));
    states.put(
        TransportRequest.Status.AT_ORIGIN,
        EnumSet.of(TransportRequest.Status.PATIENT_ONBOARD, TransportRequest.Status.INTERRUPTED));
    states.put(
        TransportRequest.Status.PATIENT_ONBOARD,
        EnumSet.of(
            TransportRequest.Status.EN_ROUTE_TO_DESTINATION, TransportRequest.Status.INTERRUPTED));
    states.put(
        TransportRequest.Status.EN_ROUTE_TO_DESTINATION,
        EnumSet.of(
            TransportRequest.Status.ARRIVED_DESTINATION, TransportRequest.Status.INTERRUPTED));
    states.put(
        TransportRequest.Status.ARRIVED_DESTINATION, EnumSet.of(TransportRequest.Status.COMPLETED));
    states.put(
        TransportRequest.Status.INTERRUPTED,
        EnumSet.of(TransportRequest.Status.COMPLETED, TransportRequest.Status.UNFULFILLED));
    return Map.copyOf(states);
  }
}
