import { OwnerApiError, type OwnerScope } from "./owner";
import type { ScopeProjection, SessionProjection } from "./session";

const DIRECTORY_ISSUER = "urn:ayuvahak:directory";
const INTERNAL_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const COMPLETENESS = new Set([
  "COMPLETE",
  "PARTIAL",
  "MISSING_SOURCE",
  "STALE_SOURCE",
  "NOT_APPLICABLE",
]);
const EXPORT_STATES = new Set([
  "PREPARED",
  "SENDING",
  "ACKNOWLEDGED",
  "OUTCOME_UNKNOWN",
  "RECONCILIATION_REQUIRED",
  "REJECTED",
  "WITHHELD",
]);

export type AnalyticsReadScope = Exclude<OwnerScope, { readonly kind: "PLATFORM" }>;

export interface AnalyticsSnapshot {
  readonly snapshotId: string;
  readonly indicatorKey: string;
  readonly indicatorVersion: number;
  readonly periodStart: string;
  readonly periodEndExclusive: string;
  readonly timeZone: string;
  readonly completeness: string;
  readonly value: string | null;
  readonly computedAt: string;
  readonly revision: number | null;
  readonly revisionState: string | null;
  readonly exportState: string | null;
  readonly exportResultClass: string | null;
}

export interface AnalyticsSnapshotPage {
  readonly items: readonly AnalyticsSnapshot[];
  readonly page: number;
  readonly size: number;
  readonly hasMore: boolean;
}

export interface AnalyticsApiClient {
  loadSnapshots(
    scope: AnalyticsReadScope,
    page?: number,
    size?: number,
    signal?: AbortSignal,
  ): Promise<AnalyticsSnapshotPage>;
}

type UnknownRecord = Record<string, unknown>;

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasOnlyKeys(value: UnknownRecord, keys: readonly string[]): boolean {
  return Object.keys(value).every((key) => keys.includes(key));
}

function validText(value: unknown, max = 128): value is string {
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    value.length > max ||
    value.trim() !== value
  ) {
    return false;
  }
  return [...value].every((character) => {
    const codePoint = character.codePointAt(0);
    return codePoint !== undefined && codePoint > 0x1f && !(codePoint >= 0x7f && codePoint <= 0x9f);
  });
}

function nullableText(value: unknown, max = 128): value is string | null {
  return value === null || validText(value, max);
}

function positiveInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && typeof value === "number" && value > 0;
}

function positiveIntegerOrNull(value: unknown): value is number | null {
  return value === null || positiveInteger(value);
}

function scopeFromProjection(scope: ScopeProjection): AnalyticsReadScope | null {
  const organization = scope.organization;
  if (
    organization?.issuer !== DIRECTORY_ISSUER ||
    organization.identifierType !== "Organization" ||
    !INTERNAL_ID.test(organization.identifier)
  ) {
    return null;
  }
  if (scope.kind === "ORGANIZATION") {
    return {
      key: `ORGANIZATION:${organization.identifier}`,
      kind: "ORGANIZATION",
      organizationId: organization.identifier,
    };
  }
  const facility = scope.facility;
  if (
    facility?.issuer !== DIRECTORY_ISSUER ||
    facility.identifierType !== "Facility" ||
    !INTERNAL_ID.test(facility.identifier)
  ) {
    return null;
  }
  return {
    key: `FACILITY:${facility.identifier}`,
    kind: "FACILITY",
    organizationId: organization.identifier,
    facilityId: facility.identifier,
  };
}

export function analyticsScopes(session: SessionProjection): readonly AnalyticsReadScope[] {
  const capability = session.capabilities.find(
    (item) => item.authorityCode === "AnalyticsAggregateRead",
  );
  if (!capability) return [];
  const scopes = new Map<string, AnalyticsReadScope>();
  for (const projection of capability.scopes) {
    const scope = scopeFromProjection(projection);
    if (scope) scopes.set(scope.key, scope);
  }
  return [...scopes.values()];
}

