import { useEffect, useMemo, useState } from "react";
import {
  type AnalyticsApiClient,
  type AnalyticsReadScope,
  type AnalyticsSnapshotPage,
  analyticsScopes,
  createAnalyticsApiClient,
} from "../api/analytics";
import { OwnerApiError } from "../api/owner";
import type { SessionProjection } from "../api/session";

interface AnalyticsWorkspaceProps {
  readonly session: SessionProjection;
  readonly getAccessToken: () => Promise<string | null>;
  readonly apiClient?: AnalyticsApiClient;
}

type QueryState =
  | { readonly status: "loading" }
  | { readonly status: "error"; readonly code: string }
  | { readonly status: "loaded"; readonly page: AnalyticsSnapshotPage };

function errorCode(error: unknown): string {
  return error instanceof OwnerApiError ? error.code : "UNAVAILABLE";
}

function scopeLabel(scope: AnalyticsReadScope): string {
  return scope.kind === "FACILITY"
    ? `Facility ${scope.facilityId}`
    : `Organization ${scope.organizationId}`;
}

function exportLabel(state: string | null, result: string | null): string {
  if (!state) return "Not requested";
  if (state === "OUTCOME_UNKNOWN" || state === "RECONCILIATION_REQUIRED") {
    return "Unresolved — reconciliation required";
  }
  if (state === "WITHHELD") return "Withheld by disclosure gate";
  if (state === "ACKNOWLEDGED") return `Acknowledged${result ? ` · ${result}` : ""}`;
  return state.replaceAll("_", " ");
}

export function AnalyticsWorkspace({
  session,
  getAccessToken,
  apiClient,
}: AnalyticsWorkspaceProps) {
  const scopes = useMemo(() => analyticsScopes(session), [session]);
  const api = useMemo(
    () => apiClient ?? createAnalyticsApiClient(getAccessToken),
    [apiClient, getAccessToken],
  );
  const [scopeKey, setScopeKey] = useState(scopes[0]?.key ?? "");
  const [pageNumber, setPageNumber] = useState(0);
  const [state, setState] = useState<QueryState>({ status: "loading" });
  const scope = scopes.find((item) => item.key === scopeKey);

  useEffect(() => {
    if (!scope) {
      setState({
        status: "loaded",
        page: { items: [], page: 0, size: 50, hasMore: false },
      });
      return;
    }
    const controller = new AbortController();
    let active = true;
    setState({ status: "loading" });
    void api
      .loadSnapshots(scope, pageNumber, 50, controller.signal)
      .then((page) => {
        if (active) setState({ status: "loaded", page });
      })
      .catch((error: unknown) => {
        if (active && !controller.signal.aborted)
          setState({ status: "error", code: errorCode(error) });
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [api, pageNumber, scope]);

  return (
    <section className="workspace-panel" aria-labelledby="analytics-title">
      <div className="workspace-intro">
        <p className="eyebrow">ANALYTICS OWNER DATA</p>
        <h2 id="analytics-title">Aggregate indicators and exports</h2>
        <p>
          This view contains aggregate snapshots only. Completeness is shown separately from the
          numeric result; a missing or stale source is never presented as zero.
        </p>
      </div>
      {scopes.length === 0 ? (
        <div className="workspace-state" role="status">
          <h3>Analytics read scope is not available</h3>
          <p>
            This session has no recognized organization or facility scope for
            AnalyticsAggregateRead.
          </p>
        </div>
      ) : (
        <>
          {scopes.length > 1 && (
            <label className="field-label" htmlFor="analytics-scope">
              Authorized reporting scope
              <select
                id="analytics-scope"
                value={scopeKey}
                onChange={(event) => {
                  setScopeKey(event.currentTarget.value);
                  setPageNumber(0);
                }}
              >
                {scopes.map((item) => (
                  <option key={item.key} value={item.key}>
                    {scopeLabel(item)}
                  </option>
                ))}
              </select>
            </label>
          )}
          <p className="field-help">Scope: {scope ? scopeLabel(scope) : "Not selected"}</p>
          {state.status === "loading" ? (
            <div className="workspace-state" role="status" aria-busy="true">
              Loading authorized aggregate snapshots…
            </div>
          ) : state.status === "error" ? (
            <div className="workspace-state workspace-error" role="alert">
              <h3>
                {state.code === "FORBIDDEN"
                  ? "Analytics access was denied"
                  : state.code === "UNAUTHENTICATED"
                    ? "Sign in again to continue"
                    : "Aggregate snapshots are unavailable"}
              </h3>
              <p>
                {state.code === "FORBIDDEN"
                  ? "The Analytics owner rejected this scoped request. No aggregate data is shown."
                  : "The Analytics owner did not return a usable response. No result is inferred."}
              </p>
              <button
                className="button button-quiet"
                type="button"
                onClick={() => window.location.reload()}
              >
                Retry
              </button>
            </div>
          ) : state.page.items.length === 0 ? (
            <div className="workspace-state" role="status">
              <h3>No aggregate snapshots were returned</h3>
              <p>An empty page does not mean the indicator result is zero.</p>
            </div>
          ) : (
            <>
              <div className="table-scroll">
                <table>
                  <caption>
                    Authorized aggregate snapshots for{" "}
                    {scope ? scopeLabel(scope) : "selected scope"}
                  </caption>
                  <thead>
                    <tr>
                      <th scope="col">Indicator</th>
                      <th scope="col">Reporting period</th>
                      <th scope="col">Result</th>
                      <th scope="col">Revision</th>
                      <th scope="col">Local export</th>
                      <th scope="col">Computed</th>
                    </tr>
                  </thead>
                  <tbody>
                    {state.page.items.map((snapshot) => (
                      <tr key={snapshot.snapshotId}>
                        <th scope="row">
                          <span>
                            {snapshot.indicatorKey} v{snapshot.indicatorVersion}
                          </span>
                          <small className="facility-id-label">{snapshot.snapshotId}</small>
                        </th>
                        <td>
                          {snapshot.periodStart} – {snapshot.periodEndExclusive} (exclusive)
                          <small className="facility-id-label">{snapshot.timeZone}</small>
                        </td>
                        <td>
                          <span className="state-tag">
                            {snapshot.completeness.replaceAll("_", " ")}
                          </span>
                          <strong className="analytics-value">
                            {snapshot.value === null ? "Value unavailable" : snapshot.value}
                          </strong>
                        </td>
                        <td>
                          {snapshot.revision === null
                            ? "No revision series"
                            : `r${snapshot.revision} · ${snapshot.revisionState}`}
                        </td>
                        <td>{exportLabel(snapshot.exportState, snapshot.exportResultClass)}</td>
                        <td>{new Date(snapshot.computedAt).toLocaleString()}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="pager">
                <span>
                  Page {state.page.page + 1} · {state.page.items.length} snapshots shown
                </span>
                <div>
                  <button
                    className="button button-quiet"
                    type="button"
                    disabled={pageNumber === 0}
                    onClick={() => setPageNumber((current) => Math.max(0, current - 1))}
                  >
                    Previous
                  </button>
                  <button
                    className="button button-quiet"
                    type="button"
                    disabled={!state.page.hasMore}
                    onClick={() => setPageNumber((current) => current + 1)}
                  >
                    Next
                  </button>
                </div>
              </div>
            </>
          )}
        </>
      )}
    </section>
  );
}
