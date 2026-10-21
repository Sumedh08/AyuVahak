import type { FormEvent } from "react";
import { useMemo, useRef, useState } from "react";
import {
  type CapacityCurrentProjection,
  capacityScopes,
  createOwnerApiClient,
  type OwnerApiClient,
  OwnerApiError,
} from "../api/owner";
import type { SessionProjection } from "../api/session";

interface CapacityWorkspaceProps {
  readonly session: SessionProjection;
  readonly getAccessToken: () => Promise<string | null>;
  readonly apiClient?: OwnerApiClient;
}

type QueryState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | { readonly status: "error"; readonly code: string }
  | { readonly status: "loaded"; readonly result: CapacityCurrentProjection };

function capacityErrorCode(error: unknown): string {
  return error instanceof OwnerApiError ? error.code : "UNAVAILABLE";
}

export function CapacityWorkspace({ session, getAccessToken, apiClient }: CapacityWorkspaceProps) {
  const scopes = useMemo(() => capacityScopes(session), [session]);
  const api = useMemo(
    () => apiClient ?? createOwnerApiClient(getAccessToken),
    [apiClient, getAccessToken],
  );
  const [facilityId, setFacilityId] = useState(scopes[0]?.facilityId ?? "");
  const [dimension, setDimension] = useState("");
  const [query, setQuery] = useState<QueryState>({ status: "idle" });
  const requestVersion = useRef(0);
  const selectedScope = scopes.find((scope) => scope.facilityId === facilityId);

  async function loadCurrent(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    const currentRequest = ++requestVersion.current;
    if (!selectedScope || !/^[A-Z][A-Z0-9._-]{0,63}$/.test(dimension.trim())) {
      setQuery({ status: "error", code: "INVALID_REQUEST" });
      return;
    }
    setQuery({ status: "loading" });
    try {
      const result = await api.loadCurrentCapacity(selectedScope, dimension.trim());
      if (requestVersion.current !== currentRequest) return;
      setQuery({ status: "loaded", result });
    } catch (error: unknown) {
      if (requestVersion.current !== currentRequest) return;
      setQuery({ status: "error", code: capacityErrorCode(error) });
    }
  }

  return (
    <section className="workspace-panel" aria-labelledby="capacity-title">
      <div className="workspace-intro">
        <p className="eyebrow">CAPACITY OWNER DATA</p>
        <h2 id="capacity-title">Current Capacity evidence</h2>
        <p>
          Freshness describes evidence state. It is not a readiness, availability, acceptance, or
          reservation decision.
        </p>
      </div>
      {scopes.length === 0 ? (
        <div className="workspace-state" role="status">
          <h3>Capacity review scope is not available</h3>
          <p>This session has no recognized facility scope for CapacityEvidenceReview.</p>
        </div>
      ) : (
        <form className="query-form" onSubmit={(event) => void loadCurrent(event)}>
          <label className="field-label" htmlFor="capacity-facility">
            Authorized facility
            <select
              id="capacity-facility"
              value={facilityId}
              onChange={(event) => {
                setFacilityId(event.currentTarget.value);
                requestVersion.current += 1;
                setQuery({ status: "idle" });
              }}
            >
              {scopes.map((scope) => (
                <option key={scope.key} value={scope.facilityId}>
                  {scope.facilityId}
                </option>
              ))}
            </select>
          </label>
          <label className="field-label" htmlFor="capacity-dimension">
            Configured dimension code
            <input
              id="capacity-dimension"
              autoComplete="off"
              maxLength={64}
              pattern="[A-Z][A-Z0-9._-]{0,63}"
              value={dimension}
              onChange={(event) => {
                requestVersion.current += 1;
                setDimension(event.currentTarget.value);
                setQuery({ status: "idle" });
              }}
              placeholder="Example: SYN_AVAILABLE_BEDS"
            />
          </label>
          <p className="field-help">
            Dimension codes are deployment-configured. This workspace does not invent or list
            unconfigured dimensions.
          </p>
          <button
            className="button button-primary"
            type="submit"
            disabled={query.status === "loading" || dimension.trim().length === 0}
          >
            Check current evidence
          </button>
        </form>
      )}
      {query.status === "loading" && (
        <div className="workspace-state" role="status" aria-busy="true">
          Loading current Capacity evidence…
        </div>
      )}
      {query.status === "error" && (
        <div className="workspace-state workspace-error" role="alert">
          <h3>
            {query.code === "FORBIDDEN"
              ? "Capacity access was denied"
              : query.code === "UNAUTHENTICATED"
                ? "Sign in again to continue"
                : query.code === "INVALID_REQUEST"
                  ? "Enter a configured dimension code"
                  : "Capacity evidence is unavailable"}
          </h3>
          <p>
            {query.code === "FORBIDDEN"
              ? "The Capacity owner rejected this scoped request. No evidence is shown."
              : query.code === "INVALID_REQUEST"
                ? "Use the configured uppercase dimension code. No request was made."
                : "The Capacity owner did not return a usable response. Unavailable evidence is not zero."}
          </p>
        </div>
      )}
      {query.status === "loaded" && (
        <CapacityResult result={query.result} requestedDimension={dimension.trim()} />
      )}
    </section>
  );
}

function CapacityResult({
  result,
  requestedDimension,
}: {
  readonly result: CapacityCurrentProjection;
  readonly requestedDimension: string;
}) {
  const evidence = result.dimensions.find((item) => item.dimensionCode === requestedDimension);
  if (!evidence) {
    return (
      <div className="workspace-state" role="status">
        <h3>No evidence returned for {requestedDimension}</h3>
        <p>The owner response did not contain this dimension. No value or readiness is inferred.</p>
      </div>
    );
  }
  const freshnessLabel: Record<typeof evidence.freshness, string> = {
    CURRENT: "Current evidence",
    STALE: "Stale evidence",
    EXPIRED: "Expired evidence",
    UNKNOWN: "Unknown evidence",
    CONFLICTED: "Conflicting evidence",
  };
  return (
    <article
      className={`capacity-result freshness-${evidence.freshness.toLowerCase()}`}
      aria-labelledby="capacity-result-title"
    >
      <div className="result-heading">
        <div>
          <p className="eyebrow">{evidence.dimensionCode}</p>
          <h3 id="capacity-result-title">{freshnessLabel[evidence.freshness]}</h3>
        </div>
        <span className="freshness-tag">{evidence.freshness}</span>
      </div>
      <dl className="result-facts">
        <div>
          <dt>Facility</dt>
          <dd>{result.facilityId}</dd>
        </div>
        <div>
          <dt>Evaluated</dt>
          <dd>
            <time dateTime={result.evaluatedAt}>
              {new Date(result.evaluatedAt).toLocaleString()}
            </time>
          </dd>
        </div>
        <div>
          <dt>Value</dt>
          <dd>
            {evidence.quantity !== null ? (
              <>
                {evidence.quantity}
                {evidence.unit ? ` ${evidence.unit}` : ""}
              </>
            ) : evidence.state !== null ? (
              evidence.state
            ) : (
              "No value reported"
            )}
          </dd>
        </div>
      </dl>
      {evidence.freshness !== "CURRENT" && (
        <p className="freshness-explanation">
          This evidence is {evidence.freshness.toLowerCase()}. It is not treated as zero, available,
          or ready.
        </p>
      )}
      {evidence.reasonCodes.length > 0 && (
        <p className="reason-codes">Reason codes: {evidence.reasonCodes.join(", ")}</p>
      )}
    </article>
  );
}
