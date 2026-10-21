import { useEffect, useMemo, useState } from "react";
import {
  createOwnerApiClient,
  directoryScopes,
  type OwnerApiClient,
  OwnerApiError,
  type OwnerScope,
} from "../api/owner";
import type { SessionProjection } from "../api/session";

interface DirectoryWorkspaceProps {
  readonly session: SessionProjection;
  readonly getAccessToken: () => Promise<string | null>;
  readonly apiClient?: OwnerApiClient;
}

type LoadState =
  | { readonly status: "loading" }
  | { readonly status: "error"; readonly code: string }
  | {
      readonly status: "loaded";
      readonly page: Awaited<ReturnType<OwnerApiClient["loadDirectoryFacilities"]>>;
    };

function errorCode(error: unknown): string {
  if (error instanceof OwnerApiError) return error.code;
  return "UNAVAILABLE";
}

function scopeLabel(scope: OwnerScope): string {
  if (scope.kind === "PLATFORM") return "All authorized Directory scope";
  if (scope.kind === "ORGANIZATION") return `Organization ${scope.organizationId}`;
  return `Facility ${scope.facilityId}`;
}

export function DirectoryWorkspace({
  session,
  getAccessToken,
  apiClient,
}: DirectoryWorkspaceProps) {
  const scopes = useMemo(() => directoryScopes(session), [session]);
  const api = useMemo(
    () => apiClient ?? createOwnerApiClient(getAccessToken),
    [apiClient, getAccessToken],
  );
  const [scopeKey, setScopeKey] = useState(scopes[0]?.key ?? "");
  const [pagination, setPagination] = useState({ page: 0 });
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const selectedScope = scopes.find((scope) => scope.key === scopeKey);

  useEffect(() => {
    if (!selectedScope) {
      setState({
        status: "loaded",
        page: {
          mode: "DIRECTORY_FILTERED",
          facilities: [],
          unlocated: [],
          page: 0,
          size: 25,
          hasMore: false,
          hasMoreUnlocated: false,
        },
      });
      return;
    }
    const controller = new AbortController();
    let active = true;
    setState({ status: "loading" });
    void api
      .loadDirectoryFacilities(selectedScope, pagination.page, 25, controller.signal)
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
  }, [api, pagination, selectedScope]);

  function changeScope(value: string): void {
    setScopeKey(value);
    setPagination({ page: 0 });
  }

  return (
    <section className="workspace-panel" aria-labelledby="directory-title">
      <div className="workspace-intro">
        <p className="eyebrow">DIRECTORY OWNER DATA</p>
        <h2 id="directory-title">Facilities in your Directory scope</h2>
        <p>
          Facility results are loaded from the authorized Directory API. Names are not searched by
          this endpoint.
        </p>
      </div>
      {scopes.length === 0 ? (
        <div className="workspace-state" role="status">
          <h3>Directory scope is not available</h3>
          <p>
            No recognized Directory scope is present in this session. Ask an administrator to review
            access grants.
          </p>
        </div>
      ) : (
        <>
          {scopes.length > 1 && (
            <label className="field-label" htmlFor="directory-scope">
              Authorized scope
              <select
                id="directory-scope"
                value={scopeKey}
                onChange={(event) => changeScope(event.currentTarget.value)}
              >
                {scopes.map((scope) => (
                  <option key={scope.key} value={scope.key}>
                    {scopeLabel(scope)}
                  </option>
                ))}
              </select>
            </label>
          )}
          {state.status === "loading" ? (
            <div className="workspace-state" role="status" aria-busy="true">
              Loading Directory facilities…
            </div>
          ) : state.status === "error" ? (
            <div className="workspace-state workspace-error" role="alert">
              <h3>
                {state.code === "FORBIDDEN"
                  ? "Directory access was denied"
                  : state.code === "UNAUTHENTICATED"
                    ? "Sign in again to continue"
                    : "Directory information is unavailable"}
              </h3>
              <p>
                {state.code === "FORBIDDEN"
                  ? "The Directory owner rejected this scoped request. No records are shown."
                  : "The Directory owner did not return a usable response. No records are assumed."}
              </p>
              <button
                className="button button-quiet"
                type="button"
                onClick={() => setPagination((current) => ({ ...current }))}
              >
                Retry
              </button>
            </div>
          ) : state.page.facilities.length === 0 ? (
            <div className="workspace-state" role="status">
              <h3>No facilities were returned</h3>
              <p>The Directory returned an empty page for this authorized scope.</p>
            </div>
          ) : (
            <>
              <div className="table-scroll">
                <table>
                  <caption>
                    Facilities returned by the Directory owner for the selected scope
                  </caption>
                  <thead>
                    <tr>
                      <th scope="col">Facility</th>
                      <th scope="col">Organization ID</th>
                      <th scope="col">Status</th>
                      <th scope="col">Version</th>
                    </tr>
                  </thead>
                  <tbody>
                    {state.page.facilities.map((facility) => (
                      <tr key={facility.facilityId}>
                        <th scope="row">
                          <span>{facility.facilityName}</span>
                          <small className="facility-id-label">{facility.facilityId}</small>
                        </th>
                        <td>{facility.managingOrganizationId}</td>
                        <td>{facility.status}</td>
                        <td>{facility.version}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <nav className="pager" aria-label="Directory result pages">
                <span>
                  Page {state.page.page + 1} · {state.page.facilities.length} facilities
                </span>
                <div>
                  <button
                    className="button button-quiet"
                    type="button"
                    disabled={pagination.page === 0}
                    onClick={() =>
                      setPagination((current) => ({ page: Math.max(0, current.page - 1) }))
                    }
                  >
                    Previous
                  </button>
                  <button
                    className="button button-quiet"
                    type="button"
                    disabled={!state.page.hasMore}
                    onClick={() => setPagination((current) => ({ page: current.page + 1 }))}
                  >
                    Next
                  </button>
                </div>
              </nav>
            </>
          )}
        </>
      )}
    </section>
  );
}
