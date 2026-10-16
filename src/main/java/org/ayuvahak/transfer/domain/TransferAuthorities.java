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
  private static final FunctionalAuthority RESERVATION_RECONCILIATION =
      new FunctionalAuthority(
          "TransferReservationReconciliation", Set.of(ExecutionScope.Kind.FACILITY));
  private static final FunctionalAuthority REFERRAL_CANCELLATION =
      new FunctionalAuthority("TransferReferralCancellation", ORIGIN_SCOPES);
  private static final FunctionalAuthority PROGRESS =
      new FunctionalAuthority("TransferProgressAuthority", Set.of(ExecutionScope.Kind.FACILITY));
  private static final FunctionalAuthority HANDOVER =
      new FunctionalAuthority("TransferHandoverAuthority", Set.of(ExecutionScope.Kind.FACILITY));
  private static final FunctionalAuthority COMPLETION =
      new FunctionalAuthority("TransferCompletionAuthority", Set.of(ExecutionScope.Kind.FACILITY));
  private static final FunctionalAuthority CLOSURE =
      new FunctionalAuthority("TransferJourneyClosureAuthority", ORIGIN_SCOPES);
  private static final FunctionalAuthority VIEW =
      new FunctionalAuthority("TransferViewAuthority", ORIGIN_SCOPES);
  private static final FunctionalAuthority PATIENT_CONTEXT_PARTICIPATION =
      new FunctionalAuthority(
          "TransferPatientContextParticipation", Set.of(ExecutionScope.Kind.FACILITY));
  private static final FunctionalAuthority REFERRAL_AMENDMENT_CHECK =
      new FunctionalAuthority("TransferReferralAmendmentCheck", ORIGIN_SCOPES);
  private static final FunctionalAuthority TRANSPORT_REQUEST_ELIGIBILITY =
      new FunctionalAuthority(
          "TransferTransportRequestEligibility", Set.of(ExecutionScope.Kind.FACILITY));
  private static final FunctionalAuthority REROUTE =
      new FunctionalAuthority("TransferRerouteAuthority", ORIGIN_SCOPES);
  private static final FunctionalAuthority TRANSPORT_DESTINATION_CHANGE_ELIGIBILITY =
      new FunctionalAuthority(
          "TransferTransportDestinationChangeEligibility", Set.of(ExecutionScope.Kind.FACILITY));

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

  public static FunctionalAuthority reservationReconciliation() {
    return RESERVATION_RECONCILIATION;
  }

  public static FunctionalAuthority referralCancellation() {
    return REFERRAL_CANCELLATION;
  }

  public static FunctionalAuthority progress() {
    return PROGRESS;
  }

  public static FunctionalAuthority handover() {
    return HANDOVER;
  }

  public static FunctionalAuthority completion() {
    return COMPLETION;
  }

  public static FunctionalAuthority closure() {
    return CLOSURE;
  }

  public static FunctionalAuthority view() {
    return VIEW;
  }

  public static FunctionalAuthority patientContextParticipation() {
    return PATIENT_CONTEXT_PARTICIPATION;
  }

  public static FunctionalAuthority referralAmendmentCheck() {
    return REFERRAL_AMENDMENT_CHECK;
  }

  public static FunctionalAuthority transportRequestEligibility() {
    return TRANSPORT_REQUEST_ELIGIBILITY;
  }

  public static FunctionalAuthority reroute() {
    return REROUTE;
  }

  public static FunctionalAuthority transportDestinationChangeEligibility() {
    return TRANSPORT_DESTINATION_CHANGE_ELIGIBILITY;
  }
}
