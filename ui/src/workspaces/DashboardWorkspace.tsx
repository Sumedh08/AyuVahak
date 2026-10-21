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
              ? "Demonstration records are synthetic and must not be used for care decisions."
              : "No demonstration business data is implied by this deployment mode."}
          </p>
        </article>
      </div>
      <section className="workspace-subsection" aria-labelledby="dashboard-workspaces-title">
        <h3 id="dashboard-workspaces-title">Available workspaces</h3>
        {available.length === 0 ? (
          <div className="workspace-state" role="status">
            <h4>No operational workspace is authorized for this session</h4>
            <p>Ask an administrator to review your grants and organization or facility scope.</p>
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
