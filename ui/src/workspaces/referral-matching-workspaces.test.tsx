import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { type ReferralMatchingApiClient, ReferralMatchingApiError } from "../api/referral-matching";
import type { SessionProjection } from "../api/session";
import { MatchingWorkspace } from "./MatchingWorkspace";
import { ReferralWorkspace } from "./ReferralWorkspace";

const organizationId = "6d383f64-9f14-4cf6-a8a1-3e119e8fc882";
const facilityId = "9eb1d3e6-86c4-45aa-b6bb-e15596ee05ec";
const referralId = "2e29d3f6-d75b-4f0c-b7f0-ace31692ddce";
const candidateFacility = "8427cfb8-4f59-4ef7-9327-15d977f7008b";
const session: SessionProjection = Object.assign(
  {
    subject: {
      kind: "HUMAN" as const,
      issuer: "https://identity.synthetic.invalid/issuer",
      identifierType: "subject" as const,
      identifier: "SYN-OPERATOR-001",
    },
    capabilities: [
      "ReferralRead",
      "ReferralCreate",
      "RecommendationRead",
      "RecommendationRequest",
    ].map((authorityCode) => ({
      authorityCode,
      scopes: [
        {
          kind: "FACILITY" as const,
          organization: {
            issuer: "urn:ayuvahak:directory",
            identifierType: "Organization",
            identifier: organizationId,
          },
          facility: {
            issuer: "urn:ayuvahak:directory",
            identifierType: "Facility",
            identifier: facilityId,
          },
        },
      ],
    })),
  },
  { deploymentMode: "EMPTY" as const },
);

const summary = {
  id: referralId,
  status: "SUBMITTED" as const,
  version: 2,
  origin: { organizationId, facilityId },
  relevantAt: "2026-10-01T10:00:00Z",
  submittedAt: "2026-10-01T10:30:00Z",
};
const page = { items: [summary], page: 0, size: 25, hasMore: false };
const detail = {
  id: referralId,
  status: "SUBMITTED" as const,
  version: 2,
  origin: { organizationId, facilityId },
  careRequirement: {
    requiredCareCapabilityCodes: ["SYN_CARDIOLOGY"],
    requestedStart: null,
    requestedEnd: null,
    permittedGeographyCodes: [],
    selectedDestinationOrganizationId: null,
    priorityCode: null,
  },
  submittedRevisionNumber: 1,
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-01T11:00:00Z",
};
const recommendation = {
  id: "04d6cf44-381d-446d-a4d9-32df2adee15d",
  referralId,
  referralRevision: 1,
  generatedAt: "2026-10-01T12:00:00Z",
  applicableUntil: "2026-10-01T13:00:00Z",
  outcome: "COMPLETE" as const,
  reasonCodes: [],
  candidates: [
    {
      facility: candidateFacility,
      organization: "a7e042f1-3b3f-4b15-8625-1609497b5ec4",
      status: "RANKED_ELIGIBLE" as const,
      rank: 1,
      reasons: [{ code: "CAPABILITY_MATCH", subjectCode: "SYN_CARDIOLOGY" }],
      measure: { kind: "STRAIGHT_LINE_DISTANCE", value: 8.2, unit: "km" },
    },
  ],
};

function apiClient(overrides: Partial<ReferralMatchingApiClient> = {}): ReferralMatchingApiClient {
  return {
    listReferrals: vi.fn().mockResolvedValue(page),
    getReferral: vi.fn().mockResolvedValue(detail),
    createReferral: vi.fn().mockResolvedValue(detail),
    getRecommendationHistory: vi
      .fn()
      .mockResolvedValue({ referralId, items: [], latestRecommendationId: null }),
    generateRecommendation: vi.fn().mockResolvedValue(recommendation),
    ...overrides,
  };
}

