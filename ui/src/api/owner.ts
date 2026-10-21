import type { ScopeProjection, SessionProjection } from "./session";

const DIRECTORY_ISSUER = "urn:ayuvahak:directory";
const INTERNAL_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export type OwnerScope =
  | { readonly key: "PLATFORM"; readonly kind: "PLATFORM" }
  | {
      readonly key: string;
      readonly kind: "ORGANIZATION";
      readonly organizationId: string;
    }
  | {
      readonly key: string;
      readonly kind: "FACILITY";
      readonly organizationId: string;
      readonly facilityId: string;
    };

export interface DirectoryFacility {
  readonly facilityId: string;
  readonly managingOrganizationId: string;
  readonly facilityName: string;
  readonly status: string;
  readonly version: number;
}

export interface DirectoryFacilityPage {
  readonly mode: "DIRECTORY_FILTERED" | "NEARBY";
  readonly facilities: readonly DirectoryFacility[];
  readonly unlocated: readonly DirectoryFacility[];
  readonly page: number;
  readonly size: number;
  readonly hasMore: boolean;
  readonly hasMoreUnlocated: boolean;
}

export type CapacityFreshness = "CURRENT" | "STALE" | "EXPIRED" | "UNKNOWN" | "CONFLICTED";

export interface CapacityDimension {
  readonly dimensionCode: string;
  readonly freshness: CapacityFreshness;
  readonly dimensionVersion: number | null;
  readonly policyVersion: number | null;
  readonly projectionVersion: string;
  readonly valueKind: "QUANTITY" | "STATE" | null;
  readonly quantity: number | null;
  readonly state: string | null;
  readonly unit: string | null;
  readonly fallbackUsed: boolean;
  readonly reasonCodes: readonly string[];
}

export interface CapacityCurrentProjection {
  readonly facilityId: string;
  readonly evaluatedAt: string;
  readonly dimensions: readonly CapacityDimension[];
}

export type OwnerApiErrorCode =
  | "UNAUTHENTICATED"
  | "FORBIDDEN"
  | "UNAVAILABLE"
  | "INVALID_RESPONSE"
  | "INVALID_REQUEST"
  | "CONFLICT"
  | "OUTCOME_UNKNOWN";

export class OwnerApiError extends Error {
  constructor(
    readonly status: number | null,
    readonly code: OwnerApiErrorCode,
  ) {
    super("The owner service could not provide the requested information.");
    this.name = "OwnerApiError";
  }
}

type UnknownRecord = Record<string, unknown>;

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function boundedString(value: unknown, maximum = 512): value is string {
  return (
    typeof value === "string" &&
    value.length > 0 &&
    value.length <= maximum &&
    value.trim() === value
  );
}

function nullableString(value: unknown, maximum = 512): value is string | null {
  return value === null || boundedString(value, maximum);
}

function ownerIdentifier(
  reference: ScopeProjection["organization"] | ScopeProjection["facility"],
  type: "Organization" | "Facility",
): string | null {
  if (
    reference?.issuer !== DIRECTORY_ISSUER ||
    reference.identifierType !== type ||
    !INTERNAL_ID.test(reference.identifier)
  ) {
    return null;
  }
  return reference.identifier;
}

function authorizedScopes(
  session: SessionProjection,
  authorityCode: string,
): readonly OwnerScope[] {
  const capability = session.capabilities.find((item) => item.authorityCode === authorityCode);
  if (!capability) return [];
  const scopes: OwnerScope[] = [];
  const keys = new Set<string>();
  for (const scope of capability.scopes) {
    const candidate = scopeForOwner(scope);
    if (candidate && !keys.has(candidate.key)) {
      scopes.push(candidate);
      keys.add(candidate.key);
    }
  }
  return scopes;
}

