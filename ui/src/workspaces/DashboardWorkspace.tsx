import type { SessionProjection } from "../api/session";
import { WORKSPACES } from "../components/AppShell";

interface DashboardWorkspaceProps {
  readonly session: SessionProjection;
}

function hasAccess(
  session: SessionProjection,
  required: readonly string[],
  scopeKinds: readonly string[],
): boolean {
  return session.capabilities.some(
    (capability) =>
      required.includes(capability.authorityCode) &&
      capability.scopes.some((scope) => scopeKinds.includes(scope.kind)),
  );
}

export function DashboardWorkspace({ session }: DashboardWorkspaceProps) {
  const available = WORKSPACES.filter((workspace) =>
    hasAccess(session, workspace.authorities, workspace.scopeKinds),
  );

  return (
    <section className="workspace-panel" aria-labelledby="dashboard-title">
      <div className="workspace-intro">
        <p className="eyebrow">YOUR COORDINATION WORKSPACE</p>
        <h2 id="dashboard-title">Overview</h2>
        <p>
          Start with an owner workspace. Live facts are loaded from the responsible AyuVahak context
          when you open that workspace.
        </p>
      </div>
      <div className="dashboard-status-grid">
        <article className="dashboard-status-card">
          <p className="eyebrow">IDENTITY</p>
          <h3>Session verified</h3>
          <p>{session.subject.identifier}</p>
          <small>Access is checked again by each backend operation.</small>
        </article>
        <article className="dashboard-status-card">
          <p className="eyebrow">DEPLOYMENT</p>
          <h3>
            {session.deploymentMode === "SYNTHETIC_DEMO" ? "Synthetic demo" : "Empty deployment"}
          </h3>
          <p>
            {session.deploymentMode === "SYNTHETIC_DEMO"
              ? "This mode is reserved for unmistakably synthetic demonstration data and must never be used for care decisions."
              : "No demonstration business data is implied by this deployment mode."}
          </p>
        </article>
      </div>
      <section className="workspace-subsection" aria-labelledby="dashboard-workspaces-title">
        <h3 id="dashboard-workspaces-title">Available workspaces</h3>
        {available.length === 0 ? (
          <div className="workspace-state" role="status">
            {session.deploymentMode === "EMPTY" ? (
              <>
                <h4>Complete local deployment setup</h4>
                <ol>
                  <li>
                    Configure a human identity in the deployment&apos;s external identity provider.
                  </li>
                  <li>
                    Have the local operator run
                    <code>scripts/local/Initialize-AyuVahak.ps1</code> with that identity&apos;s
                    exact OIDC subject. Synthetic Demo uses its versioned manifest subject.
                  </li>
                  <li>
                    A grant administrator can assign Directory setup to a separate operator; the
                    current operator cannot grant themselves additional access.
                  </li>
                  <li>
                    Use Directory setup to create the organization and facilities, then use Access
                    management to grant only the scopes needed by each operator.
                  </li>
                </ol>
                <p>
                  An identity-provider account alone does not grant AyuVahak access. This page
                  cannot create accounts or bypass the operator-controlled bootstrap.
                </p>
              </>
            ) : (
              <>
                <h4>No operational workspace is authorized for this session</h4>
                <p>
                  Ask an administrator to review your grants and organization or facility scope.
                </p>
              </>
            )}
          </div>
        ) : (
          <ul className="dashboard-workspace-list">
            {available.map((workspace) => (
              <li key={workspace.href}>
                <a href={workspace.href}>
                  <strong>{workspace.label}</strong>
                  <span>Open the authorized {workspace.label.toLowerCase()} workspace</span>
                  <span aria-hidden="true">→</span>
                </a>
              </li>
            ))}
          </ul>
        )}
      </section>
      <p className="field-help">
        This overview does not invent counts or summarize records from other contexts. Referral,
        Transfer, Transport, Capacity and aggregate Analytics remain separate owner views.
      </p>
    </section>
  );
}
