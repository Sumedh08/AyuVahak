import { describe, expect, it, vi } from "vitest";
import {
  createSessionClient,
  parseSessionProjection,
  SessionApiError,
  type SessionProjection,
} from "./session";

const validSession: SessionProjection = {
  subject: {
    kind: "HUMAN",
    issuer: "http://localhost:8081/realms/ayuvahak-local",
    identifierType: "subject",
    identifier: "SYN-OPERATOR-001",
  },
  capabilities: [
    {
      authorityCode: "DirectoryRead",
      scopes: [
        {
          kind: "ORGANIZATION",
          organization: {
            issuer: "urn:synthetic:directory",
            identifierType: "organization",
            identifier: "SYN-ORG-001",
          },
        },
      ],
    },
  ],
};

describe("session projection validation", () => {
  it("accepts the bounded authenticated projection and preserves its scopes", () => {
    expect(parseSessionProjection(validSession)).toEqual(validSession);
  });

  it("rejects unexpected fields and invalid scope shapes", () => {
    expect(parseSessionProjection({ ...validSession, roles: ["admin"] })).toBeNull();
    expect(
      parseSessionProjection({
        ...validSession,
        capabilities: [{ authorityCode: "DirectoryRead", scopes: [{ kind: "FACILITY" }] }],
      }),
    ).toBeNull();
  });

  it("validates external identifiers as bounded opaque references", () => {
    expect(
      parseSessionProjection({
        ...validSession,
        capabilities: [
          {
            authorityCode: "DirectoryRead",
            scopes: [
              {
                kind: "ORGANIZATION",
                organization: {
                  issuer: "urn:synthetic:directory",
                  identifierType: "Organization.v1",
                  identifier: "SYN-ORG-001",
                },
              },
            ],
          },
        ],
      }),
    ).not.toBeNull();
    expect(
      parseSessionProjection({
        ...validSession,
        capabilities: [
          {
            authorityCode: "DirectoryRead",
            scopes: [
              {
                kind: "ORGANIZATION",
                organization: {
                  issuer: "urn:synthetic:directory",
                  identifierType: "organization",
                  identifier: " SYN-ORG-001",
                },
              },
            ],
          },
        ],
      }),
    ).toBeNull();
  });

  it("does not accept a service identity as a human subject", () => {
    expect(
      parseSessionProjection({
        ...validSession,
        subject: { ...validSession.subject, kind: "SERVICE" },
      }),
    ).toBeNull();
  });
});

describe("authenticated session API client", () => {
  it("requests the same-origin session projection using only the access token", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify(validSession), { status: 200 }));
    const client = createSessionClient(fetcher);

    await expect(client.loadSession("synthetic-access-token")).resolves.toEqual(validSession);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/v1/session",
      expect.objectContaining({
        method: "GET",
        credentials: "omit",
        cache: "no-store",
        headers: {
          Accept: "application/json",
          Authorization: "Bearer synthetic-access-token",
        },
      }),
    );
  });

  it.each([
    [401, "UNAUTHENTICATED"],
    [403, "FORBIDDEN"],
  ] as const)("maps HTTP %s to the explicit %s state", async (status, code) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status }));
    await expect(createSessionClient(fetcher).loadSession("synthetic-token")).rejects.toMatchObject(
      {
        status,
        code,
      },
    );
  });

  it("rejects a malformed success response without exposing response text", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        new Response(JSON.stringify({ secret: "synthetic-not-a-token" }), { status: 200 }),
      );
    await expect(
      createSessionClient(fetcher).loadSession("synthetic-token"),
    ).rejects.toBeInstanceOf(SessionApiError);
  });
});