function parseSnapshot(value: unknown): AnalyticsSnapshot | null {
  if (
    !isRecord(value) ||
    !hasOnlyKeys(value, [
      "snapshotId",
      "indicatorKey",
      "indicatorVersion",
      "periodStart",
      "periodEndExclusive",
      "timeZone",
      "completeness",
      "value",
      "computedAt",
      "revision",
      "revisionState",
      "exportState",
      "exportResultClass",
    ]) ||
    typeof value.snapshotId !== "string" ||
    !INTERNAL_ID.test(value.snapshotId) ||
    !validText(value.indicatorKey, 64) ||
    !positiveInteger(value.indicatorVersion) ||
    typeof value.periodStart !== "string" ||
    Number.isNaN(Date.parse(value.periodStart)) ||
    typeof value.periodEndExclusive !== "string" ||
    Number.isNaN(Date.parse(value.periodEndExclusive)) ||
    !validText(value.timeZone, 64) ||
    typeof value.completeness !== "string" ||
    !COMPLETENESS.has(value.completeness) ||
    !nullableText(value.value, 40) ||
    (value.value !== null && !/^(?:0|[1-9]\d*)(?:\.\d{1,6})?$/u.test(value.value)) ||
    typeof value.computedAt !== "string" ||
    Number.isNaN(Date.parse(value.computedAt)) ||
    !positiveIntegerOrNull(value.revision) ||
    !nullableText(value.revisionState) ||
    !nullableText(value.exportState) ||
    (value.exportState !== null && !EXPORT_STATES.has(value.exportState)) ||
    !nullableText(value.exportResultClass)
  ) {
    return null;
  }
  if ((value.completeness === "COMPLETE") !== (value.value !== null)) return null;
  if ((value.revision === null) !== (value.revisionState === null)) return null;
  return {
    snapshotId: value.snapshotId,
    indicatorKey: value.indicatorKey,
    indicatorVersion: value.indicatorVersion,
    periodStart: value.periodStart,
    periodEndExclusive: value.periodEndExclusive,
    timeZone: value.timeZone,
    completeness: value.completeness,
    value: value.value,
    computedAt: value.computedAt,
    revision: value.revision,
    revisionState: value.revisionState,
    exportState: value.exportState,
    exportResultClass: value.exportResultClass,
  };
}

function parsePage(value: unknown): AnalyticsSnapshotPage | null {
  if (
    !isRecord(value) ||
    !hasOnlyKeys(value, ["items", "page", "size", "hasMore"]) ||
    !Array.isArray(value.items) ||
    value.items.length > 100 ||
    !Number.isSafeInteger(value.page) ||
    (value.page as number) < 0 ||
    !Number.isSafeInteger(value.size) ||
    (value.size as number) < 1 ||
    (value.size as number) > 100 ||
    typeof value.hasMore !== "boolean"
  ) {
    return null;
  }
  const items = value.items.map(parseSnapshot);
  if (items.some((item) => item === null)) return null;
  return {
    items: items as AnalyticsSnapshot[],
    page: value.page as number,
    size: value.size as number,
    hasMore: value.hasMore,
  };
}

export function createAnalyticsApiClient(
  getAccessToken: () => Promise<string | null>,
  fetcher: typeof fetch = fetch,
): AnalyticsApiClient {
  return {
    async loadSnapshots(scope, page = 0, size = 50, signal) {
      if (
        !Number.isSafeInteger(page) ||
        page < 0 ||
        !Number.isSafeInteger(size) ||
        size < 1 ||
        size > 100
      ) {
        throw new OwnerApiError(null, "INVALID_REQUEST");
      }
      const token = await getAccessToken();
      if (!token || token.length > 8192 || !/^[A-Za-z0-9\-._~+/]+=*$/u.test(token)) {
        throw new OwnerApiError(401, "UNAUTHENTICATED");
      }
      const headers: Record<string, string> = {
        Accept: "application/json",
        Authorization: `Bearer ${token}`,
        "X-AyuVahak-Execution-Scope": scope.kind,
        "X-AyuVahak-Organization-Id": scope.organizationId,
      };
      if (scope.kind === "FACILITY") headers["X-AyuVahak-Facility-Id"] = scope.facilityId;
      const params = new URLSearchParams({ page: String(page), size: String(size) });
      let response: Response;
      try {
        response = await fetcher(`/api/v1/analytics/snapshots?${params}`, {
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
        const result = parsePage(await response.json());
        if (!result) throw new OwnerApiError(response.status, "INVALID_RESPONSE");
        return result;
      } catch (error: unknown) {
        if (error instanceof OwnerApiError) throw error;
        throw new OwnerApiError(response.status, "INVALID_RESPONSE");
      }
    },
  };
}
