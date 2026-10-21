import { OwnerApiError, type OwnerScope } from "./owner";
import type { ScopeProjection, SessionProjection } from "./session";

const DIRECTORY_ISSUER = "urn:ayuvahak:directory";
const INTERNAL_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const TRANSFER_STATES = new Set([
  "AWAITING_RESERVATION",
  "CANCELLATION_PENDING_RELEASE",
  "READY",
  "COORDINATION_EXCEPTION",
  "IN_TRANSIT",
  "INTERRUPTED",
  "ARRIVED",
  "HANDED_OVER",
  "COMPLETED",
  "CANCELLED",
]);

export interface TransferSummary {
  readonly transferId: string;
  readonly journeyId: string;
  readonly requestAttemptId: string;
  readonly referralId: string;
  readonly originOrganizationId: string;
  readonly originFacilityId: string | null;
  readonly receivingOrganizationId: string;
  readonly receivingFacilityId: string;
  readonly status: string;
  readonly version: number;
  readonly createdAt: string;
  readonly reservationOutcome: string | null;
}

export interface IncomingTransferRequest {
  readonly requestAttemptId: string;
  readonly journeyId: string;
  readonly referralId: string;
  readonly originOrganizationId: string;
  readonly originFacilityId: string | null;
  readonly receivingFacilityId: string;
  readonly requestedAt: string;
  readonly expiresAt: string;
}

export interface TransferPage<T> {
  readonly items: readonly T[];
  readonly page: number;
  readonly size: number;
  readonly hasMore: boolean;
}

export interface TransportRequestSummary {
  readonly transportRequestId: string;
  readonly transferId: string;
  readonly originFacilityId: string;
  readonly destinationFacilityId: string;
  readonly status: string;
  readonly requestedAt: string;
  readonly version: number;
}

export type TransferReadScope = Exclude<OwnerScope, { kind: "PLATFORM" }>;
export type TransportReadScope = Extract<OwnerScope, { kind: "FACILITY" }>;

export interface TransferApiClient {
  loadTransfers(
    scope: TransferReadScope,
    page?: number,
    size?: number,
    signal?: AbortSignal,
  ): Promise<TransferPage<TransferSummary>>;
  loadIncoming(
    scope: TransportReadScope,
    page?: number,
    size?: number,
    signal?: AbortSignal,
  ): Promise<TransferPage<IncomingTransferRequest>>;
  loadTransportRequests(
    scope: TransportReadScope,
    transferId: string,
    page?: number,
    size?: number,
    signal?: AbortSignal,
  ): Promise<TransferPage<TransportRequestSummary>>;
}

type UnknownRecord = Record<string, unknown>;

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function validId(value: unknown): value is string {
  return typeof value === "string" && INTERNAL_ID.test(value);
}

function boundedText(value: unknown, maximum = 96): value is string {
  return (
    typeof value === "string" &&
    value.length > 0 &&
    value.length <= maximum &&
    value.trim() === value
  );
}

function nullableText(value: unknown, maximum = 96): value is string | null {
  return value === null || boundedText(value, maximum);
}

function instant(value: unknown): value is string {
  return boundedText(value, 64) && !Number.isNaN(Date.parse(value));
}

function scopeFromProjection(scope: ScopeProjection): TransferReadScope | null {
  if (
    scope.kind === "PLATFORM" ||
    scope.organization?.issuer !== DIRECTORY_ISSUER ||
    scope.organization.identifierType !== "Organization" ||
    !validId(scope.organization.identifier)
  ) {
    return null;
  }
  if (scope.kind === "ORGANIZATION") {
    return {
      key: `ORGANIZATION:${scope.organization.identifier}`,
      kind: "ORGANIZATION",
      organizationId: scope.organization.identifier,
    };
  }
  if (
    scope.facility?.issuer !== DIRECTORY_ISSUER ||
    scope.facility.identifierType !== "Facility" ||
    !validId(scope.facility.identifier)
  ) {
    return null;
  }
  return {
    key: `FACILITY:${scope.facility.identifier}`,
    kind: "FACILITY",
    organizationId: scope.organization.identifier,
    facilityId: scope.facility.identifier,
  };
}

