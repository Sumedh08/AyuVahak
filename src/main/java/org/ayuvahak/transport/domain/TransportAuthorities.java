package org.ayuvahak.transport.domain;

import java.util.Set;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Narrow Transport permissions used by owner-validated coordination commands. */
public final class TransportAuthorities {

  private static final Set<ExecutionScope.Kind> FACILITY_SCOPE =
      Set.of(ExecutionScope.Kind.FACILITY);
  private static final FunctionalAuthority REQUEST =
      new FunctionalAuthority("TransportRequest", FACILITY_SCOPE);
  private static final FunctionalAuthority DISPATCH_COORDINATION =
      new FunctionalAuthority("TransportDispatchCoordinationAuthority", FACILITY_SCOPE);
  private static final FunctionalAuthority STATUS =
      new FunctionalAuthority("TransportStatusAuthority", FACILITY_SCOPE);
  private static final FunctionalAuthority CANCELLATION =
      new FunctionalAuthority("TransportCancellationAuthority", FACILITY_SCOPE);
  private static final FunctionalAuthority DESTINATION_CHANGE =
      new FunctionalAuthority("TransportDestinationChangeAuthority", FACILITY_SCOPE);

  private TransportAuthorities() {}

  public static FunctionalAuthority request() {
    return REQUEST;
  }

  public static FunctionalAuthority dispatchCoordination() {
    return DISPATCH_COORDINATION;
  }

  public static FunctionalAuthority status() {
    return STATUS;
  }

  public static FunctionalAuthority cancellation() {
    return CANCELLATION;
  }

  public static FunctionalAuthority destinationChange() {
    return DESTINATION_CHANGE;
  }
}
