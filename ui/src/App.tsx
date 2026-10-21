import { useCallback, useEffect, useMemo, useState } from "react";
import { type AnalyticsApiClient, createAnalyticsApiClient } from "./api/analytics";
import { createOwnerApiClient, type OwnerApiClient } from "./api/owner";
import {
  createReferralMatchingApiClient,
  type ReferralMatchingApiClient,
} from "./api/referral-matching";
import {
  createSessionClient,
  SessionApiError,
  type SessionClient,
  type SessionProjection,
} from "./api/session";
import { createTransferApiClient, type TransferApiClient } from "./api/transfer";
import type { AuthClient } from "./auth/oidc";
import { AppShell } from "./components/AppShell";
import { AnalyticsWorkspace } from "./workspaces/AnalyticsWorkspace";
import { CapacityWorkspace } from "./workspaces/CapacityWorkspace";
import { DirectoryWorkspace } from "./workspaces/DirectoryWorkspace";
import { MatchingWorkspace } from "./workspaces/MatchingWorkspace";
import { ReferralWorkspace } from "./workspaces/ReferralWorkspace";
import { TransferWorkspace } from "./workspaces/TransferWorkspace";
import { TransportWorkspace } from "./workspaces/TransportWorkspace";

const defaultSessionClient = createSessionClient();

type ViewState =
  | { readonly status: "loading" }
  | { readonly status: "configuration-missing" }
  | { readonly status: "configuration-invalid" }
  | { readonly status: "unauthenticated" }
  | { readonly status: "auth-error" }
  | { readonly status: "session-error" }
  | { readonly status: "denied"; readonly reason: "forbidden" | "service-identity" }
  | { readonly status: "authenticated"; readonly session: SessionProjection };

export interface AppProps {
  readonly authClient: AuthClient;
  readonly sessionClient?: SessionClient;
  readonly ownerApiClient?: OwnerApiClient;
  readonly referralMatchingApiClient?: ReferralMatchingApiClient;
  readonly transferApiClient?: TransferApiClient;
  readonly analyticsApiClient?: AnalyticsApiClient;
}