function scopesFor(
  session: SessionProjection,
  authorityCode: string,
): readonly TransferReadScope[] {
  const capability = session.capabilities.find(
    (candidate) => candidate.authorityCode === authorityCode,
  );
  if (!capability) return [];
  const seen = new Set<string>();
  const scopes: TransferReadScope[] = [];
  for (const projection of capability.scopes) {
    const scope = scopeFromProjection(projection);
    if (scope && !seen.has(scope.key)) {
      seen.add(scope.key);
      scopes.push(scope);
    }
  }
  return scopes;
}

export function transferScopes(session: SessionProjection): readonly TransferReadScope[] {
  return scopesFor(session, "TransferViewAuthority");
}

export function receivingScopes(session: SessionProjection): readonly TransportReadScope[] {
  return transferScopes(session).filter(
    (scope): scope is TransportReadScope => scope.kind === "FACILITY",
  );
}

export function transportScopes(session: SessionProjection): readonly TransportReadScope[] {
  const allowed = new Set([
    "TransportStatusAuthority",
    "TransportRequestAuthority",
    "TransportDispatchCoordinationAuthority",
  ]);
  const seen = new Set<string>();
  const scopes: TransportReadScope[] = [];
  for (const capability of session.capabilities) {
    if (!allowed.has(capability.authorityCode)) continue;
    for (const projection of capability.scopes) {
      const scope = scopeFromProjection(projection);
      if (scope?.kind === "FACILITY" && !seen.has(scope.key)) {
        seen.add(scope.key);
        scopes.push(scope);
      }
    }
  }
  return scopes;
}

function parseTransfer(value: unknown): TransferSummary | null {
  if (
    !isRecord(value) ||
    !validId(value.transferId) ||
    !validId(value.journeyId) ||
    !validId(value.requestAttemptId) ||
    !validId(value.referralId) ||
    !validId(value.originOrganizationId) ||
    !nullableId(value.originFacilityId) ||
    !validId(value.receivingOrganizationId) ||
    !validId(value.receivingFacilityId) ||
    typeof value.status !== "string" ||
    !TRANSFER_STATES.has(value.status) ||
    !Number.isSafeInteger(value.version) ||
    (value.version as number) < 1 ||
    !instant(value.createdAt) ||
    !nullableText(value.reservationOutcome)
  ) {
    return null;
  }
  return {
    transferId: value.transferId,
    journeyId: value.journeyId,
    requestAttemptId: value.requestAttemptId,
    referralId: value.referralId,
    originOrganizationId: value.originOrganizationId,
    originFacilityId: value.originFacilityId,
    receivingOrganizationId: value.receivingOrganizationId,
    receivingFacilityId: value.receivingFacilityId,
    status: value.status,
    version: value.version as number,
    createdAt: value.createdAt,
    reservationOutcome: value.reservationOutcome,
  };
}

function nullableId(value: unknown): value is string | null {
  return value === null || validId(value);
}

function parseIncoming(value: unknown): IncomingTransferRequest | null {
  if (
    !isRecord(value) ||
    !validId(value.requestAttemptId) ||
    !validId(value.journeyId) ||
    !validId(value.referralId) ||
    !validId(value.originOrganizationId) ||
    !nullableId(value.originFacilityId) ||
    !validId(value.receivingFacilityId) ||
    !instant(value.requestedAt) ||
    !instant(value.expiresAt)
  ) {
    return null;
  }
  return {
    requestAttemptId: value.requestAttemptId,
    journeyId: value.journeyId,
    referralId: value.referralId,
    originOrganizationId: value.originOrganizationId,
    originFacilityId: value.originFacilityId,
    receivingFacilityId: value.receivingFacilityId,
    requestedAt: value.requestedAt,
    expiresAt: value.expiresAt,
  };
}

