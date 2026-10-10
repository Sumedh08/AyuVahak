package org.ayuvahak.transfer.domain;

import java.util.Set;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Transfer-owned authorities for origin request initiation and receiving decisions. */
public final class TransferAuthorities {

  private static final Set<ExecutionScope.Kind> ORIGIN_SCOPES =
      Set.of(ExecutionScope.Kind.ORGANIZATION, ExecutionScope.Kind.FACILITY);
  private static final FunctionalAuthority REQUEST =
      new FunctionalAuthority("TransferRequest", ORIGIN_SCOPES);
  private static final FunctionalAuthority REQUEST_EXPIRY =
      new FunctionalAuthority("TransferRequestExpiry", Set.of(ExecutionScope.Kind.ORGANIZATION));
  private static final FunctionalAuthority CLINICAL_SUITABILITY =
      new FunctionalAuthority("ClinicalSuitabilityAuthority", Set.of(ExecutionScope.Kind.FACILITY));
  private static final FunctionalAuthority OPERATIONAL_READINESS =
      new FunctionalAuthority(
          "OperationalReadinessAuthority", Set.of(ExecutionScope.Kind.FACILITY));
  private static final FunctionalAuthority ACCEPTANCE =
      new FunctionalAuthority("AcceptanceAuthority", Set.of(ExecutionScope.Kind.FACILITY));

  private TransferAuthorities() {}

  public static FunctionalAuthority request() {
    return REQUEST;
  }

  public static FunctionalAuthority requestExpiry() {
    return REQUEST_EXPIRY;
  }

  public static FunctionalAuthority clinicalSuitability() {
    return CLINICAL_SUITABILITY;
  }

  public static FunctionalAuthority operationalReadiness() {
    return OPERATIONAL_READINESS;
  }

  public static FunctionalAuthority acceptance() {
    return ACCEPTANCE;
  }
}
