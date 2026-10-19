package org.ayuvahak.referral.domain;

import java.util.Set;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Referral-owned functional authorities for origin-scoped commands and reads. */
public final class ReferralAuthorities {

  private static final Set<ExecutionScope.Kind> ORIGIN_SCOPES =
      Set.of(ExecutionScope.Kind.ORGANIZATION, ExecutionScope.Kind.FACILITY);
  private static final FunctionalAuthority CREATE =
      new FunctionalAuthority("ReferralCreate", ORIGIN_SCOPES);
  private static final FunctionalAuthority EDIT =
      new FunctionalAuthority("ReferralEdit", ORIGIN_SCOPES);
  private static final FunctionalAuthority SUBMIT =
      new FunctionalAuthority("ReferralSubmit", ORIGIN_SCOPES);
  private static final FunctionalAuthority AMEND =
      new FunctionalAuthority("ReferralAmend", ORIGIN_SCOPES);
  private static final FunctionalAuthority RESOLVE =
      new FunctionalAuthority("ReferralResolve", ORIGIN_SCOPES);
  private static final FunctionalAuthority CANCEL =
      new FunctionalAuthority("ReferralCancel", ORIGIN_SCOPES);
  private static final FunctionalAuthority READ =
      new FunctionalAuthority("ReferralRead", ORIGIN_SCOPES);
  private static final FunctionalAuthority PATIENT_CONTEXT_DISCLOSURE =
      new FunctionalAuthority(
          "ReferralPatientContextDisclosure", Set.of(ExecutionScope.Kind.FACILITY));
  private static final FunctionalAuthority REPORTING_SNAPSHOT =
      new FunctionalAuthority(
          "ReferralReportingSnapshot",
          Set.of(ExecutionScope.Kind.ORGANIZATION, ExecutionScope.Kind.FACILITY));
  private static final FunctionalAuthority RETENTION =
      new FunctionalAuthority("ReferralRetention", ORIGIN_SCOPES);
  private static final FunctionalAuthority RETENTION_REAPPLICATION =
      new FunctionalAuthority("ReferralRetentionReapplication", Set.of(ExecutionScope.Kind.PLATFORM));

  private ReferralAuthorities() {}

  public static FunctionalAuthority create() {
    return CREATE;
  }

  public static FunctionalAuthority edit() {
    return EDIT;
  }

  public static FunctionalAuthority submit() {
    return SUBMIT;
  }

  public static FunctionalAuthority amend() {
    return AMEND;
  }

  public static FunctionalAuthority resolve() {
    return RESOLVE;
  }

  public static FunctionalAuthority cancel() {
    return CANCEL;
  }

  public static FunctionalAuthority read() {
    return READ;
  }

  public static FunctionalAuthority patientContextDisclosure() {
    return PATIENT_CONTEXT_DISCLOSURE;
  }

  public static FunctionalAuthority reportingSnapshot() {
    return REPORTING_SNAPSHOT;
  }

  public static FunctionalAuthority retention() {
    return RETENTION;
  }

  public static FunctionalAuthority retentionReapplication() {
    return RETENTION_REAPPLICATION;
  }
}
