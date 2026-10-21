const DIRECTORY_ISSUER = "urn:ayuvahak:directory";
const INTERNAL_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;

export type GrantScope =
  | { readonly kind: "PLATFORM" }
  | { readonly kind: "ORGANIZATION"; readonly organizationId: string }
  | { readonly kind: "FACILITY"; readonly organizationId: string; readonly facilityId: string };

export interface AccessGrant {
  readonly grantId: string;
  readonly kind: "HUMAN" | "SERVICE";
  readonly issuer: string;
  readonly subject: string | null;
  readonly clientId: string | null;
  readonly authority: string;
  readonly scope: "PLATFORM" | "ORGANIZATION" | "FACILITY";
  readonly organizationIssuer: string | null;
  readonly organizationIdentifier: string | null;
  readonly facilityIssuer: string | null;
  readonly facilityIdentifier: string | null;
  readonly status: "ACTIVE" | "REVOKED";
  readonly createdAt: string;
  readonly revokedAt: string | null;
}

export interface GrantPage {
  readonly items: readonly AccessGrant[];
  readonly nextCursor: string | null;
}

export type GrantPrincipal =
  | { readonly kind: "HUMAN"; readonly issuer: string; readonly subject: string }
  | { readonly kind: "SERVICE"; readonly issuer: string; readonly clientId: string };

export interface AccessGrantApiClient {
  list(cursor?: string, signal?: AbortSignal): Promise<GrantPage>;
  create(principal: GrantPrincipal, authority: string, scope: GrantScope): Promise<void>;
  revoke(grantId: string, reasonCode: string): Promise<void>;
}

export class AccessGrantApiError extends Error {
  constructor(
    readonly status: number | null,
    readonly code: string,
  ) {
    super("The access-grant operation could not be completed.");
    this.name = "AccessGrantApiError";
  }
}

type UnknownRecord = Record<string, unknown>;

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasOnlyKeys(value: UnknownRecord, keys: readonly string[]): boolean {
  return Object.keys(value).every((key) => keys.includes(key));
}

function text(value: unknown, max = 256): value is string {
  return (
    typeof value === "string" &&
    value.length > 0 &&
    value.length <= max &&
    value.trim() === value &&
    [...value].every((character) => {
      const codePoint = character.codePointAt(0);
      return (
        codePoint !== undefined && codePoint >= 0x20 && !(codePoint >= 0x7f && codePoint <= 0x9f)
      );
    })
  );
}

function nullableText(value: unknown, max = 256): value is string | null {
  return value === null || text(value, max);
}

function parseGrant(value: unknown): AccessGrant | null {
  if (
    !isRecord(value) ||
    !hasOnlyKeys(value, [
      "grantId",
      "kind",
      "issuer",
      "subject",
      "clientId",
      "authority",
      "scope",
      "organizationIssuer",
      "organizationIdentifier",
      "facilityIssuer",
      "facilityIdentifier",
      "status",
      "createdAt",
      "revokedAt",
    ]) ||
    typeof value.grantId !== "string" ||
    !INTERNAL_ID.test(value.grantId) ||
    (value.kind !== "HUMAN" && value.kind !== "SERVICE") ||
    !text(value.issuer) ||
    !nullableText(value.subject) ||
    !nullableText(value.clientId) ||
    (value.kind === "HUMAN" && (value.subject === null || value.clientId !== null)) ||
    (value.kind === "SERVICE" && (value.clientId === null || value.subject !== null)) ||
    !text(value.authority, 64) ||
    !/^[A-Za-z][A-Za-z0-9]{0,63}$/u.test(value.authority) ||
    (value.scope !== "PLATFORM" && value.scope !== "ORGANIZATION" && value.scope !== "FACILITY") ||
    !nullableText(value.organizationIssuer) ||
    !nullableText(value.organizationIdentifier) ||
    !nullableText(value.facilityIssuer) ||
    !nullableText(value.facilityIdentifier) ||
    (value.status !== "ACTIVE" && value.status !== "REVOKED") ||
    typeof value.createdAt !== "string" ||
    Number.isNaN(Date.parse(value.createdAt)) ||
    !nullableText(value.revokedAt, 64) ||
    (value.revokedAt !== null && Number.isNaN(Date.parse(value.revokedAt)))
  ) {
    return null;
  }
  const hasOrganization =
    value.organizationIssuer !== null && value.organizationIdentifier !== null;
  const hasFacility = value.facilityIssuer !== null && value.facilityIdentifier !== null;
  if (
    (value.organizationIssuer === null) !== (value.organizationIdentifier === null) ||
    (value.facilityIssuer === null) !== (value.facilityIdentifier === null) ||
    (value.scope === "PLATFORM" && (hasOrganization || hasFacility)) ||
    (value.scope === "ORGANIZATION" && (!hasOrganization || hasFacility)) ||
    (value.scope === "FACILITY" && (!hasOrganization || !hasFacility)) ||
    (value.scope !== "PLATFORM" && value.organizationIssuer !== DIRECTORY_ISSUER) ||
    (value.scope === "FACILITY" && value.facilityIssuer !== DIRECTORY_ISSUER)
  ) {
    return null;
  }
  return value as unknown as AccessGrant;
}