describe("Referral workspace", () => {
  it("announces the Referral loading state until the owner responds", async () => {
    let resolvePage: ((value: typeof page) => void) | undefined;
    const api = apiClient({
      listReferrals: vi.fn().mockImplementation(
        () =>
          new Promise<typeof page>((resolve) => {
            resolvePage = resolve;
          }),
      ),
    });
    render(
      <ReferralWorkspace
        session={session}
        getAccessToken={async () => "synthetic-token"}
        apiClient={api}
      />,
    );
    expect(screen.getByRole("status")).toHaveTextContent(/loading referral records/i);
    resolvePage?.(page);
    expect(await screen.findByRole("button", { name: "Open Referral" })).toBeInTheDocument();
  });

  it("loads scoped records, opens detail, and never renders PatientReference", async () => {
    const api = apiClient();
    render(
      <ReferralWorkspace
        session={session}
        getAccessToken={async () => "synthetic-token"}
        apiClient={api}
      />,
    );
    expect(await screen.findByRole("button", { name: "Open Referral" })).toBeInTheDocument();
    expect(api.listReferrals).toHaveBeenCalledWith(
      { key: `FACILITY:${facilityId}`, kind: "FACILITY", organizationId, facilityId },
      0,
      undefined,
      expect.any(AbortSignal),
    );
    fireEvent.click(screen.getByRole("button", { name: "Open Referral" }));
    expect(
      await screen.findByRole("heading", { name: "SUBMITTED · version 2" }),
    ).toBeInTheDocument();
    expect(api.getReferral).toHaveBeenCalledWith(
      expect.objectContaining({ facilityId }),
      referralId,
      expect.any(AbortSignal),
    );
    expect(screen.queryByText("SYN-PATIENT-DO-NOT-RETAIN")).not.toBeInTheDocument();
  });

  it("shows an owner-returned empty list and an explicit forbidden state", async () => {
    const emptyApi = apiClient({
      listReferrals: vi.fn().mockResolvedValue({ ...page, items: [] }),
    });
    const view = render(
      <ReferralWorkspace
        session={session}
        getAccessToken={async () => "synthetic-token"}
        apiClient={emptyApi}
      />,
    );
    expect(
      await screen.findByRole("heading", { name: "No Referrals were returned" }),
    ).toBeInTheDocument();
    view.unmount();
    const deniedApi = apiClient({
      listReferrals: vi.fn().mockRejectedValue(new ReferralMatchingApiError(403, "FORBIDDEN")),
    });
    render(
      <ReferralWorkspace
        session={session}
        getAccessToken={async () => "synthetic-token"}
        apiClient={deniedApi}
      />,
    );
    expect(
      await screen.findByRole("heading", { name: "Referral access was denied" }),
    ).toBeInTheDocument();
  });

  it("exposes create only from the granted scope and submits the owner contract", async () => {
    const api = apiClient({ listReferrals: vi.fn().mockResolvedValue({ ...page, items: [] }) });
    render(
      <ReferralWorkspace
        session={session}
        getAccessToken={async () => "synthetic-token"}
        apiClient={api}
      />,
    );
    fireEvent.change(screen.getByLabelText("PatientReference issuer"), {
      target: { value: "urn:synthetic:patient" },
    });
    fireEvent.change(screen.getByLabelText("PatientReference resource type"), {
      target: { value: "Patient" },
    });
    fireEvent.change(screen.getByLabelText("PatientReference identifier"), {
      target: { value: "SYN-PATIENT-NEW-001" },
    });
    fireEvent.change(screen.getByLabelText("Required care capability code"), {
      target: { value: "SYN_CARDIOLOGY" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Create Referral draft" }));
    expect(await screen.findByText(/referral draft created/i)).toBeInTheDocument();
    expect(api.createReferral).toHaveBeenCalledWith(
      expect.objectContaining({ facilityId }),
      expect.objectContaining({
        patientReference: {
          issuer: "urn:synthetic:patient",
          resourceType: "Patient",
          identifier: "SYN-PATIENT-NEW-001",
        },
      }),
    );
    expect(screen.queryByDisplayValue("SYN-PATIENT-NEW-001")).not.toBeInTheDocument();
  });
});

describe("deterministic Matching workspace", () => {
  it("announces the Matching worklist loading state until the owner responds", async () => {
    let resolvePage: ((value: typeof page) => void) | undefined;
    const api = apiClient({
      listReferrals: vi.fn().mockImplementation(
        () =>
          new Promise<typeof page>((resolve) => {
            resolvePage = resolve;
          }),
      ),
    });
    render(
      <MatchingWorkspace
        session={session}
        getAccessToken={async () => "synthetic-token"}
        apiClient={api}
      />,
    );
    expect(screen.getByRole("status")).toHaveTextContent(/loading submitted referrals/i);
    resolvePage?.(page);
    expect(
      await screen.findByRole("button", { name: "Review submitted revision" }),
    ).toBeInTheDocument();
  });

  it("loads submitted Referral data and presents structured deterministic reasons", async () => {
    const api = apiClient({
      listReferrals: vi.fn().mockResolvedValue(page),
      getRecommendationHistory: vi.fn().mockResolvedValue({
        referralId,
        items: [recommendation],
        latestRecommendationId: recommendation.id,
      }),
    });
    render(
      <MatchingWorkspace
        session={session}
        getAccessToken={async () => "synthetic-token"}
        apiClient={api}
      />,
    );
    expect(
      await screen.findByRole("button", { name: "Review submitted revision" }),
    ).toBeInTheDocument();
    expect(api.listReferrals).toHaveBeenCalledWith(
      expect.objectContaining({ facilityId }),
      0,
      "SUBMITTED",
      expect.any(AbortSignal),
    );
    fireEvent.click(screen.getByRole("button", { name: "Review submitted revision" }));
    expect(await screen.findByRole("heading", { name: "Revision 1" })).toBeInTheDocument();
    expect(
      await screen.findByText(/reasons: capability_match \(syn_cardiology\)/i),
    ).toBeInTheDocument();
    expect(screen.queryByText("SYN-PATIENT-DO-NOT-RETAIN")).not.toBeInTheDocument();
    expect(screen.getByText(/not a clinical decision/i)).toBeInTheDocument();
  });

  it("generates only from the submitted revision and the explicit matching grant", async () => {
    const api = apiClient({
      getRecommendationHistory: vi
        .fn()
        .mockResolvedValue({ referralId, items: [], latestRecommendationId: null }),
    });
    render(
      <MatchingWorkspace
        session={session}
        getAccessToken={async () => "synthetic-token"}
        apiClient={api}
      />,
    );
    fireEvent.click(await screen.findByRole("button", { name: "Review submitted revision" }));
    fireEvent.click(
      await screen.findByRole("button", { name: "Request deterministic recommendation" }),
    );
    expect(api.generateRecommendation).toHaveBeenCalledWith(
      expect.objectContaining({ facilityId }),
      referralId,
      1,
    );
    expect(
      await screen.findByText(/reasons: capability_match \(syn_cardiology\)/i),
    ).toBeInTheDocument();
  });

  it("preserves empty and unauthorized worklist states", async () => {
    const emptyApi = apiClient({
      listReferrals: vi.fn().mockResolvedValue({ ...page, items: [] }),
    });
    const view = render(
      <MatchingWorkspace
        session={session}
        getAccessToken={async () => "synthetic-token"}
        apiClient={emptyApi}
      />,
    );
    expect(
      await screen.findByRole("heading", { name: "No submitted Referrals were returned" }),
    ).toBeInTheDocument();
    view.unmount();
    const deniedApi = apiClient({
      listReferrals: vi.fn().mockRejectedValue(new ReferralMatchingApiError(403, "FORBIDDEN")),
    });
    render(
      <MatchingWorkspace
        session={session}
        getAccessToken={async () => "synthetic-token"}
        apiClient={deniedApi}
      />,
    );
    expect(
      await screen.findByRole("heading", { name: "Referral worklist access was denied" }),
    ).toBeInTheDocument();
  });
});