export function App({
  authClient,
  sessionClient = defaultSessionClient,
  ownerApiClient,
  referralMatchingApiClient,
  transferApiClient,
  analyticsApiClient,
}: AppProps) {
  const [view, setView] = useState<ViewState>({ status: "loading" });
  const [currentPath, setCurrentPath] = useState(() => window.location.pathname);
  const getAccessToken = useCallback(() => authClient.getAccessToken(), [authClient]);
  const ownerClient = useMemo(
    () => ownerApiClient ?? createOwnerApiClient(getAccessToken),
    [getAccessToken, ownerApiClient],
  );
  const referralMatchingClient = useMemo(
    () => referralMatchingApiClient ?? createReferralMatchingApiClient(getAccessToken),
    [getAccessToken, referralMatchingApiClient],
  );
  const transferClient = useMemo(
    () => transferApiClient ?? createTransferApiClient(getAccessToken),
    [getAccessToken, transferApiClient],
  );
  const analyticsClient = useMemo(
    () => analyticsApiClient ?? createAnalyticsApiClient(getAccessToken),
    [getAccessToken, analyticsApiClient],
  );

  useEffect(() => {
    const onPopState = () => setCurrentPath(window.location.pathname);
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, []);

  useEffect(() => {
    if (authClient.configurationStatus === "missing") {
      setView({ status: "configuration-missing" });
      return;
    }
    if (authClient.configurationStatus === "invalid") {
      setView({ status: "configuration-invalid" });
      return;
    }

    const controller = new AbortController();
    let active = true;
    void (async () => {
      try {
        const accessToken = await authClient.initialize();
        if (!active) return;
        if (window.location.pathname === "/auth/callback") setCurrentPath("/");
        if (!accessToken) {
          setView({ status: "unauthenticated" });
          return;
        }

        const session = await sessionClient.loadSession(accessToken, controller.signal);
        if (!active) return;
        if (session.subject.kind !== "HUMAN") {
          setView({ status: "denied", reason: "service-identity" });
          return;
        }
        setView({ status: "authenticated", session });
      } catch (error: unknown) {
        if (!active || controller.signal.aborted) return;
        if (error instanceof SessionApiError && error.code === "UNAUTHENTICATED") {
          setView({ status: "unauthenticated" });
        } else if (error instanceof SessionApiError && error.code === "FORBIDDEN") {
          setView({ status: "denied", reason: "forbidden" });
        } else {
          setView({ status: "session-error" });
        }
      }
    })();

    return () => {
      active = false;
      controller.abort();
    };
  }, [authClient, sessionClient]);

  async function beginSignIn(): Promise<void> {
    setView({ status: "loading" });
    try {
      await authClient.signIn();
    } catch {
      setView({ status: "auth-error" });
    }
  }

  async function signOut(): Promise<void> {
    await authClient.clear();
    setView({ status: "unauthenticated" });
  }

  const navigate = useCallback((path: string) => {
    window.history.pushState(null, "", path);
    setCurrentPath(window.location.pathname);
  }, []);

  switch (view.status) {
    case "loading":
      return (
        <main className="gate-page" aria-busy="true">
          <p role="status">Checking your AyuVahak session…</p>
        </main>
      );
    case "configuration-missing":
      return (
        <AuthGate title="Identity provider is not configured">
          <p>
            Set the deployment-controlled OIDC authority and public client identifier before signing
            in. This application does not create user passwords.
          </p>
        </AuthGate>
      );
    case "configuration-invalid":
      return (
        <AuthGate title="Identity provider configuration is invalid">
          <p>
            Check the deployment's OIDC authority, public client identifier, and scope
            configuration.
          </p>
        </AuthGate>
      );
    case "unauthenticated":
      return (
        <AuthGate title="Sign in to AyuVahak">
          <p>Use the identity provider configured by your deployment.</p>
          <button
            className="button button-primary"
            onClick={() => void beginSignIn()}
            type="button"
          >
            Continue to sign in
          </button>
        </AuthGate>
      );
    case "auth-error":
      return (
        <AuthGate title="Sign-in could not be started">
          <p>
            The configured identity provider could not start authentication. Try again or contact
            your administrator.
          </p>
          <button
            className="button button-primary"
            onClick={() => void beginSignIn()}
            type="button"
          >
            Try again
          </button>
        </AuthGate>
      );
    case "session-error":
      return (
        <AuthGate title="Session information is unavailable">
          <p>
            AyuVahak could not verify the session's current access projection. No workspace access
            is assumed.
          </p>
          <button
            className="button button-primary"
            onClick={() => window.location.reload()}
            type="button"
          >
            Retry
          </button>
        </AuthGate>
      );
    case "denied":
      return (
        <AuthGate title="AyuVahak access was denied">
          <p>
            {view.reason === "service-identity"
              ? "This interactive workspace requires a human identity. Service identities cannot use the web client."
              : "The server did not authorize access to the session projection. Contact your deployment administrator."}
          </p>
        </AuthGate>
      );
    case "authenticated": {
      const workspace =
        currentPath === "/directory" || currentPath.startsWith("/directory/") ? (
          <DirectoryWorkspace
            session={view.session}
            getAccessToken={getAccessToken}
            apiClient={ownerClient}
          />
        ) : currentPath === "/capacity" || currentPath.startsWith("/capacity/") ? (
          <CapacityWorkspace
            session={view.session}
            getAccessToken={getAccessToken}
            apiClient={ownerClient}
          />
        ) : currentPath === "/referrals" || currentPath.startsWith("/referrals/") ? (
          <ReferralWorkspace
            session={view.session}
            getAccessToken={getAccessToken}
            apiClient={referralMatchingClient}
          />
        ) : currentPath === "/matching" || currentPath.startsWith("/matching/") ? (
          <MatchingWorkspace
            session={view.session}
            getAccessToken={getAccessToken}
            apiClient={referralMatchingClient}
          />
        ) : currentPath === "/transfers" || currentPath.startsWith("/transfers/") ? (
          <TransferWorkspace
            session={view.session}
            getAccessToken={getAccessToken}
            apiClient={transferClient}
            view="transfers"
          />
        ) : currentPath === "/receiving" || currentPath.startsWith("/receiving/") ? (
          <TransferWorkspace
            session={view.session}
            getAccessToken={getAccessToken}
            apiClient={transferClient}
            view="receiving"
          />
        ) : currentPath === "/transport" || currentPath.startsWith("/transport/") ? (
          <TransportWorkspace
            session={view.session}
            getAccessToken={getAccessToken}
            apiClient={transferClient}
          />
        ) : currentPath === "/analytics" || currentPath.startsWith("/analytics/") ? (
          <AnalyticsWorkspace
            session={view.session}
            getAccessToken={getAccessToken}
            apiClient={analyticsClient}
          />
        ) : undefined;
      return (
        <AppShell
          session={view.session}
          currentPath={currentPath}
          onSignOut={() => void signOut()}
          onNavigate={navigate}
        >
          {workspace}
        </AppShell>
      );
    }
    default: {
      const exhaustive: never = view;
      return exhaustive;
    }
  }
}

interface AuthGateProps {
  readonly title: string;
  readonly children: React.ReactNode;
}

function AuthGate({ title, children }: AuthGateProps) {
  return (
    <main className="gate-page">
      <section className="gate-card" aria-labelledby="gate-title">
        <a className="brand gate-brand" href="/">
          <span className="brand-mark" aria-hidden="true">
            A
          </span>
          <span>
            <strong>AyuVahak</strong>
            <small>Care coordination</small>
          </span>
        </a>
        <p className="eyebrow">SECURE WORKSPACE</p>
        <h1 id="gate-title">{title}</h1>
        <div className="gate-message">{children}</div>
        <p className="gate-footnote">
          Authentication is provided by your configured identity provider.
        </p>
      </section>
    </main>
  );
}
