import { OwnerApiError } from "./owner";

export const OPERATOR_SUPPORT_TOPICS = [
  { key: "CAPACITY_STALENESS", label: "Understand stale or unknown Capacity" },
  { key: "FEDERATION_OUTCOME_UNKNOWN", label: "Handle an unknown federation outcome" },
  { key: "TRANSFER_RECONCILIATION", label: "Review Transfer reconciliation" },
  { key: "DHIS2_UNKNOWN_OUTCOME", label: "Handle an unresolved DHIS2 export" },
  { key: "DATABASE_RESTORE", label: "Follow the database restore procedure" },
  { key: "IDENTITY_PROVIDER_OUTAGE", label: "Respond to an identity-provider outage" },
  { key: "RATE_LIMIT_OPERATION", label: "Understand request-rate limits" },
] as const;

export type OperatorSupportQuestionKey = (typeof OPERATOR_SUPPORT_TOPICS)[number]["key"];
export type OperatorSupportStatus = "ADVISORY" | "ABSTAINED";
export type OperatorSupportProvider = "DETERMINISTIC_LOCAL" | "OPENAI_COMPATIBLE";

export interface OperatorSupportProfileStatus {
  readonly enabled: boolean;
  readonly provider: OperatorSupportProvider;
  readonly configurationValid: boolean;
  readonly secretReferenceConfigured: boolean;
}

export function parseOperatorSupportQuestionKey(value: string): OperatorSupportQuestionKey | null {
  return OPERATOR_SUPPORT_TOPICS.find((topic) => topic.key === value)?.key ?? null;
}

export interface OperatorSupportCitation {
  readonly sourceId: string;
  readonly path: string;
  readonly section: string;
  readonly contentHash: string;
}

export interface OperatorSupportResponse {
  readonly status: OperatorSupportStatus;
  readonly answer: string;
  readonly sources: readonly OperatorSupportCitation[];
  readonly capabilityVersion: string;
  readonly corpusVersion: string;
  readonly reasonCode:
    | "NONE"
    | "DISABLED"
    | "NO_APPROVED_EVIDENCE"
    | "INVALID_PROVIDER_OUTPUT"
    | "PROVIDER_UNAVAILABLE";
}

export interface OperatorSupportApiClient {
  profileStatus(signal?: AbortSignal): Promise<OperatorSupportProfileStatus>;
  answer(
    questionKey: OperatorSupportQuestionKey,
    signal?: AbortSignal,
  ): Promise<OperatorSupportResponse>;
}

type UnknownRecord = Record<string, unknown>;

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasOnlyKeys(value: UnknownRecord, keys: readonly string[]): boolean {
  return Object.keys(value).every((key) => keys.includes(key));
}

function boundedText(value: unknown, maxLength: number): value is string {
  return (
    typeof value === "string" &&
    value.length > 0 &&
    value.length <= maxLength &&
    value.trim() === value &&
    [...value].every((character) => {
      const codePoint = character.codePointAt(0);
      return (
        codePoint !== undefined && codePoint >= 0x20 && !(codePoint >= 0x7f && codePoint <= 0x9f)
      );
    })
  );
}

function safeAnswer(value: unknown): value is string {
  return (
    typeof value === "string" &&
    value.length <= 1400 &&
    [...value].every((character) => {
      const codePoint = character.codePointAt(0);
      return (
        codePoint !== undefined &&
        !(codePoint < 0x20 && codePoint !== 0x0a && codePoint !== 0x09) &&
        !(codePoint >= 0x7f && codePoint <= 0x9f)
      );
    })
  );
}

function parseCitation(value: unknown): OperatorSupportCitation | null {
  if (
    !isRecord(value) ||
    !hasOnlyKeys(value, ["sourceId", "path", "section", "contentHash"]) ||
    !boundedText(value.sourceId, 64) ||
    !boundedText(value.path, 160) ||
    value.path.startsWith("/") ||
    value.path.includes("..") ||
    !boundedText(value.section, 120) ||
    typeof value.contentHash !== "string" ||
    !/^[a-f0-9]{64}$/u.test(value.contentHash)
  ) {
    return null;
  }
  return {
    sourceId: value.sourceId,
    path: value.path,
    section: value.section,
    contentHash: value.contentHash,
  };
}

