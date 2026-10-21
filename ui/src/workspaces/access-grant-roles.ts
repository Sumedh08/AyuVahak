export interface GrantRoleTemplate {
  readonly key: string;
  readonly label: string;
  readonly description: string;
  readonly scope: "PLATFORM" | "ORGANIZATION" | "FACILITY";
  readonly authorities: readonly string[];
}

/** Presets expand only to existing backend authorities; the backend remains authoritative. */
export const GRANT_ROLE_TEMPLATES: readonly GrantRoleTemplate[] = [
  {
    key: "platform-access-administrator",
    label: "Platform access administrator",
    description: "Can administer platform access grants. This does not create IdP accounts.",
    scope: "PLATFORM",
    authorities: ["AccessGrantAdministration"],
  },
  {
    key: "organization-directory-administrator",
    label: "Organization Directory administrator",
    description: "Can curate Directory records within one organization scope.",
    scope: "ORGANIZATION",
    authorities: ["DirectoryCuration"],
  },
  {
    key: "referral-coordinator-organization",
    label: "Referral coordinator · organization",
    description: "Can work with origin Referrals, deterministic Matching and Transfer initiation.",
    scope: "ORGANIZATION",
    authorities: [
      "ReferralRead",
      "ReferralCreate",
      "ReferralSubmit",
      "RecommendationRead",
      "RecommendationRequest",
      "TransferViewAuthority",
      "TransferRequest",
    ],
  },
  {
    key: "referral-coordinator-facility",
    label: "Referral coordinator · facility",
    description: "Facility-scoped Referral, Matching and Transfer coordination permissions.",
    scope: "FACILITY",
    authorities: [
      "ReferralRead",
      "ReferralCreate",
      "ReferralSubmit",
      "RecommendationRead",
      "RecommendationRequest",
      "TransferViewAuthority",
      "TransferRequest",
      "TransferProgressAuthority",
    ],
  },
  {
    key: "receiving-coordinator",
    label: "Receiving coordinator · facility",
    description:
      "Can review receiving requests and record facility-scoped readiness and acceptance.",
    scope: "FACILITY",
    authorities: [
      "TransferViewAuthority",
      "ClinicalSuitabilityAuthority",
      "OperationalReadinessAuthority",
      "AcceptanceAuthority",
      "TransferReservationReconciliation",
      "TransferHandoverAuthority",
      "TransferCompletionAuthority",
    ],
  },
  {
    key: "capacity-manager",
    label: "Capacity manager · facility",
    description: "Can review Capacity evidence, reporting and reservations for one facility.",
    scope: "FACILITY",
    authorities: ["CapacityEvidenceReview", "CapacityReporting", "CapacityReservation"],
  },
  {
    key: "transport-coordinator",
    label: "Transport coordinator · facility",
    description: "Can coordinate Transport requests and status within one facility scope.",
    scope: "FACILITY",
    authorities: [
      "TransferViewAuthority",
      "TransportRequestAuthority",
      "TransportDispatchCoordinationAuthority",
      "TransportStatusAuthority",
    ],
  },
  {
    key: "analyst",
    label: "Operational analyst · organization",
    description:
      "Read-only access to approved aggregate snapshots. No transactional workflow authority.",
    scope: "ORGANIZATION",
    authorities: ["AnalyticsAggregateRead"],
  },
];
