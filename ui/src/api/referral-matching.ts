import type { OwnerScope } from "./owner";

const INTERNAL_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const REFERRAL_STATES = new Set(["DRAFT", "SUBMITTED", "CANCELLED", "RESOLVED"]);
const CANDIDATE_STATES = new Set(["RANKED_ELIGIBLE", "INELIGIBLE", "INDETERMINATE"]);

export interface ReferralOrigin {
  readonly organizationId: string;
  readonly facilityId: string | null;
}

export interface ReferralCareRequirement {
  readonly requiredCareCapabilityCodes: readonly string[];
  readonly requestedStart: string | null;
  readonly requestedEnd: string | null;
  readonly permittedGeographyCodes: readonly string[];
  readonly selectedDestinationOrganizationId: string | null;
  readonly priorityCode: string | null;
}

export interface ReferralSummary {
  readonly id: string;
  readonly status: "DRAFT" | "SUBMITTED" | "CANCELLED" | "RESOLVED";
  readonly version: number;
  readonly origin: ReferralOrigin;
  readonly relevantAt: string;
  readonly submittedAt: string | null;
}

export interface ReferralPage {
  readonly items: readonly ReferralSummary[];
  readonly page: number;
  readonly size: number;
  readonly hasMore: boolean;
}

/** PatientReference is deliberately excluded from this presentation model. */
export interface ReferralDetail extends Omit<ReferralSummary, "relevantAt" | "submittedAt"> {
  readonly careRequirement: ReferralCareRequirement;
  readonly submittedRevisionNumber: number | null;
  readonly createdAt: string | null;
  readonly updatedAt: string | null;
}

export interface RecommendationReason {
  readonly code: string;
  readonly subjectCode: string | null;
}

export interface RecommendationCandidate {
  readonly facility: string;
  readonly organization: string;
  readonly status: "RANKED_ELIGIBLE" | "INELIGIBLE" | "INDETERMINATE";
  readonly rank: number | null;
  readonly reasons: readonly RecommendationReason[];
  readonly measure: {
    readonly kind: string;
    readonly value: number;
    readonly unit: string;
  } | null;
}

export interface RecommendationSnapshot {
  readonly id: string;
  readonly referralId: string;
  readonly referralRevision: number;
  readonly generatedAt: string;
  readonly applicableUntil: string;
  readonly outcome: "COMPLETE" | "INDETERMINATE";
  readonly reasonCodes: readonly string[];
  readonly candidates: readonly RecommendationCandidate[];
}

export interface RecommendationHistory {
  readonly referralId: string;
  readonly items: readonly RecommendationSnapshot[];
  readonly latestRecommendationId: string | null;
}

export interface ReferralCreateInput {
  readonly patientReference: {
    readonly issuer: string;
    readonly resourceType: string;
    readonly identifier: string;
  };
  readonly careRequirement: {
    readonly requiredCareCapabilityCodes: readonly string[];
    readonly requestedStart: string | null;
    readonly requestedEnd: string | null;
    readonly permittedGeographyCodes: readonly string[];
    readonly selectedDestinationOrganizationId: string | null;
    readonly priorityCode: string | null;
  };
}

export type ReferralMatchingErrorCode =
  | "UNAUTHENTICATED"
  | "FORBIDDEN"
  | "UNAVAILABLE"
  | "INVALID_RESPONSE"
  | "INVALID_REQUEST";

export class ReferralMatchingApiError extends Error {
  constructor(
    readonly status: number | null,
    readonly code: ReferralMatchingErrorCode,
  ) {
    super("The scoped Referral or Matching service could not complete the request.");
    this.name = "ReferralMatchingApiError";
  }
}

export interface ReferralMatchingApiClient {
  listReferrals(
    scope: OwnerScope,
    page: number,
    status?: ReferralSummary["status"],
    signal?: AbortSignal,
  ): Promise<ReferralPage>;
  getReferral(scope: OwnerScope, id: string, signal?: AbortSignal): Promise<ReferralDetail>;
  createReferral(scope: OwnerScope, input: ReferralCreateInput): Promise<ReferralDetail>;
  getRecommendationHistory(
    scope: OwnerScope,
    referralId: string,
    signal?: AbortSignal,
  ): Promise<RecommendationHistory>;
  generateRecommendation(
    scope: OwnerScope,
    referralId: string,
    revisionNumber: number,
  ): Promise<RecommendationSnapshot>;
}