function scopeForOwner(scope: ScopeProjection): OwnerScope | null {
  if (scope.kind === "PLATFORM") return { key: "PLATFORM", kind: "PLATFORM" };
  const organizationId = ownerIdentifier(scope.organization, "Organization");
  if (!organizationId) return null;
  if (scope.kind === "ORGANIZATION") {
    return { key: `ORGANIZATION:${organizationId}`, kind: "ORGANIZATION", organizationId };
  }
  const facilityId = ownerIdentifier(scope.facility, "Facility");
  if (!facilityId) return null;
  return {
    key: `FACILITY:${facilityId}`,
    kind: "FACILITY",
    organizationId,
    facilityId,
  };
}

export function directoryScopes(session: SessionProjection): readonly OwnerScope[] {
  return authorizedScopes(session, "DirectoryRead");
}

export function directoryCurationScopes(session: SessionProjection): readonly OwnerScope[] {
  return authorizedScopes(session, "DirectoryCuration");
}

export function capacityScopes(
  session: SessionProjection,
): readonly Extract<OwnerScope, { kind: "FACILITY" }>[] {
  return authorizedScopes(session, "CapacityEvidenceReview").filter(
    (scope): scope is Extract<OwnerScope, { kind: "FACILITY" }> => scope.kind === "FACILITY",
  );
}

/** Returns only recognized Directory origin scopes granted for the requested authority. */
export function originScopesForAuthority(
  session: SessionProjection,
  authorityCode: string,
): readonly Extract<OwnerScope, { kind: "ORGANIZATION" | "FACILITY" }>[] {
  return authorizedScopes(session, authorityCode).filter(
    (scope): scope is Extract<OwnerScope, { kind: "ORGANIZATION" | "FACILITY" }> =>
      scope.kind === "ORGANIZATION" || scope.kind === "FACILITY",
  );
}

function parseFacility(value: unknown): DirectoryFacility | null {
  if (
    !isRecord(value) ||
    !boundedString(value.facilityId, 64) ||
    !INTERNAL_ID.test(value.facilityId) ||
    !boundedString(value.managingOrganizationId, 64) ||
    !INTERNAL_ID.test(value.managingOrganizationId) ||
    !boundedString(value.facilityName) ||
    !boundedString(value.status, 64) ||
    typeof value.version !== "number" ||
    !Number.isSafeInteger(value.version) ||
    value.version < 0 ||
    !isRecord(value.source) ||
    !boundedString(value.source.issuer, 256) ||
    !boundedString(value.source.resourceType, 64) ||
    !boundedString(value.source.reference) ||
    !isRecord(value.provenance) ||
    !boundedString(value.provenance.method, 64) ||
    !boundedString(value.provenance.observedAt, 64) ||
    !boundedString(value.provenance.recordedAt, 64) ||
    !boundedString(value.provenance.reasonCode, 96) ||
    !(value.location === null || isRecord(value.location)) ||
    !(
      value.distanceMeters === null ||
      (typeof value.distanceMeters === "number" && Number.isFinite(value.distanceMeters))
    )
  ) {
    return null;
  }
  return {
    facilityId: value.facilityId,
    managingOrganizationId: value.managingOrganizationId,
    facilityName: value.facilityName,
    status: value.status,
    version: value.version,
  };
}

export function parseDirectoryFacilityPage(value: unknown): DirectoryFacilityPage | null {
  if (
    !isRecord(value) ||
    value.mode !== "DIRECTORY_FILTERED" ||
    !Array.isArray(value.facilities) ||
    !Array.isArray(value.unlocated) ||
    !Number.isSafeInteger(value.page) ||
    (value.page as number) < 0 ||
    !Number.isSafeInteger(value.size) ||
    (value.size as number) < 1 ||
    (value.size as number) > 100 ||
    typeof value.hasMore !== "boolean" ||
    typeof value.hasMoreUnlocated !== "boolean"
  ) {
    return null;
  }
  const facilities = value.facilities.map(parseFacility);
  const unlocated = value.unlocated.map(parseFacility);
  if (
    facilities.some((facility) => facility === null) ||
    unlocated.some((facility) => facility === null)
  ) {
    return null;
  }
  return {
    mode: value.mode,
    facilities: facilities as DirectoryFacility[],
    unlocated: unlocated as DirectoryFacility[],
    page: value.page as number,
    size: value.size as number,
    hasMore: value.hasMore,
    hasMoreUnlocated: value.hasMoreUnlocated,
  };
}

