import { describe, expect, it, vi } from "vitest";
import type { SessionProjection } from "./session";
import {
  createTransferApiClient,
  receivingScopes,
  transferScopes,
  transportScopes,
} from "./transfer";

const orgA = "11111111-1111-4111-8111-111111111111";
const facilityA = "22222222-2222-4222-8222-222222222222";
const facilityB = "33333333-3333-4333-8333-333333333333";
const transferId = "44444444-4444-4444-8444-444444444444";

function first<T>(values: readonly T[]): T {
  const value = values[0];
  if (value === undefined) throw new Error("Expected a projected authorization scope.");
  return value;
}

function session(): SessionProjection {
  return {
    subject: {
      kind: "HUMAN",
      issuer: "https://identity.example.test/realms/synthetic",
      identifierType: "subject",
      identifier: "SYN-USER-001",
    },
    deploymentMode: "EMPTY",
    capabilities: [
      {
        authorityCode: "TransferViewAuthority",
        scopes: [
          {
            kind: "PLATFORM",
          },
          {
            kind: "FACILITY",
            organization: {
              issuer: "urn:ayuvahak:directory",
              identifierType: "Organization",
              identifier: orgA,
            },
            facility: {
              issuer: "urn:ayuvahak:directory",
              identifierType: "Facility",
              identifier: facilityA,
            },
          },
          {
            kind: "FACILITY",
            organization: {
              issuer: "urn:ayuvahak:directory",
              identifierType: "Organization",
              identifier: orgA,
            },
            facility: {
              issuer: "urn:ayuvahak:directory",
              identifierType: "Facility",
              identifier: facilityB,
            },
          },
        ],
      },
      {
        authorityCode: "TransportStatusAuthority",
        scopes: [
          {
            kind: "FACILITY",
            organization: {
              issuer: "urn:ayuvahak:directory",
              identifierType: "Organization",
              identifier: orgA,
            },
            facility: {
              issuer: "urn:ayuvahak:directory",
              identifierType: "Facility",
              identifier: facilityA,
            },
          },
        ],
      },
    ],
  };
}

function page(items: unknown[]): Response {
  return new Response(JSON.stringify({ items, page: 0, size: 50, hasMore: false }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

describe("Transfer read client", () => {
  it("projects only validated Directory organization/facility scopes", () => {
    const projected = session();
    expect(transferScopes(projected).map((scope) => scope.key)).toEqual([
      `FACILITY:${facilityA}`,
      `FACILITY:${facilityB}`,
    ]);
    expect(receivingScopes(projected).map((scope) => scope.facilityId)).toEqual([
      facilityA,
      facilityB,
    ]);
    expect(transportScopes(projected).map((scope) => scope.facilityId)).toEqual([facilityA]);
  });

  it("sends the selected bearer identity and execution scope without cookies", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(page([]));
    const client = createTransferApiClient(async () => "synthetic-access-token", fetcher);

    await client.loadTransfers(first(transferScopes(session())));

    expect(fetcher).toHaveBeenCalledWith(
      "/api/v1/transfers?page=0&size=50",
      expect.objectContaining({
        method: "GET",
        credentials: "omit",
        cache: "no-store",
        headers: expect.objectContaining({
          Authorization: "Bearer synthetic-access-token",
          "X-AyuVahak-Execution-Scope": "FACILITY",
          "X-AyuVahak-Organization-Id": orgA,
          "X-AyuVahak-Facility-Id": facilityA,
        }),
      }),
    );
  });

  it("rejects a server Transfer row outside the authorized facility projection", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      page([
        {
          transferId,
          journeyId: "55555555-5555-4555-8555-555555555555",
          requestAttemptId: "66666666-6666-4666-8666-666666666666",
          referralId: "77777777-7777-4777-8777-777777777777",
          recommendationId: "88888888-8888-4888-8888-888888888888",
          originOrganizationId: orgA,
          originFacilityId: facilityB,
          receivingOrganizationId: orgA,
          receivingFacilityId: facilityB,
          status: "READY",
          version: 1,
          createdAt: "2026-01-01T00:00:00Z",
          lastEventAt: "2026-01-01T00:00:00Z",
          lastAggregateSequence: 1,
          reservationOutcome: null,
          reservationId: null,
        },
      ]),
    );
    const client = createTransferApiClient(async () => "synthetic-access-token", fetcher);

    await expect(client.loadTransfers(first(transferScopes(session())))).rejects.toMatchObject({
      code: "INVALID_RESPONSE",
    });
  });

  it("scopes a receiving queue to its exact facility and rejects cross-facility rows", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      page([
        {
          requestAttemptId: transferId,
          journeyId: "55555555-5555-4555-8555-555555555555",
          referralId: "77777777-7777-4777-8777-777777777777",
          recommendationId: "88888888-8888-4888-8888-888888888888",
          originOrganizationId: orgA,
          originFacilityId: facilityA,
          receivingFacilityId: facilityB,
          requestedAt: "2026-01-01T00:00:00Z",
          expiresAt: "2026-01-02T00:00:00Z",
        },
      ]),
    );
    const client = createTransferApiClient(async () => "synthetic-access-token", fetcher);

    await expect(client.loadIncoming(first(receivingScopes(session())))).rejects.toMatchObject({
      code: "INVALID_RESPONSE",
    });
    expect(fetcher).toHaveBeenCalledWith(
      "/api/v1/transfer-request-attempts?page=0&size=50&state=PENDING",
      expect.any(Object),
    );
  });

  it("validates transfer identifiers before constructing a nested Transport request", async () => {
    const fetcher = vi.fn<typeof fetch>();
    const client = createTransferApiClient(async () => "synthetic-access-token", fetcher);
    await expect(
      client.loadTransportRequests(first(transportScopes(session())), "../other-transfer"),
    ).rejects.toMatchObject({ code: "INVALID_REQUEST" });
    expect(fetcher).not.toHaveBeenCalled();
  });
});
