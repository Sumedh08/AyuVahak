import { describe, expect, it, vi } from "vitest";
import { analyticsScopes, createAnalyticsApiClient } from "./analytics";
import type { SessionProjection } from "./session";

const organizationId = "6d383f64-9f14-4cf6-a8a1-3e119e8fc882";
const facilityId = "9eb1d3e6-86c4-45aa-b6bb-e15596ee05ec";
const snapshotId = "4d9a7e33-d9f5-450a-9cd7-3b689d9d1d88";
const session: SessionProjection = {
  deploymentMode: "EMPTY",
  subject: {
    kind: "HUMAN",
    issuer: "https://identity.synthetic.invalid/issuer",
    identifierType: "subject",
    identifier: "SYN-ANALYST-001",
  },
  capabilities: [
    {
      authorityCode: "AnalyticsAggregateRead",
      scopes: [
        {
          kind: "ORGANIZATION",
          organization: {
            issuer: "urn:ayuvahak:directory",
            identifierType: "Organization",
            identifier: organizationId,
          },
        },
        {
          kind: "FACILITY",
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
    },
  ],
};

const completeZero = {
  snapshotId,
  indicatorKey: "SYN-INDICATOR-001",
  indicatorVersion: 1,
  periodStart: "2026-01-01",
  periodEndExclusive: "2026-02-01",
  timeZone: "UTC",
  completeness: "COMPLETE",
  value: "0",
  computedAt: "2026-02-01T00:00:00Z",
  revision: 1,
  revisionState: "PROVISIONAL",
  exportState: "ACKNOWLEDGED",
  exportResultClass: "IMPORTED",
};

describe("Analytics aggregate API", () => {
  it("derives only valid read scopes from the server capability projection", () => {
    const scopes = analyticsScopes(session);
    expect(scopes).toEqual([
      { key: `ORGANIZATION:${organizationId}`, kind: "ORGANIZATION", organizationId },
      {
        key: `FACILITY:${facilityId}`,
        kind: "FACILITY",
        organizationId,
        facilityId,
      },
    ]);
    expect(analyticsScopes({ ...session, capabilities: [] })).toEqual([]);
    expect(
      analyticsScopes({
        ...session,
        capabilities: [
          {
            authorityCode: "AnalyticsAggregateRead",
            scopes: [
              {
                kind: "ORGANIZATION",
                organization: {
                  issuer: "urn:other",
                  identifierType: "Organization",
                  identifier: organizationId,
                },
              },
            ],
          },
        ],
      }),
    ).toEqual([]);
  });

  it("sends only a scoped no-store request and preserves exact aggregate decimals", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({ items: [completeZero], page: 0, size: 50, hasMore: false }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    const client = createAnalyticsApiClient(async () => "synthetic-token", fetcher);
    const scope = analyticsScopes(session)[0];
    if (!scope) throw new Error("Synthetic organization scope is required");

    const result = await client.loadSnapshots(scope);

    expect(result.items[0]?.value).toBe("0");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/v1/analytics/snapshots?page=0&size=50",
      expect.objectContaining({
        method: "GET",
        credentials: "omit",
        cache: "no-store",
        headers: expect.objectContaining({
          Authorization: "Bearer synthetic-token",
          "X-AyuVahak-Execution-Scope": "ORGANIZATION",
          "X-AyuVahak-Organization-Id": organizationId,
        }),
      }),
    );
  });

  it("rejects a missing-source snapshot disguised with zero", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(
        JSON.stringify({
          items: [{ ...completeZero, completeness: "MISSING_SOURCE" }],
          page: 0,
          size: 50,
          hasMore: false,
        }),
        { status: 200 },
      ),
    );
    const scope = analyticsScopes(session)[0];
    if (!scope) throw new Error("Synthetic organization scope is required");
    await expect(
      createAnalyticsApiClient(async () => "synthetic-token", fetcher).loadSnapshots(scope),
    ).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  });

  it("rejects unexpected payload fields instead of rendering uncontrolled values", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(
        JSON.stringify({
          items: [{ ...completeZero, patientReference: "SYN-PATIENT-MUST-NOT-LEAK" }],
          page: 0,
          size: 50,
          hasMore: false,
        }),
        { status: 200 },
      ),
    );
    const scope = analyticsScopes(session)[0];
    if (!scope) throw new Error("Synthetic organization scope is required");
    await expect(
      createAnalyticsApiClient(async () => "synthetic-token", fetcher).loadSnapshots(scope),
    ).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  });
});