type UnknownRecord = Record<string, unknown>;

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function boundedText(value: unknown, maximum = 512): value is string {
  return (
    typeof value === "string" &&
    value.length > 0 &&
    value.length <= maximum &&
    value.trim() === value &&
    !hasControlCharacters(value)
  );
}

function hasControlCharacters(value: string): boolean {
  for (const character of value) {
    const codePoint = character.codePointAt(0);
    if (
      codePoint !== undefined &&
      (codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f))
    ) {
      return true;
    }
  }
  return false;
}

function nullableText(value: unknown, maximum = 512): value is string | null {
  return value === null || boundedText(value, maximum);
}

function instant(value: unknown): value is string {
  return boundedText(value, 64) && !Number.isNaN(Date.parse(value));
}

function nullableInstant(value: unknown): value is string | null {
  return value === null || instant(value);
}

function positiveInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) > 0;
}

function parseOrigin(value: unknown): ReferralOrigin | null {
  if (
    !isRecord(value) ||
    typeof value.organizationId !== "string" ||
    !INTERNAL_ID.test(value.organizationId) ||
    !(
      value.facilityId === null ||
      (typeof value.facilityId === "string" && INTERNAL_ID.test(value.facilityId))
    )
  ) {
    return null;
  }
  return { organizationId: value.organizationId, facilityId: value.facilityId as string | null };
}

function parseCareRequirement(value: unknown): ReferralCareRequirement | null {
  if (
    !isRecord(value) ||
    !Array.isArray(value.requiredCareCapabilityCodes) ||
    value.requiredCareCapabilityCodes.length > 20 ||
    !value.requiredCareCapabilityCodes.every(
      (code) => typeof code === "string" && /^[A-Z][A-Z0-9._-]{0,63}$/.test(code),
    ) ||
    !nullableInstant(value.requestedStart) ||
    !nullableInstant(value.requestedEnd) ||
    !Array.isArray(value.permittedGeographyCodes) ||
    value.permittedGeographyCodes.length > 20 ||
    !value.permittedGeographyCodes.every((code) => boundedText(code, 64)) ||
    !nullableText(value.selectedDestinationOrganizationId, 64) ||
    !(
      value.selectedDestinationOrganizationId === null ||
      INTERNAL_ID.test(value.selectedDestinationOrganizationId)
    ) ||
    !nullableText(value.priorityCode, 64)
  ) {
    return null;
  }
  return {
    requiredCareCapabilityCodes: value.requiredCareCapabilityCodes,
    requestedStart: value.requestedStart,
    requestedEnd: value.requestedEnd,
    permittedGeographyCodes: value.permittedGeographyCodes,
    selectedDestinationOrganizationId: value.selectedDestinationOrganizationId,
    priorityCode: value.priorityCode,
  };
}

function scopeContains(scope: OwnerScope, origin: ReferralOrigin): boolean {
  return (
    scope.kind !== "PLATFORM" &&
    scope.organizationId === origin.organizationId &&
    (scope.kind !== "FACILITY" || scope.facilityId === origin.facilityId)
  );
}

function parseReferralSummary(value: unknown): ReferralSummary | null {
  if (
    !isRecord(value) ||
    typeof value.id !== "string" ||
    !INTERNAL_ID.test(value.id) ||
    typeof value.status !== "string" ||
    !REFERRAL_STATES.has(value.status) ||
    !Number.isSafeInteger(value.version) ||
    (value.version as number) < 1 ||
    !instant(value.relevantAt) ||
    !nullableInstant(value.submittedAt)
  ) {
    return null;
  }
  const origin = parseOrigin(value.origin);
  if (!origin) return null;
  return {
    id: value.id,
    status: value.status as ReferralSummary["status"],
    version: value.version as number,
    origin,
    relevantAt: value.relevantAt,
    submittedAt: value.submittedAt,
  };
}

