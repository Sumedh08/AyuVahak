import { describe, expect, it, vi } from "vitest";
import { originScopesForAuthority } from "./owner";
import {
  createReferralMatchingApiClient,
  parseReferralDetail,
  type ReferralMatchingApiError,
} from "./referral-matching";
import type { SessionProjection } from "./session";

const organizationId = "6d383f64-9f14-4cf6-a8a1-3e119e8fc882";
const facilityId = "9eb1d3e6-86c4-45aa-b6bb-e15596ee05ec";
const referralId = "2e29d3f6-d75b-4f0c-b7f0-ace31692ddce";
const scope = {
  key: `FACILITY:${facilityId}`,
  kind: "FACILITY" as const,
  organizationId,
  facilityId,
};

const detailResponse = {
  id: referralId,
  status: "SUBMITTED",
  version: 2,
  origin: { organizationId, facilityId },
  patientReference: {
    issuer: "urn:synthetic:patient",
    resourceType: "Patient",
    identifier: "SYN-PATIENT-DO-NOT-RETAIN",
    encounterReference: null,
  },
  careRequirement: {
    requiredCareCapabilityCodes: ["SYN_CARDIOLOGY"],
    requestedStart: null,
    requestedEnd: null,
    permittedGeographyCodes: [],
    selectedDestinationOrganizationId: null,
    priorityCode: null,
  },
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-01T11:00:00Z",
  submittedRevision: { revisionNumber: 1 },
};

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("Referral and Matching scoped API client", () => {
  it("derives only recognized origin scopes from the requested server authority", () => {
    const session: SessionProjection = Object.assign(
      {
        subject: {
          kind: "HUMAN" as const,
          issuer: "https://identity.synthetic.invalid/issuer",
          identifierType: "subject" as const,
          identifier: "SYN-OPERATOR-001",
        },
        capabilities: [
          {
            authorityCode: "ReferralRead",
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
              {
                kind: "PLATFORM" as const,
              },
            ],
          },
        ],
      },
      { deploymentMode: "EMPTY" as const },
    );
    expect(originScopesForAuthority(session, "ReferralRead")).toEqual([scope]);
    expect(originScopesForAuthority(session, "ReferralCreate")).toEqual([]);
  });

  it("sends bearer and exact origin scope headers, and drops PatientReference from detail DTO", async () => {
    const fetcher = vi.fn().mockResolvedValue(response(detailResponse));
    const api = createReferralMatchingApiClient(async () => "synthetic-token", fetcher);
    const result = await api.getReferral(scope, referralId);
    const [, options] = fetcher.mock.calls[0] as [string, RequestInit];
    const headers = new Headers(options.headers);
    expect(fetcher).toHaveBeenCalledWith(
      `/api/v1/referrals/${referralId}`,
      expect.objectContaining({ credentials: "omit", cache: "no-store" }),
    );
    expect(headers.get("Authorization")).toBe("Bearer synthetic-token");
    expect(headers.get("X-AyuVahak-Execution-Scope")).toBe("FACILITY");
    expect(headers.get("X-AyuVahak-Organization-Id")).toBe(organizationId);
    expect(headers.get("X-AyuVahak-Facility-Id")).toBe(facilityId);
    expect(result.submittedRevisionNumber).toBe(1);
    expect("patientReference" in result).toBe(false);
    expect(JSON.stringify(result)).not.toContain("SYN-PATIENT-DO-NOT-RETAIN");
  });

  it("rejects a response that crosses the selected organization scope", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      response({
        items: [
          {
            id: referralId,
            status: "SUBMITTED",
            version: 2,
            origin: { organizationId: "a7e042f1-3b3f-4b15-8625-1609497b5ec4", facilityId },
            relevantAt: "2026-10-01T10:00:00Z",
            submittedAt: "2026-10-01T10:30:00Z",
          },
        ],
        page: 0,
        size: 25,
        hasMore: false,
      }),
    );
    const api = createReferralMatchingApiClient(async () => "synthetic-token", fetcher);
    await expect(api.listReferrals(scope, 0)).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  });

  it("classifies authorization failures without reading response details", async () => {
    const fetcher = vi.fn().mockResolvedValue(response({ detail: "not for UI" }, 403));
    const api = createReferralMatchingApiClient(async () => "synthetic-token", fetcher);
    await expect(api.getReferral(scope, referralId)).rejects.toMatchObject({
      code: "FORBIDDEN",
      status: 403,
    } satisfies Partial<ReferralMatchingApiError>);
  });

  it("rejects malformed owner responses", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(response({ ...detailResponse, careRequirement: null }));
    const api = createReferralMatchingApiClient(async () => "synthetic-token", fetcher);
    await expect(api.getReferral(scope, referralId)).rejects.toMatchObject({
      code: "INVALID_RESPONSE",
    });
    expect(parseReferralDetail({ ...detailResponse, id: "not-an-id" })).toBeNull();
  });

  it("sends the Referral creation input directly with an idempotency key", async () => {
    const fetcher = vi.fn().mockResolvedValue(response(detailResponse, 201));
    const api = createReferralMatchingApiClient(async () => "synthetic-token", fetcher);
    await api.createReferral(scope, {
      patientReference: {
        issuer: "urn:synthetic:patient",
        resourceType: "Patient",
        identifier: "SYN-PATIENT-CREATE-001",
      },
      careRequirement: {
        requiredCareCapabilityCodes: ["SYN_CARDIOLOGY"],
        requestedStart: null,
        requestedEnd: null,
        permittedGeographyCodes: [],
        selectedDestinationOrganizationId: null,
        priorityCode: null,
      },
    });
    const [, options] = fetcher.mock.calls[0] as [string, RequestInit];
    const headers = new Headers(options.headers);
    expect(options.method).toBe("POST");
    expect(headers.get("Idempotency-Key")).toMatch(/^[0-9a-f-]{36}$/i);
    expect(JSON.parse(String(options.body))).toMatchObject({
      patientReference: { identifier: "SYN-PATIENT-CREATE-001" },
    });
  });

  it("requests Matching from a submitted revision and validates deterministic output", async () => {
    const snapshotId = "04d6cf44-381d-446d-a4d9-32df2adee15d";
    const candidateId = "8427cfb8-4f59-4ef7-9327-15d977f7008b";
    const fetcher = vi.fn().mockResolvedValue(
      response({
        id: snapshotId,
        referralId,
        referralRevision: 1,
        generatedAt: "2026-10-01T12:00:00Z",
        applicableUntil: "2026-10-01T13:00:00Z",
        outcome: "COMPLETE",
        reasonCodes: [],
        candidates: [
          {
            facility: {
              issuer: "urn:ayuvahak:directory",
              resourceType: "Facility",
              identifier: candidateId,
            },
            organization: {
              issuer: "urn:ayuvahak:directory",
              resourceType: "Organization",
              identifier: organizationId,
            },
            status: "RANKED_ELIGIBLE",
            rank: 1,
            reasons: [{ code: "CAPABILITY_MATCH", subjectCode: "SYN_CARDIOLOGY" }],
            directoryCapabilityAssessments: [],
            evidence: [],
            measure: {
              kind: "STRAIGHT_LINE_DISTANCE",
              sourceCode: "SYN_DISTANCE",
              value: 8.2,
              unit: "km",
              observedAt: "2026-10-01T11:00:00Z",
              validUntil: "2026-10-01T13:00:00Z",
              sourceSystem: null,
            },
          },
        ],
      }),
    );
    const api = createReferralMatchingApiClient(async () => "synthetic-token", fetcher);
    const result = await api.generateRecommendation(scope, referralId, 1);
    const [, options] = fetcher.mock.calls[0] as [string, RequestInit];
    const headers = new Headers(options.headers);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/v1/recommendations",
      expect.objectContaining({ method: "POST" }),
    );
    expect(headers.get("X-AyuVahak-Execution-Scope")).toBe("FACILITY");
    expect(JSON.parse(String(options.body))).toEqual({ referralId, revisionNumber: 1 });
    expect(result.candidates[0]).toMatchObject({
      rank: 1,
      reasons: [{ code: "CAPABILITY_MATCH" }],
    });
  });
});