const FRESHNESS = new Set<CapacityFreshness>([
  "CURRENT",
  "STALE",
  "EXPIRED",
  "UNKNOWN",
  "CONFLICTED",
]);

function parseCapacityDimension(value: unknown): CapacityDimension | null {
  if (
    !isRecord(value) ||
    typeof value.dimensionCode !== "string" ||
    !/^[A-Z][A-Z0-9._-]{0,63}$/.test(value.dimensionCode) ||
    typeof value.freshness !== "string" ||
    !FRESHNESS.has(value.freshness as CapacityFreshness) ||
    !(
      value.dimensionVersion === null ||
      (Number.isSafeInteger(value.dimensionVersion) && (value.dimensionVersion as number) >= 0)
    ) ||
    !(
      value.policyVersion === null ||
      (Number.isSafeInteger(value.policyVersion) && (value.policyVersion as number) >= 0)
    ) ||
    !boundedString(value.projectionVersion, 96) ||
    !(value.valueKind === null || value.valueKind === "QUANTITY" || value.valueKind === "STATE") ||
    !(
      value.quantity === null ||
      (typeof value.quantity === "number" && Number.isFinite(value.quantity))
    ) ||
    !nullableString(value.state) ||
    !nullableString(value.unit, 96) ||
    typeof value.fallbackUsed !== "boolean" ||
    !Array.isArray(value.reasonCodes) ||
    value.reasonCodes.length > 64 ||
    !value.reasonCodes.every((code) => boundedString(code, 96)) ||
    !Array.isArray(value.evidence)
  ) {
    return null;
  }
  return {
    dimensionCode: value.dimensionCode,
    freshness: value.freshness as CapacityFreshness,
    dimensionVersion: value.dimensionVersion as number | null,
    policyVersion: value.policyVersion as number | null,
    projectionVersion: value.projectionVersion,
    valueKind: value.valueKind,
    quantity: value.quantity as number | null,
    state: value.state,
    unit: value.unit,
    fallbackUsed: value.fallbackUsed,
    reasonCodes: value.reasonCodes as string[],
  };
}

export function parseCapacityCurrent(value: unknown): CapacityCurrentProjection | null {
  if (
    !isRecord(value) ||
    !boundedString(value.facilityId, 64) ||
    !INTERNAL_ID.test(value.facilityId) ||
    !boundedString(value.evaluatedAt, 64) ||
    Number.isNaN(Date.parse(value.evaluatedAt)) ||
    !Array.isArray(value.dimensions) ||
    value.dimensions.length > 128
  ) {
    return null;
  }
  const dimensions = value.dimensions.map(parseCapacityDimension);
  if (dimensions.some((dimension) => dimension === null)) return null;
  return {
    facilityId: value.facilityId,
    evaluatedAt: value.evaluatedAt,
    dimensions: dimensions as CapacityDimension[],
  };
}

export interface OwnerApiClient {
  loadDirectoryFacilities(
    scope: OwnerScope,
    page: number,
    size: number,
    signal?: AbortSignal,
  ): Promise<DirectoryFacilityPage>;
  loadCurrentCapacity(
    scope: Extract<OwnerScope, { kind: "FACILITY" }>,
    dimension: string,
    signal?: AbortSignal,
  ): Promise<CapacityCurrentProjection>;
  createOrganization(
    scope: OwnerScope,
    name: string,
    idempotencyKey: string,
    observedAt: string,
  ): Promise<string>;
  createFacility(
    scope: OwnerScope,
    managingOrganizationId: string,
    name: string,
    idempotencyKey: string,
    observedAt: string,
  ): Promise<string>;
}