export function parseReferralPage(value: unknown): ReferralPage | null {
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
  const items = value.items.map(parseReferralSummary);
  if (items.some((item) => item === null)) return null;
  return {
    items: items as ReferralSummary[],
    page: value.page as number,
    size: value.size as number,
    hasMore: value.hasMore,
  };
}

export function parseReferralDetail(value: unknown): ReferralDetail | null {
  if (
    !isRecord(value) ||
    typeof value.id !== "string" ||
    !INTERNAL_ID.test(value.id) ||
    typeof value.status !== "string" ||
    !REFERRAL_STATES.has(value.status) ||
    !Number.isSafeInteger(value.version) ||
    (value.version as number) < 1 ||
    !(value.createdAt === undefined || value.createdAt === null || instant(value.createdAt)) ||
    !(value.updatedAt === undefined || value.updatedAt === null || instant(value.updatedAt)) ||
    !(
      value.submittedRevision === undefined ||
      value.submittedRevision === null ||
      isRecord(value.submittedRevision)
    )
  ) {
    return null;
  }
  const origin = parseOrigin(value.origin);
  const careRequirement = parseCareRequirement(value.careRequirement);
  if (!origin || !careRequirement) return null;
  let submittedRevisionNumber: number | null = null;
  if (isRecord(value.submittedRevision)) {
    if (!positiveInteger(value.submittedRevision.revisionNumber)) return null;
    submittedRevisionNumber = value.submittedRevision.revisionNumber;
  }
  return {
    id: value.id,
    status: value.status as ReferralDetail["status"],
    version: value.version as number,
    origin,
    careRequirement,
    submittedRevisionNumber,
    createdAt: nullableInstant(value.createdAt ?? null) ? (value.createdAt ?? null) : null,
    updatedAt: nullableInstant(value.updatedAt ?? null) ? (value.updatedAt ?? null) : null,
  };
}

function parseReference(value: unknown, expectedType: "Facility" | "Organization"): string | null {
  if (
    !isRecord(value) ||
    !boundedText(value.issuer, 256) ||
    value.issuer !== "urn:ayuvahak:directory" ||
    value.resourceType !== expectedType ||
    typeof value.identifier !== "string" ||
    !INTERNAL_ID.test(value.identifier)
  ) {
    return null;
  }
  return value.identifier;
}

function parseRecommendationCandidate(value: unknown): RecommendationCandidate | null {
  if (
    !isRecord(value) ||
    !CANDIDATE_STATES.has(String(value.status)) ||
    !(value.rank === null || (Number.isSafeInteger(value.rank) && (value.rank as number) > 0)) ||
    !Array.isArray(value.reasons) ||
    !Array.isArray(value.directoryCapabilityAssessments) ||
    !Array.isArray(value.evidence)
  ) {
    return null;
  }
  const facility = parseReference(value.facility, "Facility");
  const organization = parseReference(value.organization, "Organization");
  const isRanked = value.status === "RANKED_ELIGIBLE";
  const reasons = value.reasons.map((reason): RecommendationReason | null => {
    if (
      !isRecord(reason) ||
      typeof reason.code !== "string" ||
      !/^[A-Z][A-Z0-9._-]{0,79}$/.test(reason.code) ||
      !(
        reason.subjectCode === null ||
        (typeof reason.subjectCode === "string" &&
          /^[A-Z][A-Z0-9._-]{0,79}$/.test(reason.subjectCode))
      )
    ) {
      return null;
    }
    return { code: reason.code, subjectCode: reason.subjectCode };
  });
  let measure: RecommendationCandidate["measure"] = null;
  if (value.measure !== null) {
    if (
      !isRecord(value.measure) ||
      !boundedText(value.measure.kind, 64) ||
      !boundedText(value.measure.unit, 64) ||
      typeof value.measure.value !== "number" ||
      !Number.isFinite(value.measure.value) ||
      !boundedText(value.measure.sourceCode, 80)
    ) {
      return null;
    }
    measure = { kind: value.measure.kind, value: value.measure.value, unit: value.measure.unit };
  }
  if (
    !facility ||
    !organization ||
    reasons.some((reason) => reason === null) ||
    (isRanked && (value.rank === null || measure === null)) ||
    (!isRanked && (value.rank !== null || measure !== null))
  ) {
    return null;
  }
  return {
    facility,
    organization,
    status: value.status as RecommendationCandidate["status"],
    rank: value.rank as number | null,
    reasons: reasons as RecommendationReason[],
    measure,
  };
}

