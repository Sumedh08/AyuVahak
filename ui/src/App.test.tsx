import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { App } from "./App";
import type { SessionClient, SessionProjection } from "./api/session";
import type { AuthClient } from "./auth/oidc";

const session: SessionProjection = {
  deploymentMode: "EMPTY",
  subject: {
    kind: "HUMAN",
    issuer: "https://identity.synthetic.invalid/issuer",
    identifierType: "subject",
    identifier: "SYN-OPERATOR-001",
  },
  capabilities: [],
};

function authClient(token: string | null): AuthClient {
  return {
    configurationStatus: "configured",
    initialize: vi.fn().mockResolvedValue(token),
    getAccessToken: vi.fn().mockResolvedValue(token),
    signIn: vi.fn().mockResolvedValue(undefined),
    clear: vi.fn().mockResolvedValue(undefined),
  };
}

describe("authentication and session states", () => {
  it("renders an explicit loading state before checking the API session", () => {
    const auth = authClient(null);
    const sessionClient: SessionClient = { loadSession: vi.fn().mockResolvedValue(session) };
    render(<App authClient={auth} sessionClient={sessionClient} />);
    expect(screen.getByRole("status")).toHaveTextContent(/checking your ayuvahak session/i);
  });

  it("does not claim to have a session without an OIDC access token", async () => {
    const auth = authClient(null);
    const sessionClient: SessionClient = { loadSession: vi.fn().mockResolvedValue(session) };
    render(<App authClient={auth} sessionClient={sessionClient} />);
    expect(await screen.findByRole("heading", { name: "Sign in to AyuVahak" })).toBeInTheDocument();
    expect(sessionClient.loadSession).not.toHaveBeenCalled();
  });

  it("renders the root workspace after OIDC replaces the callback URL", async () => {
    window.history.replaceState(null, "", "/auth/callback");
    const auth = authClient("synthetic-token");
    vi.mocked(auth.initialize).mockImplementation(async () => {
      window.history.replaceState(null, "", "/");
      return "synthetic-token";
    });
    const sessionClient: SessionClient = { loadSession: vi.fn().mockResolvedValue(session) };

    render(<App authClient={auth} sessionClient={sessionClient} />);

    expect(await screen.findByRole("heading", { name: "Overview", level: 1 })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Overview", level: 2 })).toBeInTheDocument();
    expect(sessionClient.loadSession).toHaveBeenCalledWith(
      "synthetic-token",
      expect.any(AbortSignal),
    );
    window.history.replaceState(null, "", "/");
  });

  it("displays an explicit service-identity denial for the human workspace", async () => {
    const serviceSession: SessionProjection = {
      ...session,
      subject: {
        kind: "SERVICE",
        issuer: "https://identity.synthetic.invalid/issuer",
        identifierType: "client_id",
        identifier: "SYN-SERVICE-001",
      },
    };
    const sessionClient: SessionClient = { loadSession: vi.fn().mockResolvedValue(serviceSession) };
    render(<App authClient={authClient("synthetic-token")} sessionClient={sessionClient} />);
    expect(
      await screen.findByRole("heading", { name: "AyuVahak access was denied" }),
    ).toBeInTheDocument();
    expect(screen.getByText(/service identities cannot use the web client/i)).toBeInTheDocument();
  });
});