function parsePage(value: unknown): GrantPage | null {
  if (
    !isRecord(value) ||
    !hasOnlyKeys(value, ["items", "nextCursor"]) ||
    !Array.isArray(value.items) ||
    value.items.length > 100 ||
    !(value.nextCursor === null || text(value.nextCursor, 64))
  ) {
    return null;
  }
  const items = value.items.map(parseGrant);
  if (items.some((item) => item === null)) return null;
  return { items: items as AccessGrant[], nextCursor: value.nextCursor };
}

export function createAccessGrantApiClient(
  getAccessToken: () => Promise<string | null>,
  fetcher: typeof fetch = fetch,
): AccessGrantApiClient {
  async function authorizedFetch(path: string, init: RequestInit): Promise<Response> {
    const token = await getAccessToken();
    if (!token || token.length > 8192 || !/^[A-Za-z0-9\-._~+/]+=*$/u.test(token)) {
      throw new AccessGrantApiError(401, "UNAUTHENTICATED");
    }
    let response: Response;
    try {
      response = await fetcher(path, {
        ...init,
        headers: {
          Accept: "application/json",
          Authorization: `Bearer ${token}`,
          "X-AyuVahak-Execution-Scope": "PLATFORM",
          ...(init.headers ?? {}),
        },
        credentials: "omit",
        cache: "no-store",
      });
    } catch {
      throw new AccessGrantApiError(null, "UNAVAILABLE");
    }
    if (response.status === 401) throw new AccessGrantApiError(401, "UNAUTHENTICATED");
    if (response.status === 403) throw new AccessGrantApiError(403, "FORBIDDEN");
    if (response.status === 409) throw new AccessGrantApiError(409, "CONFLICT");
    if (!response.ok) throw new AccessGrantApiError(response.status, "UNAVAILABLE");
    return response;
  }

  return {
    async list(cursor, signal) {
      const query = new URLSearchParams({ limit: "100" });
      if (cursor) query.set("cursor", cursor);
      const response = await authorizedFetch(`/api/v1/platform/access-grants?${query}`, {
        method: "GET",
        ...(signal ? { signal } : {}),
      });
      try {
        const page = parsePage(await response.json());
        if (!page) throw new AccessGrantApiError(response.status, "INVALID_RESPONSE");
        return page;
      } catch (error: unknown) {
        if (error instanceof AccessGrantApiError) throw error;
        throw new AccessGrantApiError(response.status, "INVALID_RESPONSE");
      }
    },
    async create(principal, authority, scope) {
      if (!/^[A-Za-z][A-Za-z0-9]{0,63}$/u.test(authority)) {
        throw new AccessGrantApiError(null, "INVALID_REQUEST");
      }
      const scopeRequest =
        scope.kind === "PLATFORM"
          ? { kind: "PLATFORM" }
          : scope.kind === "ORGANIZATION"
            ? {
                kind: "ORGANIZATION",
                organization: { issuer: DIRECTORY_ISSUER, identifier: scope.organizationId },
              }
            : {
                kind: "FACILITY",
                organization: { issuer: DIRECTORY_ISSUER, identifier: scope.organizationId },
                facility: { issuer: DIRECTORY_ISSUER, identifier: scope.facilityId },
              };
      const body = {
        principal:
          principal.kind === "HUMAN"
            ? { kind: principal.kind, issuer: principal.issuer, subject: principal.subject }
            : { kind: principal.kind, issuer: principal.issuer, clientId: principal.clientId },
        authority,
        scope: scopeRequest,
        reasonCode: "ACCESS_SETUP",
      };
      await authorizedFetch("/api/v1/platform/access-grants", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": crypto.randomUUID(),
        },
        body: JSON.stringify(body),
      });
    },
    async revoke(grantId, reasonCode) {
      if (
        !INTERNAL_ID.test(grantId) ||
        !["ACCESS_REMOVAL", "ACCESS_CHANGE", "SECURITY_RESPONSE", "OTHER"].includes(reasonCode)
      ) {
        throw new AccessGrantApiError(null, "INVALID_REQUEST");
      }
      await authorizedFetch(`/api/v1/platform/access-grants/${grantId}`, {
        method: "DELETE",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": crypto.randomUUID(),
        },
        body: JSON.stringify({ reasonCode }),
      });
    },
  };
}