export function parseRecommendationSnapshot(value: unknown): RecommendationSnapshot | null {
  if (
    !isRecord(value) ||
    typeof value.id !== "string" ||
    !INTERNAL_ID.test(value.id) ||
    typeof value.referralId !== "string" ||
    !INTERNAL_ID.test(value.referralId) ||
    !positiveInteger(value.referralRevision) ||
    !instant(value.generatedAt) ||
    !instant(value.applicableUntil) ||
    !(value.outcome === "COMPLETE" || value.outcome === "INDETERMINATE") ||
    !Array.isArray(value.reasonCodes) ||
    !value.reasonCodes.every(
      (code) => typeof code === "string" && /^[A-Z][A-Z0-9._-]{0,79}$/.test(code),
    ) ||
    !Array.isArray(value.candidates) ||
    value.candidates.length > 500
  ) {
    return null;
  }
  const candidates = value.candidates.map(parseRecommendationCandidate);
  if (candidates.some((candidate) => candidate === null)) return null;
  return {
    id: value.id,
    referralId: value.referralId,
    referralRevision: value.referralRevision,
    generatedAt: value.generatedAt,
    applicableUntil: value.applicableUntil,
    outcome: value.outcome,
    reasonCodes: value.reasonCodes,
    candidates: candidates as RecommendationCandidate[],
  };
}

export function parseRecommendationHistory(value: unknown): RecommendationHistory | null {
  if (
    !isRecord(value) ||
    typeof value.referralId !== "string" ||
    !INTERNAL_ID.test(value.referralId) ||
    !Array.isArray(value.items) ||
    value.items.length > 100 ||
    !(
      value.latestRecommendationId === undefined ||
      value.latestRecommendationId === null ||
      typeof value.latestRecommendationId === "string"
    )
  ) {
    return null;
  }
  const items = value.items.map(parseRecommendationSnapshot);
  if (
    items.some((item) => item === null) ||
    (value.latestRecommendationId !== undefined &&
      value.latestRecommendationId !== null &&
      !INTERNAL_ID.test(value.latestRecommendationId))
  ) {
    return null;
  }
  return {
    referralId: value.referralId,
    items: items as RecommendationSnapshot[],
    latestRecommendationId: value.latestRecommendationId ?? null,
  };
}

