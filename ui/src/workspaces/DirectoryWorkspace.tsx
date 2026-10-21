import type { FormEvent } from "react";
import { useEffect, useMemo, useState } from "react";
import {
  createOwnerApiClient,
  directoryCurationScopes,
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
  const curationScopes = useMemo(() => directoryCurationScopes(session), [session]);
  const setupCurationScopes = useMemo(
    () =>
      curationScopes.filter((scope) => scope.kind === "PLATFORM" || scope.kind === "ORGANIZATION"),
    [curationScopes],
  );
  const api = useMemo(
    () => apiClient ?? createOwnerApiClient(getAccessToken),
    [apiClient, getAccessToken],
  );
  const [scopeKey, setScopeKey] = useState(scopes[0]?.key ?? "");
  const [pagination, setPagination] = useState({ page: 0 });
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [organizationName, setOrganizationName] = useState("");
  const [facilityName, setFacilityName] = useState("");
  const [facilityOrganizationId, setFacilityOrganizationId] = useState("");
  const [facilityScopeKey, setFacilityScopeKey] = useState(setupCurationScopes[0]?.key ?? "");
  const [organizationCreate, setOrganizationCreate] = useState<OrganizationCreateState>({
    status: "idle",
  });
  const [facilityCreate, setFacilityCreate] = useState<FacilityCreateState>({ status: "idle" });
  const selectedScope = scopes.find((scope) => scope.key === scopeKey);
  const selectedFacilityCurationScope =
    setupCurationScopes.find((scope) => scope.key === facilityScopeKey) ?? setupCurationScopes[0];
  const platformCurationScope = curationScopes.find((scope) => scope.kind === "PLATFORM");

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

  function isAllowedName(name: string): boolean {
    if (name.trim() !== name || name.length < 1 || name.length > 256) return false;
    return session.deploymentMode !== "SYNTHETIC_DEMO" || /^SYN-[A-Z0-9-]{1,60}$/.test(name);
  }

  async function runOrganizationCreate(command: CreateCommand): Promise<void> {
    setOrganizationCreate({ status: "submitting", command });
    try {
      const id = await api.createOrganization(
        { key: "PLATFORM", kind: "PLATFORM" },
        command.name,
        command.idempotencyKey,
        command.observedAt,
      );
      setOrganizationCreate({ status: "created", id });
      setOrganizationName("");
    } catch (error: unknown) {
      setOrganizationCreate(
        errorCode(error) === "OUTCOME_UNKNOWN"
          ? { status: "unknown", command }
          : { status: "failed", code: errorCode(error) },
      );
    }
  }

  async function runFacilityCreate(command: FacilityCreateCommand): Promise<void> {
    setFacilityCreate({ status: "submitting", command });
    try {
      const id = await api.createFacility(
        command.scope,
        command.organizationId,
        command.name,
        command.idempotencyKey,
        command.observedAt,
      );
      setFacilityCreate({ status: "created", id });
      setFacilityName("");
      setPagination((current) => ({ ...current }));
    } catch (error: unknown) {
      setFacilityCreate(
        errorCode(error) === "OUTCOME_UNKNOWN"
          ? { status: "unknown", command }
          : { status: "failed", code: errorCode(error) },
      );
    }
  }

  function submitOrganization(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault();
    if (!platformCurationScope || !isAllowedName(organizationName)) {
      setOrganizationCreate({ status: "failed", code: "INVALID_REQUEST" });
      return;
    }
    void runOrganizationCreate({
      name: organizationName,
      idempotencyKey: crypto.randomUUID(),
      observedAt: new Date().toISOString(),
    });
  }

  function submitFacility(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault();
    if (
      !selectedFacilityCurationScope ||
      !isAllowedName(facilityName) ||
      (selectedFacilityCurationScope.kind !== "PLATFORM" &&
        selectedFacilityCurationScope.kind !== "ORGANIZATION")
    ) {
      setFacilityCreate({ status: "failed", code: "INVALID_REQUEST" });
      return;
    }
    const organizationId =
      selectedFacilityCurationScope.kind === "ORGANIZATION"
        ? selectedFacilityCurationScope.organizationId
        : facilityOrganizationId;
    void runFacilityCreate({
      scope: selectedFacilityCurationScope,
      organizationId,
      name: facilityName,
      idempotencyKey: crypto.randomUUID(),
      observedAt: new Date().toISOString(),
    });
  }

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
      {setupCurationScopes.length > 0 && (
        <section className="workspace-subsection" aria-labelledby="directory-setup-title">
          <h3 id="directory-setup-title">Directory setup</h3>
          <p className="field-help">
            Create operations are sent to the Directory owner with a stable idempotency key. The
            backend checks grants and scope again.{" "}
            {session.deploymentMode === "SYNTHETIC_DEMO"
              ? "Demo names must use fictional SYN-* identifiers."
              : "Enter organization and facility names approved for this deployment."}
          </p>
          {platformCurationScope && (
            <form className="query-form" onSubmit={submitOrganization}>
              <h4>Create an organization</h4>
              <label className="field-label" htmlFor="directory-new-organization">
                Organization name
                <input
                  id="directory-new-organization"
                  value={organizationName}
                  onChange={(event) => setOrganizationName(event.currentTarget.value)}
                  maxLength={256}
                  pattern={
                    session.deploymentMode === "SYNTHETIC_DEMO" ? "SYN-[A-Z0-9-]{1,60}" : undefined
                  }
                  required
                  disabled={
                    organizationCreate.status === "submitting" ||
                    organizationCreate.status === "unknown"
                  }
                  autoComplete="off"
                />
              </label>
              <button
                className="button button-primary"
                type="submit"
                disabled={
                  organizationCreate.status === "submitting" ||
                  organizationCreate.status === "unknown"
                }
              >
                {organizationCreate.status === "submitting"
                  ? "Creating organization…"
                  : "Create organization"}
              </button>
              {organizationCreate.status === "unknown" && (
                <div className="workspace-state workspace-error" role="alert">
                  <p>
                    The result is unknown. Retry the exact same request to recover its owner
                    receipt.
                  </p>
                  <button
                    className="button button-quiet"
                    type="button"
                    onClick={() => void runOrganizationCreate(organizationCreate.command)}
                  >
                    Retry same organization request
                  </button>
                </div>
              )}
              {organizationCreate.status === "created" && (
                <p className="workspace-state" role="status">
                  Organization created: {organizationCreate.id}
                </p>
              )}
              {organizationCreate.status === "failed" && (
                <p className="workspace-state workspace-error" role="alert">
                  Organization creation was not confirmed ({organizationCreate.code}). Check
                  Directory before retrying.
                </p>
              )}
            </form>
          )}
          {setupCurationScopes.length > 0 && (
            <form className="query-form" onSubmit={submitFacility}>
              <h4>Create a facility</h4>
              {setupCurationScopes.length > 1 && (
                <label className="field-label" htmlFor="directory-facility-scope">
                  Curation scope
                  <select
                    id="directory-facility-scope"
                    value={selectedFacilityCurationScope?.key ?? ""}
                    onChange={(event) => setFacilityScopeKey(event.currentTarget.value)}
                    disabled={
                      facilityCreate.status === "submitting" || facilityCreate.status === "unknown"
                    }
                  >
                    {setupCurationScopes.map((scope) => (
                      <option key={scope.key} value={scope.key}>
                        {scopeLabel(scope)}
                      </option>
                    ))}
                  </select>
                </label>
              )}
              {selectedFacilityCurationScope?.kind === "PLATFORM" && (
                <label className="field-label" htmlFor="directory-facility-organization">
                  Managing organization ID
                  <input
                    id="directory-facility-organization"
                    value={facilityOrganizationId}
                    onChange={(event) => setFacilityOrganizationId(event.currentTarget.value)}
                    maxLength={36}
                    pattern="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
                    required
                    disabled={
                      facilityCreate.status === "submitting" || facilityCreate.status === "unknown"
                    }
                    autoComplete="off"
                  />
                </label>
              )}
              <label className="field-label" htmlFor="directory-new-facility">
                Facility name
                <input
                  id="directory-new-facility"
                  value={facilityName}
                  onChange={(event) => setFacilityName(event.currentTarget.value)}
                  maxLength={256}
                  pattern={
                    session.deploymentMode === "SYNTHETIC_DEMO" ? "SYN-[A-Z0-9-]{1,60}" : undefined
                  }
                  required
                  disabled={
                    facilityCreate.status === "submitting" || facilityCreate.status === "unknown"
                  }
                  autoComplete="off"
                />
              </label>
              <button
                className="button button-primary"
                type="submit"
                disabled={
                  facilityCreate.status === "submitting" || facilityCreate.status === "unknown"
                }
              >
                {facilityCreate.status === "submitting" ? "Creating facility…" : "Create facility"}
              </button>
              {facilityCreate.status === "unknown" && (
                <div className="workspace-state workspace-error" role="alert">
                  <p>
                    The result is unknown. Retry the exact same request to recover its owner
                    receipt.
                  </p>
                  <button
                    className="button button-quiet"
                    type="button"
                    onClick={() => void runFacilityCreate(facilityCreate.command)}
                  >
                    Retry same facility request
                  </button>
                </div>
              )}
              {facilityCreate.status === "created" && (
                <p className="workspace-state" role="status">
                  Facility created: {facilityCreate.id}
                </p>
              )}
              {facilityCreate.status === "failed" && (
                <p className="workspace-state workspace-error" role="alert">
                  Facility creation was not confirmed ({facilityCreate.code}). Check Directory
                  before retrying.
                </p>
              )}
            </form>
          )}
        </section>
      )}
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

interface CreateCommand {
  readonly name: string;
  readonly idempotencyKey: string;
  readonly observedAt: string;
}

interface FacilityCreateCommand extends CreateCommand {
  readonly scope: OwnerScope;
  readonly organizationId: string;
}

type CreateState<TCommand> =
  | { readonly status: "idle" }
  | { readonly status: "submitting"; readonly command: TCommand }
  | { readonly status: "unknown"; readonly command: TCommand }
  | { readonly status: "failed"; readonly code: string }
  | { readonly status: "created"; readonly id: string };

type OrganizationCreateState = CreateState<CreateCommand>;
type FacilityCreateState = CreateState<FacilityCreateCommand>;
