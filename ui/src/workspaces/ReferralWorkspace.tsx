import type { FormEvent } from "react";
import { useEffect, useMemo, useRef, useState } from "react";
import { type OwnerScope, originScopesForAuthority } from "../api/owner";
import {
  createReferralMatchingApiClient,
  type ReferralDetail,
  type ReferralMatchingApiClient,
  ReferralMatchingApiError,
  type ReferralPage,
} from "../api/referral-matching";
import type { SessionProjection } from "../api/session";

interface ReferralWorkspaceProps {
  readonly session: SessionProjection;
  readonly getAccessToken: () => Promise<string | null>;
  readonly apiClient?: ReferralMatchingApiClient;
}

type ListState =
  | { readonly status: "loading" }
  | { readonly status: "error"; readonly code: string }
  | { readonly status: "loaded"; readonly page: ReferralPage };

type DetailState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | { readonly status: "error"; readonly code: string }
  | { readonly status: "loaded"; readonly referral: ReferralDetail };

function codeFrom(error: unknown): string {
  return error instanceof ReferralMatchingApiError ? error.code : "UNAVAILABLE";
}

function scopeLabel(scope: Extract<OwnerScope, { kind: "ORGANIZATION" | "FACILITY" }>): string {
  return scope.kind === "ORGANIZATION"
    ? `Organization ${scope.organizationId}`
    : `Facility ${scope.facilityId}`;
}