export function createReferralMatchingApiClient(
  getAccessToken: () => Promise<string | null>,
  fetcher: typeof fetch = fetch,
): ReferralMatchingApiClient {
  async function request<T>(
    path: string,
    scope: OwnerScope,
    method: "GET" | "POST",
    parse: (value: unknown) => T | null,
    body?: unknown,
    signal?: AbortSignal,
  ): Promise<T> {
    if (scope.kind === "PLATFORM") throw new ReferralMatchingApiError(null, "INVALID_REQUEST");
    const token = await getAccessToken();
    if (!token || token.length > 8192 || !/^[A-Za-z0-9\-._~+/]+=*$/.test(token)) {
      throw new ReferralMatchingApiError(401, "UNAUTHENTICATED");
    }
    const headers: Record<string, string> = {
      Accept: "application/json",
      Authorization: `Bearer ${token}`,
      "X-AyuVahak-Execution-Scope": scope.kind,
      "X-AyuVahak-Organization-Id": scope.organizationId,
    };
    if (scope.kind === "FACILITY") headers["X-AyuVahak-Facility-Id"] = scope.facilityId;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (method === "POST") headers["Idempotency-Key"] = crypto.randomUUID();
    let response: Response;
    try {
      response = await fetcher(path, {
        method,
        headers,
        credentials: "omit",
        cache: "no-store",
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        ...(signal === undefined ? {} : { signal }),
      });
    } catch {
      throw new ReferralMatchingApiError(null, "UNAVAILABLE");
    }
    if (response.status === 401) throw new ReferralMatchingApiError(401, "UNAUTHENTICATED");
    if (response.status === 403) throw new ReferralMatchingApiError(403, "FORBIDDEN");
    if (!response.ok) throw new ReferralMatchingApiError(response.status, "UNAVAILABLE");
    try {
      const parsed = parse(await response.json());
      if (parsed === null) throw new ReferralMatchingApiError(response.status, "INVALID_RESPONSE");
      return parsed;
    } catch (error: unknown) {
      if (error instanceof ReferralMatchingApiError) throw error;
      throw new ReferralMatchingApiError(response.status, "INVALID_RESPONSE");
    }
  }

  return {
    listReferrals(scope, page, status, signal) {
      if (!Number.isSafeInteger(page) || page < 0) {
        return Promise.reject(new ReferralMatchingApiError(null, "INVALID_REQUEST"));
      }
      const parameters = new URLSearchParams({ page: String(page), size: "25" });
      if (status) parameters.set("status", status);
      return request(
        `/api/v1/referrals?${parameters.toString()}`,
        scope,
        "GET",
        parseReferralPage,
        undefined,
        signal,
      ).then((result) => {
        if (
          !result.items.every((item) => scopeContains(scope, item.origin)) ||
          (status !== undefined && !result.items.every((item) => item.status === status))
        ) {
          throw new ReferralMatchingApiError(null, "INVALID_RESPONSE");
        }
        return result;
      });
    },
    getReferral(scope, id, signal) {
      if (!INTERNAL_ID.test(id)) {
        return Promise.reject(new ReferralMatchingApiError(null, "INVALID_REQUEST"));
      }
      return request(
        `/api/v1/referrals/${encodeURIComponent(id)}`,
        scope,
        "GET",
        parseReferralDetail,
        undefined,
        signal,
      ).then((result) => {
        if (result.id !== id || !scopeContains(scope, result.origin)) {
          throw new ReferralMatchingApiError(null, "INVALID_RESPONSE");
        }
        return result;
      });
    },
    createReferral(scope, input) {
      return request("/api/v1/referrals", scope, "POST", parseReferralDetail, input).then(
        (result) => {
          if (!scopeContains(scope, result.origin)) {
            throw new ReferralMatchingApiError(null, "INVALID_RESPONSE");
          }
          return result;
        },
      );
    },
    getRecommendationHistory(scope, referralId, signal) {
      if (!INTERNAL_ID.test(referralId)) {
        return Promise.reject(new ReferralMatchingApiError(null, "INVALID_REQUEST"));
      }
      return request(
        `/api/v1/referrals/${encodeURIComponent(referralId)}/recommendations?limit=20`,
        scope,
        "GET",
        parseRecommendationHistory,
        undefined,
        signal,
      ).then((result) => {
        if (
          result.referralId !== referralId ||
          !result.items.every((item) => item.referralId === referralId)
        ) {
          throw new ReferralMatchingApiError(null, "INVALID_RESPONSE");
        }
        return result;
      });
    },
    generateRecommendation(scope, referralId, revisionNumber) {
      if (!INTERNAL_ID.test(referralId) || !positiveInteger(revisionNumber)) {
        return Promise.reject(new ReferralMatchingApiError(null, "INVALID_REQUEST"));
      }
      return request("/api/v1/recommendations", scope, "POST", parseRecommendationSnapshot, {
        referralId,
        revisionNumber,
      }).then((result) => {
        if (result.referralId !== referralId || result.referralRevision !== revisionNumber) {
          throw new ReferralMatchingApiError(null, "INVALID_RESPONSE");
        }
        return result;
      });
    },
  };
}
