import type { MouseEvent, ReactNode } from "react";
import type { SessionProjection } from "../api/session";

interface WorkspaceDefinition {
  readonly href: string;
  readonly label: string;
  readonly authorities: readonly string[];
  readonly scopeKinds: readonly string[];
}

export const WORKSPACES: readonly WorkspaceDefinition[] = [
  {
    href: "/directory",
    label: "Directory",
    authorities: ["DirectoryRead"],
    scopeKinds: ["PLATFORM", "ORGANIZATION", "FACILITY"],
  },
  {
    href: "/capacity",
    label: "Capacity",
    authorities: ["CapacityEvidenceReview"],
    scopeKinds: ["FACILITY"],
  },
  {
    href: "/referrals",
    label: "Referrals",
    authorities: ["ReferralRead", "ReferralCreate"],
    scopeKinds: ["ORGANIZATION", "FACILITY"],
  },
  {
    href: "/matching",
    label: "Matching",
    authorities: ["RecommendationRead", "RecommendationRequest"],
    scopeKinds: ["ORGANIZATION", "FACILITY"],
  },
  {
    href: "/transfers",
    label: "Transfers",
    authorities: ["TransferViewAuthority", "TransferRequest", "TransferProgressAuthority"],
    scopeKinds: ["ORGANIZATION", "FACILITY"],
  },
  {
    href: "/receiving",
    label: "Receiving work",
    authorities: [
      "ClinicalSuitabilityAuthority",
      "OperationalReadinessAuthority",
      "AcceptanceAuthority",
    ],
    scopeKinds: ["FACILITY"],
  },
  {
    href: "/transport",
    label: "Transport",
    authorities: [
      "TransportRequestAuthority",
      "TransportDispatchCoordinationAuthority",
      "TransportStatusAuthority",
    ],
    scopeKinds: ["FACILITY"],
  },
  {
    href: "/analytics",
    label: "Analytics",
    authorities: [
      "AnalyticsAggregateComputation",
      "AnalyticsDisclosureReview",
      "AnalyticsDhis2Export",
    ],
    scopeKinds: ["ORGANIZATION", "FACILITY"],
  },
  {
    href: "/administration",
    label: "Access management",
    authorities: ["AccessGrantAdministration"],
    scopeKinds: ["PLATFORM"],
  },
];

export interface AppShellProps {
  readonly session: SessionProjection;
  readonly currentPath: string;
  readonly onSignOut: () => void;
  readonly onNavigate: (path: string) => void;
  readonly children?: ReactNode;
}

function handleInternalNavigation(
  onNavigate: (path: string) => void,
  event: MouseEvent<HTMLAnchorElement>,
): void {
  if (
    event.defaultPrevented ||
    event.button !== 0 ||
    event.metaKey ||
    event.ctrlKey ||
    event.shiftKey ||
    event.altKey
  ) {
    return;
  }

  const destination = new URL(event.currentTarget.href, window.location.origin);
  if (destination.origin !== window.location.origin) return;

  event.preventDefault();
  onNavigate(`${destination.pathname}${destination.search}${destination.hash}`);
}

function hasWorkspaceAccess(session: SessionProjection, workspace: WorkspaceDefinition): boolean {
  return session.capabilities.some(
    (capability) =>
      workspace.authorities.includes(capability.authorityCode) &&
      capability.scopes.some((scope) => workspace.scopeKinds.includes(scope.kind)),
  );
}

function workspaceForPath(path: string): WorkspaceDefinition | undefined {
  return WORKSPACES.find(
    (workspace) => path === workspace.href || path.startsWith(`${workspace.href}/`),
  );
}

function pageTitle(path: string, workspace?: WorkspaceDefinition): string {
  if (path === "/") return "Overview";
  return workspace?.label ?? "Workspace not found";
}

