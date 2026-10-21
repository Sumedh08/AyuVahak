import type { FormEvent } from "react";
import { useCallback, useEffect, useMemo, useState } from "react";
import {
  type AccessGrant,
  type AccessGrantApiClient,
  AccessGrantApiError,
  createAccessGrantApiClient,
  type GrantPrincipal,
} from "../api/access-grants";
import type { SessionProjection } from "../api/session";
import { GRANT_ROLE_TEMPLATES } from "./access-grant-roles";

interface AccessManagementWorkspaceProps {
  readonly session: SessionProjection;
  readonly getAccessToken: () => Promise<string | null>;
  readonly apiClient?: AccessGrantApiClient;
}

type GrantState =
  | { readonly status: "loading" }
  | { readonly status: "error"; readonly code: string }
  | {
      readonly status: "loaded";
      readonly grants: readonly AccessGrant[];
      readonly nextCursor: string | null;
    };

function errorCode(error: unknown): string {
  return error instanceof AccessGrantApiError ? error.code : "UNAVAILABLE";
}

function principalLabel(grant: AccessGrant): string {
  return grant.kind === "HUMAN"
    ? `${grant.issuer} · ${grant.subject}`
    : `${grant.issuer} · service ${grant.clientId}`;
}

export function AccessManagementWorkspace({
  session,
  getAccessToken,
  apiClient,
}: AccessManagementWorkspaceProps) {
  const api = useMemo(
    () => apiClient ?? createAccessGrantApiClient(getAccessToken),
    [apiClient, getAccessToken],
  );
  const templates = GRANT_ROLE_TEMPLATES;
  const [templateKey, setTemplateKey] = useState(templates[0]?.key ?? "");
  const [principalKind, setPrincipalKind] = useState<GrantPrincipal["kind"]>("HUMAN");
  const [issuer, setIssuer] = useState(session.subject.issuer);
  const [principalIdentifier, setPrincipalIdentifier] = useState("");
  const [organizationId, setOrganizationId] = useState("");
  const [facilityId, setFacilityId] = useState("");
  const [grantState, setGrantState] = useState<GrantState>({ status: "loading" });
  const [submitting, setSubmitting] = useState(false);
  const [actionMessage, setActionMessage] = useState("");
  const [actionError, setActionError] = useState("");
  const [cursor, setCursor] = useState<string | undefined>();
  const selectedTemplate = templates.find((template) => template.key === templateKey);

  const loadGrants = useCallback(
    (pageCursor?: string) => {
      const controller = new AbortController();
      setGrantState({ status: "loading" });
      void api
        .list(pageCursor, controller.signal)
        .then((page) => {
          setCursor(pageCursor);
          setGrantState({ status: "loaded", grants: page.items, nextCursor: page.nextCursor });
        })
        .catch((error: unknown) => {
          if (!controller.signal.aborted)
            setGrantState({ status: "error", code: errorCode(error) });
        });
      return () => controller.abort();
    },
    [api],
  );

  useEffect(() => loadGrants(), [loadGrants]);

  function targetScope():
    | { readonly kind: "PLATFORM" }
    | { readonly kind: "ORGANIZATION"; readonly organizationId: string }
    | { readonly kind: "FACILITY"; readonly organizationId: string; readonly facilityId: string }
    | null {
    if (!selectedTemplate) return null;
    if (selectedTemplate.scope === "PLATFORM") return { kind: "PLATFORM" };
    if (
      !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu.test(
        organizationId,
      )
    )
      return null;
    if (selectedTemplate.scope === "ORGANIZATION") {
      return { kind: "ORGANIZATION", organizationId };
    }
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu.test(facilityId))
      return null;
    return { kind: "FACILITY", organizationId, facilityId };
  }

  async function createTemplateGrants(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (!selectedTemplate || submitting) return;
    const scope = targetScope();
    if (!scope || !issuer.trim() || !principalIdentifier.trim()) {
      setActionError("Choose a valid template scope and identity-provider principal.");
      setActionMessage("");
      return;
    }
    const principal: GrantPrincipal =
      principalKind === "HUMAN"
        ? { kind: "HUMAN", issuer: issuer.trim(), subject: principalIdentifier.trim() }
        : { kind: "SERVICE", issuer: issuer.trim(), clientId: principalIdentifier.trim() };
    if (
      principal.kind === "HUMAN" &&
      principal.issuer === session.subject.issuer &&
      principal.subject === session.subject.identifier
    ) {
      setActionError("The current operator cannot grant or change their own access.");
      return;
    }
    setSubmitting(true);
    setActionError("");
    setActionMessage("");
    const created: string[] = [];
    try {
      for (const authority of selectedTemplate.authorities) {
        await api.create(principal, authority, scope);
        created.push(authority);
      }
      setActionMessage(
        `Created ${created.length} separate grants from “${selectedTemplate.label}”.`,
      );
      setPrincipalIdentifier("");
      loadGrants();
    } catch (error: unknown) {
      const code = errorCode(error);
      setActionError(
        created.length > 0
          ? `${created.length} grant(s) were created before ${code}. Review the grant list; no rollback is performed.`
          : code === "CONFLICT"
            ? "An active matching grant already exists. No duplicate grant was created."
            : code === "FORBIDDEN"
              ? "The server denied grant administration. No further grant was attempted."
              : `Grant setup stopped at ${code}. Review the current grant list before retrying.`,
      );
      if (created.length > 0) loadGrants();
    } finally {
      setSubmitting(false);
    }
  }

  async function revoke(grant: AccessGrant): Promise<void> {
    if (grant.status !== "ACTIVE") return;
    if (
      grant.kind === session.subject.kind &&
      grant.issuer === session.subject.issuer &&
      grant.subject === session.subject.identifier
    ) {
      setActionError("The current operator cannot revoke their own grant.");
      return;
    }
    if (!window.confirm(`Revoke ${grant.authority} for ${principalLabel(grant)}?`)) return;
    setSubmitting(true);
    setActionError("");
    setActionMessage("");
    try {
      await api.revoke(grant.grantId, "ACCESS_REMOVAL");
      setActionMessage(`Revoked ${grant.authority}.`);
      loadGrants(cursor);
    } catch (error: unknown) {
      setActionError(
        `The grant was not confirmed as revoked (${errorCode(error)}). Refresh the list before retrying.`,
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="workspace-panel" aria-labelledby="access-title">
      <div className="workspace-intro">
        <p className="eyebrow">IDENTITY AND ACCESS OWNER</p>
        <h2 id="access-title">Access management</h2>
        <p>
          AyuVahak manages authorization grants and scopes. User accounts and authentication remain
          with the configured identity provider.
        </p>
      </div>
      <div className="workspace-state" role="note">
        <strong>Backend authority remains in force.</strong>
        <p>
          Templates below expand into separate existing grants. The server validates each authority
          and target scope. A failed template can leave an explicitly reported partial set; no
          automatic rollback is attempted.
        </p>
      </div>
      <section className="workspace-subsection" aria-labelledby="grant-template-title">
        <h3 id="grant-template-title">Grant a role template</h3>
        <form className="query-form" onSubmit={(event) => void createTemplateGrants(event)}>
          <label className="field-label" htmlFor="grant-template">
            Access preset
            <select
              id="grant-template"
              value={templateKey}
              onChange={(event) => setTemplateKey(event.currentTarget.value)}
            >
              {templates.map((template) => (
                <option key={template.key} value={template.key}>
                  {template.label}
                </option>
              ))}
            </select>
          </label>
          {selectedTemplate && (
            <div className="workspace-state">
              <p>{selectedTemplate.description}</p>
              <p>
                <strong>Authorities:</strong> {selectedTemplate.authorities.join(", ")}
              </p>
            </div>
          )}
          <label className="field-label" htmlFor="grant-principal-kind">
            Principal type
            <select
              id="grant-principal-kind"
              value={principalKind}
              onChange={(event) =>
                setPrincipalKind(event.currentTarget.value as GrantPrincipal["kind"])
              }
            >
              <option value="HUMAN">Human identity from the IdP</option>
              <option value="SERVICE">Service client from the IdP</option>
            </select>
          </label>
          <label className="field-label" htmlFor="grant-issuer">
            Identity issuer
            <input
              id="grant-issuer"
              value={issuer}
              onChange={(event) => setIssuer(event.currentTarget.value)}
              maxLength={256}
              required
              autoComplete="off"
            />
          </label>
          <label className="field-label" htmlFor="grant-principal-identifier">
            {principalKind === "HUMAN" ? "IdP subject" : "IdP client ID"}
            <input
              id="grant-principal-identifier"
              value={principalIdentifier}
              onChange={(event) => setPrincipalIdentifier(event.currentTarget.value)}
              maxLength={256}
              required
              autoComplete="off"
            />
          </label>
          {selectedTemplate?.scope !== "PLATFORM" && (
            <label className="field-label" htmlFor="grant-organization-id">
              Organization ID
              <input
                id="grant-organization-id"
                value={organizationId}
                onChange={(event) => setOrganizationId(event.currentTarget.value)}
                maxLength={36}
                required
                autoComplete="off"
                placeholder="Directory organization UUID"
              />
            </label>
          )}
          {selectedTemplate?.scope === "FACILITY" && (
            <label className="field-label" htmlFor="grant-facility-id">
              Facility ID
              <input
                id="grant-facility-id"
                value={facilityId}
                onChange={(event) => setFacilityId(event.currentTarget.value)}
                maxLength={36}
                required
                autoComplete="off"
                placeholder="Directory facility UUID"
              />
            </label>
          )}
          <button
            className="button button-primary"
            type="submit"
            disabled={submitting || !selectedTemplate}
          >
            {submitting ? "Applying grants…" : "Apply template grants"}
          </button>
        </form>
        {actionMessage && (
          <p className="workspace-state" role="status">
            {actionMessage}
          </p>
        )}
        {actionError && (
          <p className="workspace-state workspace-error" role="alert">
            {actionError}
          </p>
        )}
      </section>
      <section className="workspace-subsection" aria-labelledby="current-grants-title">
        <div className="result-heading">
          <h3 id="current-grants-title">Current platform grants</h3>
          <button
            className="button button-quiet"
            type="button"
            onClick={() => loadGrants(cursor)}
            disabled={submitting || grantState.status === "loading"}
          >
            Refresh
          </button>
        </div>
        {grantState.status === "loading" ? (
          <p className="workspace-state" role="status" aria-busy="true">
            Loading access grants…
          </p>
        ) : grantState.status === "error" ? (
          <div className="workspace-state workspace-error" role="alert">
            <h4>
              {grantState.code === "FORBIDDEN"
                ? "Grant administration was denied"
                : "Access grants are unavailable"}
            </h4>
            <p>The grant owner did not return a usable list. No access state is assumed.</p>
          </div>
        ) : grantState.grants.length === 0 ? (
          <p className="workspace-state" role="status">
            No grants were returned for this page.
          </p>
        ) : (
          <div className="table-scroll">
            <table>
              <caption>Platform grant records returned by Identity and Access</caption>
              <thead>
                <tr>
                  <th scope="col">Principal</th>
                  <th scope="col">Authority</th>
                  <th scope="col">Scope</th>
                  <th scope="col">Status</th>
                  <th scope="col">Granted</th>
                  <th scope="col">Action</th>
                </tr>
              </thead>
              <tbody>
                {grantState.grants.map((grant) => (
                  <tr key={grant.grantId}>
                    <th scope="row">
                      {principalLabel(grant)}
                      <small className="facility-id-label">{grant.grantId}</small>
                    </th>
                    <td>{grant.authority}</td>
                    <td>
                      {grant.scope}
                      {grant.organizationIdentifier ? ` · ${grant.organizationIdentifier}` : ""}
                      {grant.facilityIdentifier ? ` · ${grant.facilityIdentifier}` : ""}
                    </td>
                    <td>
                      <span className="state-tag">{grant.status}</span>
                    </td>
                    <td>
                      <time dateTime={grant.createdAt}>
                        {new Date(grant.createdAt).toLocaleString()}
                      </time>
                    </td>
                    <td>
                      {grant.status === "ACTIVE" ? (
                        <button
                          className="button button-quiet"
                          type="button"
                          onClick={() => void revoke(grant)}
                          disabled={submitting}
                        >
                          Revoke
                        </button>
                      ) : (
                        "—"
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {grantState.status === "loaded" && grantState.nextCursor && (
          <button
            className="button button-quiet"
            type="button"
            onClick={() => loadGrants(grantState.nextCursor ?? undefined)}
            disabled={submitting}
          >
            Load next page
          </button>
        )}
      </section>
    </section>
  );
}