function parseResponse(value: unknown): OperatorSupportResponse | null {
  if (
    !isRecord(value) ||
    !hasOnlyKeys(value, [
      "status",
      "answer",
      "sources",
      "capabilityVersion",
      "corpusVersion",
      "reasonCode",
    ]) ||
    (value.status !== "ADVISORY" && value.status !== "ABSTAINED") ||
    !safeAnswer(value.answer) ||
    !Array.isArray(value.sources) ||
    value.sources.length > 4 ||
    !boundedText(value.capabilityVersion, 80) ||
    !boundedText(value.corpusVersion, 80) ||
    ![
      "NONE",
      "DISABLED",
      "NO_APPROVED_EVIDENCE",
      "INVALID_PROVIDER_OUTPUT",
      "PROVIDER_UNAVAILABLE",
    ].includes(String(value.reasonCode))
  ) {
    return null;
  }
  const sources = value.sources.map(parseCitation);
  if (sources.some((source) => source === null)) return null;
  const reasonCode = value.reasonCode as OperatorSupportResponse["reasonCode"];
  if (
    value.status === "ADVISORY" &&
    (value.answer.trim().length === 0 || sources.length === 0 || reasonCode !== "NONE")
  ) {
    return null;
  }
  if (
    value.status === "ABSTAINED" &&
    (value.answer !== "" || sources.length !== 0 || reasonCode === "NONE")
  ) {
    return null;
  }
  return {
    status: value.status,
    answer: value.answer,
    sources: sources as OperatorSupportCitation[],
    capabilityVersion: value.capabilityVersion,
    corpusVersion: value.corpusVersion,
    reasonCode,
  };
}

function parseProfileStatus(value: unknown): OperatorSupportProfileStatus | null {
  if (
    !isRecord(value) ||
    !hasOnlyKeys(value, [
      "enabled",
      "provider",
      "configurationValid",
      "secretReferenceConfigured",
    ]) ||
    typeof value.enabled !== "boolean" ||
    (value.provider !== "DETERMINISTIC_LOCAL" && value.provider !== "OPENAI_COMPATIBLE") ||
    typeof value.configurationValid !== "boolean" ||
    typeof value.secretReferenceConfigured !== "boolean"
  ) {
    return null;
  }
  return {
    enabled: value.enabled,
    provider: value.provider,
    configurationValid: value.configurationValid,
    secretReferenceConfigured: value.secretReferenceConfigured,
  };
}

export function createOperatorSupportApiClient(
  getAccessToken: () => Promise<string | null>,
  fetcher: typeof fetch = fetch,
): OperatorSupportApiClient {
  async function authorizedFetch(
    path: string,
    init: RequestInit = {},
    signal?: AbortSignal,
  ): Promise<Response> {
    const token = await getAccessToken();
    if (!token || token.length > 8192 || !/^[A-Za-z0-9\-._~+/]+=*$/u.test(token)) {
      throw new OwnerApiError(401, "UNAUTHENTICATED");
    }
    let response: Response;
    try {
      response = await fetcher(path, {
        ...init,
        headers: {
          ...Object.fromEntries(new Headers(init.headers).entries()),
          Accept: "application/json",
          Authorization: `Bearer ${token}`,
        },
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
    return response;
  }

  return {
    async profileStatus(signal) {
      const response = await authorizedFetch("/api/v1/operator-support/profile", {}, signal);
      try {
        const result = parseProfileStatus(await response.json());
        if (!result) throw new OwnerApiError(response.status, "INVALID_RESPONSE");
        return result;
      } catch (error: unknown) {
        if (error instanceof OwnerApiError) throw error;
        throw new OwnerApiError(response.status, "INVALID_RESPONSE");
      }
    },
    async answer(questionKey, signal) {
      const response = await authorizedFetch(
        "/api/v1/operator-support/answers",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ questionKey }),
        },
        signal,
      );
      try {
        const result = parseResponse(await response.json());
        if (!result) throw new OwnerApiError(response.status, "INVALID_RESPONSE");
        return result;
      } catch (error: unknown) {
        if (error instanceof OwnerApiError) throw error;
        throw new OwnerApiError(response.status, "INVALID_RESPONSE");
      }
    },
  };
}