export function createOwnerApiClient(
  getAccessToken: () => Promise<string | null>,
  fetcher: typeof fetch = fetch,
): OwnerApiClient {
  async function request<T>(
    path: string,
    scope: OwnerScope,
    parse: (value: unknown) => T | null,
    signal?: AbortSignal,
  ): Promise<T> {
    const token = await getAccessToken();
    if (!token || token.length > 8192 || !/^[A-Za-z0-9\-._~+/]+=*$/.test(token)) {
      throw new OwnerApiError(401, "UNAUTHENTICATED");
    }
    const headers: Record<string, string> = {
      Accept: "application/json",
      Authorization: `Bearer ${token}`,
      "X-AyuVahak-Execution-Scope": scope.kind,
    };
    if (scope.kind !== "PLATFORM") headers["X-AyuVahak-Organization-Id"] = scope.organizationId;
    if (scope.kind === "FACILITY") headers["X-AyuVahak-Facility-Id"] = scope.facilityId;

    let response: Response;
    try {
      response = await fetcher(path, {
        method: "GET",
        headers,
        credentials: "omit",
        cache: "no-store",
        ...(signal ? { signal } : {}),
      });
    } catch {
      throw new OwnerApiError(null, "UNAVAILABLE");
    }
    if (response.status === 401) throw new OwnerApiError(401, "UNAUTHENTICATED");
    if (response.status === 403) throw new OwnerApiError(403, "FORBIDDEN");
    if (!response.ok) throw new OwnerApiError(response.status, "UNAVAILABLE");
    try {
      const result = parse(await response.json());
      if (!result) throw new OwnerApiError(response.status, "INVALID_RESPONSE");
      return result;
    } catch (error: unknown) {
      if (error instanceof OwnerApiError) throw error;
      throw new OwnerApiError(response.status, "INVALID_RESPONSE");
    }
  }

  async function create(
    path: string,
    scope: OwnerScope,
    body: UnknownRecord,
    idempotencyKey: string,
  ): Promise<string> {
    const token = await getAccessToken();
    if (!token || token.length > 8192 || !/^[A-Za-z0-9\-._~+/]+=*$/.test(token)) {
      throw new OwnerApiError(401, "UNAUTHENTICATED");
    }
    if (!INTERNAL_ID.test(idempotencyKey)) {
      throw new OwnerApiError(null, "INVALID_REQUEST");
    }
    const headers: Record<string, string> = {
      Accept: "application/json",
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      "Idempotency-Key": idempotencyKey,
      "X-AyuVahak-Execution-Scope": scope.kind,
    };
    if (scope.kind !== "PLATFORM") headers["X-AyuVahak-Organization-Id"] = scope.organizationId;
    if (scope.kind === "FACILITY") headers["X-AyuVahak-Facility-Id"] = scope.facilityId;

    let response: Response;
    try {
      response = await fetcher(path, {
        method: "POST",
        headers,
        body: JSON.stringify(body),
        credentials: "omit",
        cache: "no-store",
      });
    } catch {
      throw new OwnerApiError(null, "OUTCOME_UNKNOWN");
    }
    if (response.status === 401) throw new OwnerApiError(401, "UNAUTHENTICATED");
    if (response.status === 403) throw new OwnerApiError(403, "FORBIDDEN");
    if (response.status === 400 || response.status === 422) {
      throw new OwnerApiError(response.status, "INVALID_REQUEST");
    }
    if (response.status === 409) throw new OwnerApiError(409, "CONFLICT");
    if (response.status >= 500) throw new OwnerApiError(response.status, "OUTCOME_UNKNOWN");
    if (!response.ok) throw new OwnerApiError(response.status, "UNAVAILABLE");
    try {
      const result = parseCreatedResource(await response.json());
      if (result === null) throw new OwnerApiError(response.status, "OUTCOME_UNKNOWN");
      return result;
    } catch (error: unknown) {
      if (error instanceof OwnerApiError) throw error;
      throw new OwnerApiError(response.status, "OUTCOME_UNKNOWN");
    }
  }

  return {
    loadDirectoryFacilities(scope, page, size, signal) {
      if (
        !Number.isSafeInteger(page) ||
        page < 0 ||
        !Number.isSafeInteger(size) ||
        size < 1 ||
        size > 100
      ) {
        return Promise.reject(new OwnerApiError(null, "INVALID_REQUEST"));
      }
      const params = new URLSearchParams({ page: String(page), size: String(size) });
      if (scope.kind !== "PLATFORM") params.set("organizationId", scope.organizationId);
      if (scope.kind === "FACILITY") params.set("facilityId", scope.facilityId);
      return request(
        `/api/v1/directory/facilities?${params.toString()}`,
        scope,
        parseDirectoryFacilityPage,
        signal,
      ).then((page) => {
        const allFacilities = [...page.facilities, ...page.unlocated];
        const withinScope = allFacilities.every((facility) => {
          if (scope.kind === "PLATFORM") return true;
          if (facility.managingOrganizationId !== scope.organizationId) return false;
          return scope.kind !== "FACILITY" || facility.facilityId === scope.facilityId;
        });
        if (!withinScope) throw new OwnerApiError(null, "INVALID_RESPONSE");
        return page;
      });
    },
    loadCurrentCapacity(scope, dimension, signal) {
      if (!/^[A-Z][A-Z0-9._-]{0,63}$/.test(dimension)) {
        return Promise.reject(new OwnerApiError(null, "INVALID_REQUEST"));
      }
      const params = new URLSearchParams({ dimension });
      return request(
        `/api/v1/capacity/facilities/${encodeURIComponent(scope.facilityId)}/current?${params.toString()}`,
        scope,
        parseCapacityCurrent,
        signal,
      ).then((projection) => {
        if (projection.facilityId !== scope.facilityId) {
          throw new OwnerApiError(null, "INVALID_RESPONSE");
        }
        return projection;
      });
    },
    createOrganization(scope, name, idempotencyKey, observedAt) {
      if (
        scope.kind !== "PLATFORM" ||
        name.trim() !== name ||
        name.length < 1 ||
        name.length > 256 ||
        Number.isNaN(Date.parse(observedAt))
      ) {
        return Promise.reject(new OwnerApiError(null, "INVALID_REQUEST"));
      }
      return create(
        "/api/v1/directory/organizations",
        scope,
        {
          name,
          parentOrganizationId: null,
          source: {
            issuer: "urn:ayuvahak:operator-entry",
            resourceType: "Organization",
            reference: `LOCAL-OPERATOR-${idempotencyKey}`,
          },
          provenance: {
            method: "OPERATOR_REPORTED",
            observedAt,
            reasonCode: "INITIAL_SETUP",
          },
        },
        idempotencyKey,
      );
    },
    createFacility(scope, managingOrganizationId, name, idempotencyKey, observedAt) {
      if (
        (scope.kind !== "PLATFORM" && scope.kind !== "ORGANIZATION") ||
        !INTERNAL_ID.test(managingOrganizationId) ||
        (scope.kind === "ORGANIZATION" && scope.organizationId !== managingOrganizationId) ||
        name.trim() !== name ||
        name.length < 1 ||
        name.length > 256 ||
        Number.isNaN(Date.parse(observedAt))
      ) {
        return Promise.reject(new OwnerApiError(null, "INVALID_REQUEST"));
      }
      return create(
        "/api/v1/directory/facilities",
        scope,
        {
          name,
          managingOrganizationId,
          source: {
            issuer: "urn:ayuvahak:operator-entry",
            resourceType: "Facility",
            reference: `LOCAL-OPERATOR-${idempotencyKey}`,
          },
          provenance: {
            method: "OPERATOR_REPORTED",
            observedAt,
            reasonCode: "INITIAL_SETUP",
          },
        },
        idempotencyKey,
      );
    },
  };
}

function parseCreatedResource(value: unknown): string | null {
  if (!isRecord(value) || typeof value.id !== "string" || !INTERNAL_ID.test(value.id)) {
    return null;
  }
  return value.id;
}
