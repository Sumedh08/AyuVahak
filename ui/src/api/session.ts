export type SubjectKind = "HUMAN" | "SERVICE";
export type SubjectIdentifierType = "subject" | "client_id";
export type ScopeKind = "PLATFORM" | "ORGANIZATION" | "FACILITY";
export type DeploymentMode = "EMPTY" | "SYNTHETIC_DEMO";

export interface SubjectProjection {
  readonly kind: SubjectKind;
  readonly issuer: string;
  readonly identifierType: SubjectIdentifierType;
  readonly identifier: string;
}

export interface ExternalIdentifier {
  readonly issuer: string;
  readonly identifierType: string;
  readonly identifier: string;
}

export interface ScopeProjection {
  readonly kind: ScopeKind;
  readonly organization?: ExternalIdentifier;
  readonly facility?: ExternalIdentifier;
}

export interface CapabilityProjection {
  readonly authorityCode: string;
  readonly scopes: readonly ScopeProjection[];
}

export interface SessionProjection {
  readonly subject: SubjectProjection;
  readonly capabilities: readonly CapabilityProjection[];
  readonly deploymentMode: DeploymentMode;
}

export class SessionApiError extends Error {
  constructor(
    readonly status: number | null,
    readonly code: "UNAUTHENTICATED" | "FORBIDDEN" | "UNAVAILABLE" | "INVALID_RESPONSE",
  ) {
    super("The authenticated session could not be loaded.");
    this.name = "SessionApiError";
  }
}

type UnknownRecord = Record<string, unknown>;

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasOnlyKeys(record: UnknownRecord, keys: readonly string[]): boolean {
  return Object.keys(record).every((key) => keys.includes(key));
}

function isBoundedString(value: unknown, maximumLength = 256): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= maximumLength;
}

function isOpaqueText(value: unknown, maximumLength = 256): value is string {
  return (
    isBoundedString(value, maximumLength) && value === value.trim() && !hasControlCharacters(value)
  );
}

function isIssuer(value: unknown): value is string {
  if (!isBoundedString(value)) return false;

  try {
    const issuer = new URL(value);
    return (
      (issuer.protocol === "https:" || issuer.protocol === "http:") &&
      issuer.username === "" &&
      issuer.password === ""
    );
  } catch {
    return false;
  }
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

function isExternalIssuer(value: unknown): value is string {
  return isOpaqueText(value);
}

function parseExternalIdentifier(value: unknown): ExternalIdentifier | null {
  if (
    !isRecord(value) ||
    !hasOnlyKeys(value, ["issuer", "identifierType", "identifier"]) ||
    !isExternalIssuer(value.issuer) ||
    !isOpaqueText(value.identifierType, 64) ||
    !/^[A-Za-z][A-Za-z0-9._-]{0,63}$/.test(value.identifierType) ||
    !isOpaqueText(value.identifier)
  ) {
    return null;
  }

  return {
    issuer: value.issuer,
    identifierType: value.identifierType,
    identifier: value.identifier,
  };
}

function parseScope(value: unknown): ScopeProjection | null {
  if (!isRecord(value) || typeof value.kind !== "string") return null;

  if (value.kind === "PLATFORM") {
    if (!hasOnlyKeys(value, ["kind"])) return null;
    return { kind: "PLATFORM" };
  }

  if (value.kind === "ORGANIZATION") {
    if (!hasOnlyKeys(value, ["kind", "organization"])) return null;
    const organization = parseExternalIdentifier(value.organization);
    return organization ? { kind: "ORGANIZATION", organization } : null;
  }

  if (value.kind === "FACILITY") {
    if (!hasOnlyKeys(value, ["kind", "organization", "facility"])) return null;
    const organization = parseExternalIdentifier(value.organization);
    const facility = parseExternalIdentifier(value.facility);
    return organization && facility ? { kind: "FACILITY", organization, facility } : null;
  }

  return null;
}

export function parseSessionProjection(value: unknown): SessionProjection | null {
  if (
    !isRecord(value) ||
    !hasOnlyKeys(value, ["subject", "capabilities", "deploymentMode"]) ||
    (value.deploymentMode !== "EMPTY" && value.deploymentMode !== "SYNTHETIC_DEMO") ||
    !isRecord(value.subject) ||
    !hasOnlyKeys(value.subject, ["kind", "issuer", "identifierType", "identifier"])
  ) {
    return null;
  }

  const { subject } = value;
  const subjectKind = subject.kind;
  const identifierType = subject.identifierType;
  if (
    (subjectKind !== "HUMAN" && subjectKind !== "SERVICE") ||
    (identifierType !== "subject" && identifierType !== "client_id") ||
    (subjectKind === "HUMAN" && identifierType !== "subject") ||
    (subjectKind === "SERVICE" && identifierType !== "client_id") ||
    !isIssuer(subject.issuer) ||
    !isBoundedString(subject.identifier)
  ) {
    return null;
  }

  if (!Array.isArray(value.capabilities)) return null;

  const authorityCodes = new Set<string>();
  const capabilities: CapabilityProjection[] = [];
  for (const capability of value.capabilities) {
    if (
      !isRecord(capability) ||
      !hasOnlyKeys(capability, ["authorityCode", "scopes"]) ||
      !isBoundedString(capability.authorityCode, 96) ||
      !/^[A-Z][A-Za-z0-9]*$/.test(capability.authorityCode) ||
      authorityCodes.has(capability.authorityCode) ||
      !Array.isArray(capability.scopes)
    ) {
      return null;
    }

    const scopes = capability.scopes.map(parseScope);
    if (scopes.some((scope) => scope === null)) return null;
    authorityCodes.add(capability.authorityCode);
    capabilities.push({
      authorityCode: capability.authorityCode,
      scopes: scopes as ScopeProjection[],
    });
  }

  return {
    subject: {
      kind: subjectKind,
      issuer: subject.issuer,
      identifierType,
      identifier: subject.identifier,
    },
    capabilities,
    deploymentMode: value.deploymentMode,
  };
}

export interface SessionClient {
  loadSession(accessToken: string, signal?: AbortSignal): Promise<SessionProjection>;
}

export function createSessionClient(fetcher: typeof fetch = fetch): SessionClient {
  return {
    async loadSession(accessToken, signal) {
      if (!isBoundedString(accessToken, 8192) || !/^[A-Za-z0-9\-._~+/]+=*$/.test(accessToken)) {
        throw new SessionApiError(null, "UNAUTHENTICATED");
      }

      let response: Response;
      try {
        response = await fetcher("/api/v1/session", {
          method: "GET",
          headers: {
            Accept: "application/json",
            Authorization: `Bearer ${accessToken}`,
          },
          credentials: "omit",
          cache: "no-store",
          ...(signal ? { signal } : {}),
        });
      } catch {
        throw new SessionApiError(null, "UNAVAILABLE");
      }

      if (response.status === 401) throw new SessionApiError(401, "UNAUTHENTICATED");
      if (response.status === 403) throw new SessionApiError(403, "FORBIDDEN");
      if (!response.ok) throw new SessionApiError(response.status, "UNAVAILABLE");

      try {
        const session = parseSessionProjection(await response.json());
        if (!session) throw new SessionApiError(response.status, "INVALID_RESPONSE");
        return session;
      } catch (error: unknown) {
        if (error instanceof SessionApiError) throw error;
        throw new SessionApiError(response.status, "INVALID_RESPONSE");
      }
    },
  };
}