export function AppShell({ session, currentPath, onSignOut, onNavigate, children }: AppShellProps) {
  const activeWorkspace = workspaceForPath(currentPath);
  const canAccessActiveWorkspace =
    activeWorkspace !== undefined && hasWorkspaceAccess(session, activeWorkspace);

  return (
    <div className="app-frame">
      <a className="skip-link" href="#main-content">
        Skip to main content
      </a>
      <header className="topbar">
        <a
          className="brand"
          href="/"
          aria-label="AyuVahak home"
          onClick={(event) => handleInternalNavigation(onNavigate, event)}
        >
          <span className="brand-mark" aria-hidden="true">
            A
          </span>
          <span>
            <strong>AyuVahak</strong>
            <small>Care coordination</small>
          </span>
        </a>
        <div className="topbar-right">
          <span className="connection-state">
            <span className="status-dot" aria-hidden="true" />
            Authenticated session
          </span>
          <span className="actor-kind">
            {session.subject.kind === "HUMAN" ? "Human user" : "Service identity"}
          </span>
          <button className="button button-quiet" onClick={onSignOut} type="button">
            Sign out
          </button>
        </div>
      </header>

      <div className="layout">
        <aside className="sidebar" aria-label="Workspace navigation">
          <p className="sidebar-caption">WORKSPACES</p>
          <nav aria-label="Main navigation">
            <ul className="nav-list">
              <li>
                <a
                  className={currentPath === "/" ? "nav-link active" : "nav-link"}
                  href="/"
                  aria-current={currentPath === "/" ? "page" : undefined}
                  onClick={(event) => handleInternalNavigation(onNavigate, event)}
                >
                  <span className="nav-symbol" aria-hidden="true">
                    ⌂
                  </span>
                  Overview
                </a>
              </li>
              {WORKSPACES.filter((workspace) => hasWorkspaceAccess(session, workspace)).map(
                (workspace) => {
                  const active =
                    currentPath === workspace.href || currentPath.startsWith(`${workspace.href}/`);
                  return (
                    <li key={workspace.href}>
                      <a
                        className={active ? "nav-link active" : "nav-link"}
                        href={workspace.href}
                        aria-current={active ? "page" : undefined}
                        onClick={(event) => handleInternalNavigation(onNavigate, event)}
                      >
                        <span className="nav-symbol" aria-hidden="true">
                          {workspace.label.slice(0, 1)}
                        </span>
                        {workspace.label}
                      </a>
                    </li>
                  );
                },
              )}
            </ul>
          </nav>
          <div className="sidebar-note">
            <span className="note-icon" aria-hidden="true">
              i
            </span>
            <p>
              Navigation reflects your current server-reported access. Every API request is
              authorized by AyuVahak.
            </p>
          </div>
        </aside>

        <main id="main-content" className="main-content" tabIndex={-1}>
          <div className="page-heading">
            <div>
              <p className="eyebrow">AYUVAHAK WORKSPACE</p>
              <h1>{pageTitle(currentPath, activeWorkspace)}</h1>
            </div>
            <span className="scope-badge">Access projection loaded</span>
          </div>
          {activeWorkspace && !canAccessActiveWorkspace ? (
            <section className="message-card" role="status">
              <h2>Workspace access is not listed for this session</h2>
              <p>
                This presentation check does not grant access. AyuVahak APIs enforce the current
                grants and scopes for every request.
              </p>
              <a
                className="button button-primary"
                href="/"
                onClick={(event) => handleInternalNavigation(onNavigate, event)}
              >
                Return to overview
              </a>
            </section>
          ) : (
            (children ??
            (currentPath === "/" ? (
              <section className="welcome-card" aria-labelledby="welcome-title">
                <div className="welcome-icon" aria-hidden="true">
                  ↗
                </div>
                <p className="eyebrow">COORDINATION OVERVIEW</p>
                <h2 id="welcome-title">Operational summary not loaded</h2>
                <p>
                  This overview does not load operational records. Choose an authorized workspace to
                  request information from its owning AyuVahak service.
                </p>
                <div className="principle-row">
                  <span className="principle-check" aria-hidden="true">
                    ✓
                  </span>
                  <span>Operational state stays with its owning service.</span>
                </div>
                <div className="principle-row">
                  <span className="principle-check" aria-hidden="true">
                    ✓
                  </span>
                  <span>Unknown and stale information is never treated as zero.</span>
                </div>
              </section>
            ) : activeWorkspace ? (
              <section className="message-card">
                <p className="eyebrow">WORKSPACE FOUNDATION</p>
                <h2>{activeWorkspace.label} is available in your access projection</h2>
                <p>
                  This frontend foundation does not yet load operational records or perform domain
                  actions. Those must use the owning AyuVahak API and its server-side authorization.
                </p>
              </section>
            ) : (
              <section className="message-card" role="status">
                <h2>That workspace could not be found</h2>
                <a
                  className="button button-primary"
                  href="/"
                  onClick={(event) => handleInternalNavigation(onNavigate, event)}
                >
                  Return to overview
                </a>
              </section>
            )))
          )}
        </main>
      </div>
    </div>
  );
}
