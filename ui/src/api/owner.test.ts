import { describe, expect, it, vi } from "vitest";
import {
  capacityScopes,
  createOwnerApiClient,
  directoryScopes,
  type OwnerApiError,
  parseCapacityCurrent,
  parseDirectoryFacilityPage,
} from "./owner";
import type { SessionProjection } from "./session";

const organizationId = "6d383f64-9f14-4cf6-a8a1-3e119e8fc882";
const facilityId = "9eb1d3e6-86c4-45aa-b6bb-e15596ee05ec";

const session: SessionProjection = {
  subject: {
    kind: "HUMAN",
    issuer: "https://identity.synthetic.invalid/issuer",
    identifierType: "subject",
    identifier: "SYN-OPERATOR-001",
  },
  capabilities: [
    {
      authorityCode: "DirectoryRead",
      scopes: [
        { kind: "PLATFORM" },
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
    {
      authorityCode: "CapacityEvidenceReview",
      scopes: [
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

const facilityPage = {
  mode: "DIRECTORY_FILTERED",
  facilities: [
    {
      facilityId,
      managingOrganizationId: organizationId,
      facilityName: "Synthetic Facility A",
      status: "ACTIVE",
      version: 2,
      source: {
        issuer: "urn:synthetic:registry",
        resourceType: "Facility",
        reference: "SYN-FACILITY-A",
      },
      provenance: {
        method: "SYNTHETIC_FIXTURE",
        observedAt: "2026-10-20T10:00:00Z",
        recordedAt: "2026-10-20T10:00:00Z",
        reasonCode: "SYNTHETIC_TEST",
      },
      location: null,
      distanceMeters: null,
    },
  ],
  unlocated: [],
  page: 0,
  size: 25,
  hasMore: false,
  hasMoreUnlocated: false,
};

const unknownCapacity = {
  facilityId,
  evaluatedAt: "2026-10-21T10:00:00Z",
  dimensions: [
    {
      dimensionCode: "SYN-BEDS",
      freshness: "UNKNOWN",
      dimensionVersion: null,
      policyVersion: null,
      projectionVersion: "capacity-projection-v1",
      valueKind: null,
      quantity: null,
      state: null,
      unit: null,
      evidence: [],
      fallbackUsed: false,
      reasonCodes: ["CAPACITY_EVIDENCE_UNKNOWN"],
    },
  ],
};

describe("owner API session scope mapping", () => {
  it("creates only Directory scopes whose issuer, resource type, and ID match owner conventions", () => {
    expect(directoryScopes(session)).toEqual([
      { key: "PLATFORM", kind: "PLATFORM" },
      { key: `FACILITY:${facilityId}`, kind: "FACILITY", organizationId, facilityId },
    ]);
    expect(capacityScopes(session)).toEqual([
      { key: `FACILITY:${facilityId}`, kind: "FACILITY", organizationId, facilityId },
    ]);
  });

  it("does not turn an unrelated or malformed scope reference into an owner request", () => {
    const unrelated: SessionProjection = {
      ...session,
      capabilities: [
        {
          authorityCode: "DirectoryRead",
          scopes: [
            {
              kind: "FACILITY",
              organization: {
                issuer: "urn:other:directory",
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
    expect(directoryScopes(unrelated)).toEqual([]);
  });
});

describe("owner API response contracts", () => {
  it("validates the Directory facility page returned by the owner endpoint", () => {
    expect(parseDirectoryFacilityPage(facilityPage)).toMatchObject({
      facilities: [{ facilityId, facilityName: "Synthetic Facility A", status: "ACTIVE" }],
      page: 0,
      size: 25,
      hasMore: false,
    });
    expect(
      parseDirectoryFacilityPage({
        ...facilityPage,
        facilities: [{ ...facilityPage.facilities[0], facilityName: 4 }],
      }),
    ).toBeNull();
  });

  it("preserves Capacity unknown and numeric zero as distinct values", () => {
    expect(parseCapacityCurrent(unknownCapacity)?.dimensions[0]).toMatchObject({
      freshness: "UNKNOWN",
      quantity: null,
    });
    const zero = {
      ...unknownCapacity,
      dimensions: [
        {
          ...unknownCapacity.dimensions[0],
          freshness: "CURRENT",
          dimensionVersion: 1,
          policyVersion: 1,
          valueKind: "QUANTITY",
          quantity: 0,
          unit: "beds",
        },
      ],
    };
    expect(parseCapacityCurrent(zero)?.dimensions[0]).toMatchObject({
      freshness: "CURRENT",
      quantity: 0,
    });
  });

  it("rejects an unrecognized freshness state rather than guessing", () => {
    expect(
      parseCapacityCurrent({
        ...unknownCapacity,
        dimensions: [{ ...unknownCapacity.dimensions[0], freshness: "AVAILABLE" }],
      }),
    ).toBeNull();
  });
});

describe("same-origin owner API client", () => {
  it("requests only the selected Directory scope and obtains a fresh in-memory token", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockImplementation(async () => new Response(JSON.stringify(facilityPage), { status: 200 }));
    const tokenProvider = vi.fn().mockResolvedValue("synthetic-access-token");
    const api = createOwnerApiClient(tokenProvider, fetcher);
    const scope = {
      key: `FACILITY:${facilityId}`,
      kind: "FACILITY" as const,
      organizationId,
      facilityId,
    };

    await expect(api.loadDirectoryFacilities(scope, 0, 25)).resolves.toMatchObject({ page: 0 });

    expect(tokenProvider).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledWith(
      `/api/v1/directory/facilities?page=0&size=25&organizationId=${organizationId}&facilityId=${facilityId}`,
      expect.objectContaining({
        credentials: "omit",
        cache: "no-store",
        headers: expect.objectContaining({
          Authorization: "Bearer synthetic-access-token",
          "X-AyuVahak-Execution-Scope": "FACILITY",
          "X-AyuVahak-Organization-Id": organizationId,
          "X-AyuVahak-Facility-Id": facilityId,
        }),
      }),
    );
  });

  it("maps API denial to a safe explicit error", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response("sensitive text", { status: 403 }));
    const api = createOwnerApiClient(async () => "synthetic-access-token", fetcher);
    const scope = {
      key: `FACILITY:${facilityId}`,
      kind: "FACILITY" as const,
      organizationId,
      facilityId,
    };

    await expect(api.loadCurrentCapacity(scope, "SYN_BEDS")).rejects.toMatchObject({
      status: 403,
      code: "FORBIDDEN",
    } satisfies Partial<OwnerApiError>);
  });

  it("obtains the in-memory token again for every operation", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockImplementation(async () => new Response(JSON.stringify(facilityPage), { status: 200 }));
    const tokenProvider = vi
      .fn<() => Promise<string | null>>()
      .mockResolvedValueOnce("first-token")
      .mockResolvedValueOnce("rotated-token");
    const api = createOwnerApiClient(tokenProvider, fetcher);
    const scope = {
      key: `FACILITY:${facilityId}`,
      kind: "FACILITY" as const,
      organizationId,
      facilityId,
    };

    await api.loadDirectoryFacilities(scope, 0, 25);
    await api.loadDirectoryFacilities(scope, 0, 25);

    expect(tokenProvider).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls[0]?.[1]?.headers).toMatchObject({
      Authorization: "Bearer first-token",
    });
    expect(fetcher.mock.calls[1]?.[1]?.headers).toMatchObject({
      Authorization: "Bearer rotated-token",
    });
  });

  it("rejects owner response records that escape the selected organization scope", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify(facilityPage), { status: 200 }));
    const api = createOwnerApiClient(async () => "synthetic-access-token", fetcher);
    const wrongScope = {
      key: "ORGANIZATION:8d0f20d7-1cf6-45b6-9c42-bd32ced0674b",
      kind: "ORGANIZATION" as const,
      organizationId: "8d0f20d7-1cf6-45b6-9c42-bd32ced0674b",
    };

    await expect(api.loadDirectoryFacilities(wrongScope, 0, 25)).rejects.toMatchObject({
      code: "INVALID_RESPONSE",
    });
  });
});