function parseTransport(value: unknown): TransportRequestSummary | null {
  if (
    !isRecord(value) ||
    !validId(value.transportRequestId) ||
    !validId(value.transferId) ||
    !validId(value.originFacilityId) ||
    !validId(value.destinationFacilityId) ||
    !boundedText(value.status, 64) ||
    !instant(value.requestedAt) ||
    !Number.isSafeInteger(value.version) ||
    (value.version as number) < 1
  ) {
    return null;
  }
  return {
    transportRequestId: value.transportRequestId,
    transferId: value.transferId,
    originFacilityId: value.originFacilityId,
    destinationFacilityId: value.destinationFacilityId,
    status: value.status,
    requestedAt: value.requestedAt,
    version: value.version as number,
  };
}

function parsePage<T>(
  value: unknown,
  parseItem: (item: unknown) => T | null,
): TransferPage<T> | null {
  if (
    !isRecord(value) ||
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
  const items = value.items.map(parseItem);
  if (items.some((item) => item === null)) return null;
  return {
    items: items as T[],
    page: value.page as number,
    size: value.size as number,
    hasMore: value.hasMore,
  };
}

function inTransferScope(scope: TransferReadScope, transfer: TransferSummary): boolean {
  if (scope.kind === "ORGANIZATION") {
    return (
      transfer.originOrganizationId === scope.organizationId ||
      transfer.receivingOrganizationId === scope.organizationId
    );
  }
  return (
    transfer.originFacilityId === scope.facilityId ||
    transfer.receivingFacilityId === scope.facilityId
  );
}

export function createTransferApiClient(
  getAccessToken: () => Promise<string | null>,
  fetcher: typeof fetch = fetch,
): TransferApiClient {
  async function request<T>(
    path: string,
    scope: TransferReadScope,
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
      "X-AyuVahak-Organization-Id": scope.organizationId,
    };
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

  function validatePage(page: number, size: number): void {
    if (
      !Number.isSafeInteger(page) ||
      page < 0 ||
      !Number.isSafeInteger(size) ||
      size < 1 ||
      size > 100
    ) {
      throw new OwnerApiError(null, "INVALID_REQUEST");
    }
  }

  return {
    loadTransfers(scope, page = 0, size = 50, signal) {
      validatePage(page, size);
      const params = new URLSearchParams({ page: String(page), size: String(size) });
      return request(
        `/api/v1/transfers?${params}`,
        scope,
        (value) => parsePage(value, parseTransfer),
        signal,
      ).then((result) => {
        if (!result.items.every((item) => inTransferScope(scope, item))) {
          throw new OwnerApiError(null, "INVALID_RESPONSE");
        }
        return result;
      });
    },
    loadIncoming(scope, page = 0, size = 50, signal) {
      validatePage(page, size);
      const params = new URLSearchParams({
        page: String(page),
        size: String(size),
        state: "PENDING",
      });
      return request(
        `/api/v1/transfer-request-attempts?${params}`,
        scope,
        (value) => parsePage(value, parseIncoming),
        signal,
      ).then((result) => {
        if (!result.items.every((item) => item.receivingFacilityId === scope.facilityId)) {
          throw new OwnerApiError(null, "INVALID_RESPONSE");
        }
        return result;
      });
    },
    loadTransportRequests(scope, transferId, page = 0, size = 50, signal) {
      validatePage(page, size);
      if (!INTERNAL_ID.test(transferId))
        return Promise.reject(new OwnerApiError(null, "INVALID_REQUEST"));
      const params = new URLSearchParams({ page: String(page), size: String(size) });
      return request(
        `/api/v1/transfers/${encodeURIComponent(transferId)}/transport-requests?${params}`,
        scope,
        (value) => parsePage(value, parseTransport),
        signal,
      ).then((result) => {
        if (
          !result.items.every(
            (item) =>
              item.transferId === transferId &&
              (item.originFacilityId === scope.facilityId ||
                item.destinationFacilityId === scope.facilityId),
          )
        ) {
          throw new OwnerApiError(null, "INVALID_RESPONSE");
        }
        return result;
      });
    },
  };
}