export function ReferralWorkspace({ session, getAccessToken, apiClient }: ReferralWorkspaceProps) {
  const readScopes = useMemo(() => originScopesForAuthority(session, "ReferralRead"), [session]);
  const createScopes = useMemo(
    () => originScopesForAuthority(session, "ReferralCreate"),
    [session],
  );
  const scopes = useMemo<
    readonly Extract<OwnerScope, { kind: "ORGANIZATION" | "FACILITY" }>[]
  >(() => {
    const unique = new Map<string, Extract<OwnerScope, { kind: "ORGANIZATION" | "FACILITY" }>>();
    for (const scope of [...readScopes, ...createScopes]) unique.set(scope.key, scope);
    return [...unique.values()];
  }, [createScopes, readScopes]);
  const api = useMemo(
    () => apiClient ?? createReferralMatchingApiClient(getAccessToken),
    [apiClient, getAccessToken],
  );
  const [scopeKey, setScopeKey] = useState(scopes[0]?.key ?? "");
  const [pageNumber, setPageNumber] = useState(0);
  const [reloadNumber, setReloadNumber] = useState(0);
  const [listState, setListState] = useState<ListState>({ status: "loading" });
  const [selectedId, setSelectedId] = useState("");
  const [detailState, setDetailState] = useState<DetailState>({ status: "idle" });
  const [createScopeKey, setCreateScopeKey] = useState(createScopes[0]?.key ?? "");
  const [created, setCreated] = useState(false);
  const [createError, setCreateError] = useState("");
  const [creating, setCreating] = useState(false);
  const createInFlight = useRef(false);
  const detailController = useRef<AbortController | null>(null);
  const selectedScope = scopes.find((scope) => scope.key === scopeKey);
  const selectedReadScope = readScopes.find((scope) => scope.key === scopeKey);

  useEffect(() => {
    // This counter is an explicit revalidation trigger for the owner read below.
    void reloadNumber;
    if (!selectedReadScope) {
      setListState({ status: "loaded", page: { items: [], page: 0, size: 25, hasMore: false } });
      return;
    }
    const controller = new AbortController();
    let active = true;
    setListState({ status: "loading" });
    void api
      .listReferrals(selectedReadScope, pageNumber, undefined, controller.signal)
      .then((page) => {
        if (active) setListState({ status: "loaded", page });
      })
      .catch((error: unknown) => {
        if (active && !controller.signal.aborted)
          setListState({ status: "error", code: codeFrom(error) });
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [api, pageNumber, reloadNumber, selectedReadScope]);

  async function loadDetail(id: string): Promise<void> {
    if (!selectedReadScope) return;
    detailController.current?.abort();
    const controller = new AbortController();
    detailController.current = controller;
    setSelectedId(id);
    setDetailState({ status: "loading" });
    try {
      const referral = await api.getReferral(selectedReadScope, id, controller.signal);
      if (!controller.signal.aborted) setDetailState({ status: "loaded", referral });
    } catch (error: unknown) {
      if (!controller.signal.aborted) setDetailState({ status: "error", code: codeFrom(error) });
    }
  }

  async function create(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (createInFlight.current) return;
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const createScope = createScopes.find((scope) => scope.key === createScopeKey);
    const capability = String(form.get("capability") ?? "").trim();
    const issuer = String(form.get("patient-issuer") ?? "").trim();
    const resourceType = String(form.get("patient-resource-type") ?? "").trim();
    const identifier = String(form.get("patient-identifier") ?? "").trim();
    if (!createScope || !capability || !issuer || !resourceType || !identifier) return;
    createInFlight.current = true;
    setCreating(true);
    setCreated(false);
    setCreateError("");
    try {
      await api.createReferral(createScope, {
        patientReference: { issuer, resourceType, identifier },
        careRequirement: {
          requiredCareCapabilityCodes: [capability],
          requestedStart: null,
          requestedEnd: null,
          permittedGeographyCodes: [],
          selectedDestinationOrganizationId: null,
          priorityCode: null,
        },
      });
      setCreated(true);
      setReloadNumber((value) => value + 1);
      formElement.reset();
    } catch (error: unknown) {
      setCreateError(codeFrom(error));
    } finally {
      createInFlight.current = false;
      setCreating(false);
    }
  }

  return (
    <section className="workspace-panel" aria-labelledby="referrals-title">
      <div className="workspace-intro">
        <p className="eyebrow">REFERRAL OWNER DATA</p>
        <h2 id="referrals-title">Referrals</h2>
        <p>
          Referral records remain with the Referral service. This workspace does not display
          PatientReference or clinical context. Matching recommendations are a separate
          deterministic workspace.
        </p>
      </div>
      {scopes.length > 1 && (
        <label className="field-label" htmlFor="referral-scope">
          Authorized origin scope
          <select
            id="referral-scope"
            value={scopeKey}
            onChange={(event) => {
              setScopeKey(event.currentTarget.value);
              setPageNumber(0);
              setSelectedId("");
              detailController.current?.abort();
              setDetailState({ status: "idle" });
            }}
          >
            {scopes.map((scope) => (
              <option key={scope.key} value={scope.key}>
                {scopeLabel(scope)}
              </option>
            ))}
          </select>
        </label>
      )}

      {selectedReadScope ? (
        <section aria-labelledby="referral-list-title" className="workspace-subsection">
          <h3 id="referral-list-title">Referral records</h3>
          {listState.status === "loading" ? (
            <div className="workspace-state" role="status" aria-busy="true">
              Loading Referral records…
            </div>
          ) : listState.status === "error" ? (
            <div className="workspace-state workspace-error" role="alert">
              <h4>
                {listState.code === "FORBIDDEN"
                  ? "Referral access was denied"
                  : listState.code === "UNAUTHENTICATED"
                    ? "Sign in again to continue"
                    : "Referral records are unavailable"}
              </h4>
              <p>No Referral records are assumed or displayed.</p>
              <button
                className="button button-quiet"
                type="button"
                onClick={() => setReloadNumber((value) => value + 1)}
              >
                Retry
              </button>
            </div>
          ) : listState.page.items.length === 0 ? (
            <div className="workspace-state" role="status">
              <h4>No Referrals were returned</h4>
              <p>The owner returned an empty page for this scope.</p>
            </div>
          ) : (
            <>
              <div className="table-scroll">
                <table>
                  <caption>Referrals returned by the owner for the selected scope</caption>
                  <thead>
                    <tr>
                      <th scope="col">Referral</th>
                      <th scope="col">Status</th>
                      <th scope="col">Version</th>
                      <th scope="col">Origin facility</th>
                      <th scope="col">Relevant time</th>
                    </tr>
                  </thead>
                  <tbody>
                    {listState.page.items.map((referral) => (
                      <tr key={referral.id}>
                        <th scope="row">
                          <button
                            className="text-button"
                            type="button"
                            onClick={() => void loadDetail(referral.id)}
                            aria-pressed={selectedId === referral.id}
                          >
                            Open Referral
                          </button>
                          <small className="facility-id-label">{referral.id}</small>
                        </th>
                        <td>{referral.status}</td>
                        <td>{referral.version}</td>
                        <td>{referral.origin.facilityId ?? "Organization scope"}</td>
                        <td>
                          <time dateTime={referral.relevantAt}>
                            {new Date(referral.relevantAt).toLocaleString()}
                          </time>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <nav className="pager" aria-label="Referral result pages">
                <span>
                  Page {listState.page.page + 1} · {listState.page.items.length} Referrals
                </span>
                <div>
                  <button
                    className="button button-quiet"
                    type="button"
                    disabled={pageNumber === 0}
                    onClick={() => setPageNumber((value) => Math.max(0, value - 1))}
                  >
                    Previous
                  </button>
                  <button
                    className="button button-quiet"
                    type="button"
                    disabled={!listState.page.hasMore}
                    onClick={() => setPageNumber((value) => value + 1)}
                  >
                    Next
                  </button>
                </div>
              </nav>
            </>
          )}
          {detailState.status === "loading" && (
            <div className="workspace-state" role="status" aria-busy="true">
              Loading Referral detail…
            </div>
          )}
          {detailState.status === "error" && (
            <div className="workspace-state workspace-error" role="alert">
              <h4>
                {detailState.code === "FORBIDDEN"
                  ? "Referral detail access was denied"
                  : "Referral detail is unavailable"}
              </h4>
              <p>No detail is assumed.</p>
            </div>
          )}
          {detailState.status === "loaded" && (
            <ReferralDetailView referral={detailState.referral} />
          )}
        </section>
      ) : (
        <div className="workspace-state" role="status">
          <h3>Referral read scope is not available</h3>
          <p>
            This session cannot list or open Referral records. The server remains the authorization
            authority.
          </p>
        </div>
      )}

      {createScopes.length > 0 && (
        <section aria-labelledby="create-referral-title" className="workspace-subsection">
          <h3 id="create-referral-title">Create a Referral</h3>
          <p>
            Creating a Referral sends the PatientReference directly to the authorized Referral API.
            It is not saved in this browser.
          </p>
          <form className="query-form" onSubmit={(event) => void create(event)}>
            {createScopes.length > 1 && (
              <label className="field-label" htmlFor="referral-create-scope">
                Create within scope
                <select
                  id="referral-create-scope"
                  value={createScopeKey}
                  onChange={(event) => setCreateScopeKey(event.currentTarget.value)}
                >
                  {createScopes.map((scope) => (
                    <option key={scope.key} value={scope.key}>
                      {scopeLabel(scope)}
                    </option>
                  ))}
                </select>
              </label>
            )}
            <label className="field-label" htmlFor="patient-issuer">
              PatientReference issuer
              <input
                id="patient-issuer"
                name="patient-issuer"
                required
                maxLength={256}
                autoComplete="off"
              />
            </label>
            <label className="field-label" htmlFor="patient-resource-type">
              PatientReference resource type
              <input
                id="patient-resource-type"
                name="patient-resource-type"
                required
                maxLength={64}
                autoComplete="off"
              />
            </label>
            <label className="field-label" htmlFor="patient-identifier">
              PatientReference identifier
              <input
                id="patient-identifier"
                name="patient-identifier"
                required
                maxLength={256}
                autoComplete="off"
              />
            </label>
            <label className="field-label" htmlFor="referral-capability">
              Required care capability code
              <input
                id="referral-capability"
                name="capability"
                required
                maxLength={64}
                pattern="[A-Z][A-Z0-9._-]{0,63}"
                placeholder="Example: SYN_CARDIOLOGY"
              />
            </label>
            <button className="button button-primary" type="submit" disabled={creating}>
              {creating ? "Creating Referral draft…" : "Create Referral draft"}
            </button>
          </form>
          {created && (
            <p className="workspace-state" role="status">
              Referral draft created. The owner list is being refreshed.
            </p>
          )}
          {createError && (
            <div className="workspace-state workspace-error" role="alert">
              <h4>
                {createError === "FORBIDDEN"
                  ? "Referral creation was denied"
                  : createError === "UNAUTHENTICATED"
                    ? "Sign in again to continue"
                    : "Referral could not be created"}
              </h4>
              <p>No Referral was assumed to have been created. The form values are not logged.</p>
            </div>
          )}
        </section>
      )}
      {selectedScope && (
        <p className="field-help">
          Scope selection is a presentation aid; each request is independently authorized by the
          Referral service.
        </p>
      )}
    </section>
  );
}

function ReferralDetailView({ referral }: { readonly referral: ReferralDetail }) {
  return (
    <article className="capacity-result" aria-labelledby="referral-detail-title">
      <p className="eyebrow">REFERRAL DETAIL</p>
      <h3 id="referral-detail-title">
        {referral.status} · version {referral.version}
      </h3>
      <dl className="result-facts">
        <div>
          <dt>Referral</dt>
          <dd>{referral.id}</dd>
        </div>
        <div>
          <dt>Origin organization</dt>
          <dd>{referral.origin.organizationId}</dd>
        </div>
        <div>
          <dt>Origin facility</dt>
          <dd>{referral.origin.facilityId ?? "Not specified"}</dd>
        </div>
        <div>
          <dt>Required capabilities</dt>
          <dd>{referral.careRequirement.requiredCareCapabilityCodes.join(", ")}</dd>
        </div>
        <div>
          <dt>Submitted revision</dt>
          <dd>{referral.submittedRevisionNumber ?? "Not submitted"}</dd>
        </div>
      </dl>
      <p className="field-help">
        PatientReference and clinical context are intentionally not displayed in this workspace.
      </p>
    </article>
  );
}
