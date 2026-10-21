import { describe, expect, it, vi } from "vitest";
import { AccessGrantApiError, createAccessGrantApiClient } from "./access-grants";

const grant = {
  grantId: "28d0fe73-26e8-4c05-b8cf-1b9b11ac15e2",
  kind: "HUMAN",
  issuer: "https://identity.example.test/realms/ayuvahak",
  subject: "synthetic-operator",
  clientId: null,
  authority: "AnalyticsAggregateRead",
  scope: "ORGANIZATION",
  organizationIssuer: "urn:ayuvahak:directory",
  organizationIdentifier: "08b72645-81d8-45f6-b54a-94d03f58feba",
  facilityIssuer: null,
  facilityIdentifier: null,
  status: "ACTIVE",
  createdAt: "2026-01-01T00:00:00Z",
  revokedAt: null,
};

describe("access grants API client", () => {
  it("loads grants only from the authorized platform scope", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        new Response(JSON.stringify({ items: [grant], nextCursor: null }), { status: 200 }),
      );
    const client = createAccessGrantApiClient(async () => "synthetic-token", fetcher);

    await expect(client.list()).resolves.toEqual({ items: [grant], nextCursor: null });
    expect(fetcher).toHaveBeenCalledWith(
      "/api/v1/platform/access-grants?limit=100",
      expect.objectContaining({
        method: "GET",
        credentials: "omit",
        cache: "no-store",
        headers: expect.objectContaining({ "X-AyuVahak-Execution-Scope": "PLATFORM" }),
      }),
    );
  });

  it("rejects malformed authority records rather than displaying them", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(
        JSON.stringify({
          items: [
            {
              ...grant,
              facilityIssuer: "urn:bad",
              facilityIdentifier: "cf1f8736-47e9-46af-82d4-bf81c2b24b6d",
            },
          ],
          nextCursor: null,
        }),
        {
          status: 200,
        },
      ),
    );
    const client = createAccessGrantApiClient(async () => "synthetic-token", fetcher);

    await expect(client.list()).rejects.toBeInstanceOf(AccessGrantApiError);
  });

  it("sends grant creation to the existing backend grant API", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 201 }));
    const client = createAccessGrantApiClient(async () => "synthetic-token", fetcher);

    await client.create(
      {
        kind: "HUMAN",
        issuer: "https://identity.example.test/realms/ayuvahak",
        subject: "synthetic-new-operator",
      },
      "AccessGrantAdministration",
      { kind: "PLATFORM" },
    );

    const [path, init] = fetcher.mock.calls[0] ?? [];
    expect(path).toBe("/api/v1/platform/access-grants");
    expect(init?.method).toBe("POST");
    expect(init?.headers).toEqual(
      expect.objectContaining({
        "X-AyuVahak-Execution-Scope": "PLATFORM",
        "Content-Type": "application/json",
      }),
    );
    expect(JSON.parse(String(init?.body))).toMatchObject({
      principal: {
        kind: "HUMAN",
        issuer: "https://identity.example.test/realms/ayuvahak",
        subject: "synthetic-new-operator",
      },
      authority: "AccessGrantAdministration",
      scope: { kind: "PLATFORM" },
      reasonCode: "ACCESS_SETUP",
    });
  });
});
